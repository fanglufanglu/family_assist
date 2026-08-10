const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const { URL } = require("url");
const crypto = require("crypto");
let PgPool = null;
try {
  PgPool = require("pg").Pool;
} catch (_) {
}

const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || "0.0.0.0";
const families = new Map();
const users = new Map();
const MAX_FAMILY_MEMBERS = 5;
const HELP_INVITE_TTL_MS = 2 * 60 * 1000;
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 8 * 1024 * 1024);
const DATA_DIR = process.env.RELAY_DATA_DIR || path.join(__dirname, "..", ".data");
const STATE_FILE = process.env.RELAY_STATE_FILE || path.join(DATA_DIR, "relay-state.json");
const SAVE_DEBOUNCE_MS = 250;
const CONTROL_REQUEST_COOLDOWN_MS = 15_000;
const CONTROL_ACTION_COOLDOWN_MS = 120;
const DATABASE_URL = process.env.DATABASE_URL || process.env.FAMILY_ASSIST_DATABASE_URL || "";
const STATE_ID = "main";
const PASSWORD_ITERATIONS = 310000;
const PASSWORD_KEY_LEN = 32;
const PASSWORD_DIGEST = "sha256";
const RESET_CODE_TTL_MS = 10 * 60 * 1000;
const RESET_REQUEST_COOLDOWN_MS = 60 * 1000;
const RESET_MAX_ATTEMPTS = 5;
const AUTH_WINDOW_MS = 10 * 60 * 1000;
const AUTH_MAX_ATTEMPTS = 10;
const SMS_WEBHOOK_URL = String(process.env.SMS_WEBHOOK_URL || "").trim();
const SMS_WEBHOOK_TOKEN = String(process.env.SMS_WEBHOOK_TOKEN || "").trim();
const RESET_CODE_EXPOSED = process.env.RESET_CODE_EXPOSED === "true";
const pgPool = DATABASE_URL && PgPool ? new PgPool({ connectionString: DATABASE_URL }) : null;
let saveTimer = null;
let storageReady = false;
const authAttempts = new Map();

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", (chunk) => {
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        reject(new Error("request body is too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(body);
}

function logRequest(req, res, startedAt) {
  const forwardedFor = req.headers["x-forwarded-for"] || "";
  const remote = forwardedFor || req.socket.remoteAddress || "";
  const userAgent = req.headers["user-agent"] || "";
  const elapsedMs = Date.now() - startedAt;
  let safeUrl = req.url;
  try {
    const parsed = new URL(req.url, "http://relay.local");
    for (const key of ["authToken", "accountToken", "pendingToken", "inviteCode"]) {
      if (parsed.searchParams.has(key)) parsed.searchParams.set(key, "[redacted]");
    }
    safeUrl = parsed.pathname + parsed.search;
  } catch (_) {
  }
  console.log(`${new Date().toISOString()} ${req.method} ${safeUrl} ${res.statusCode} ${elapsedMs}ms remote=${remote} ua="${userAgent}"`);
}

function familyFor(pairCode) {
  if (!families.has(pairCode)) {
    families.set(pairCode, newFamily());
  }
  return families.get(pairCode);
}

function newFamily() {
  return {
    sessionId: "",
    inviteCode: "",
    inviteExpiresAt: 0,
    members: new Map(),
    active: false,
    elderName: "",
    deviceName: "",
    updatedAt: "",
    frame: null,
    frameUpdatedAt: "",
    lastFamilySeenAtMs: 0,
    lastFamilySeenAt: "",
    masked: false,
    annotation: null,
    controlRequested: false,
    controlAllowed: false,
    controlDecision: "idle",
    controlReason: "",
    controlUpdatedAt: "",
    controlAction: null,
    lastControlRequestAtMs: 0,
    lastControlActionAtMs: 0,
    activeHelperToken: "",
    activeHelperName: "",
    targetHelperRef: "",
    targetHelperName: "",
    pendingHelpInvitation: null,
    pendingBindRequests: [],
    audit: [],
    crashes: [],
    webrtc: {
      offer: null,
      answer: null,
      elderIce: [],
      familyIce: [],
      updatedAt: "",
    },
  };
}

function makeToken() {
  return toBase64Url(crypto.randomBytes(24));
}

function toBase64Url(value) {
  return Buffer.from(value)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function makeInviteCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function makeSessionId() {
  return crypto.randomBytes(12).toString("hex");
}

function makeId(prefix) {
  return `${prefix}_${crypto.randomBytes(10).toString("hex")}`;
}

function requestAddress(req) {
  const forwarded = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  return forwarded || req.socket.remoteAddress || "unknown";
}

function rateLimitKey(req, scope, phone) {
  return `${scope}:${requestAddress(req)}:${phone || ""}`;
}

function isRateLimited(key, maxAttempts = AUTH_MAX_ATTEMPTS, windowMs = AUTH_WINDOW_MS) {
  const now = Date.now();
  if (authAttempts.size > 5000) {
    for (const [candidateKey, candidate] of authAttempts.entries()) {
      if (now - candidate.startedAt >= AUTH_WINDOW_MS) authAttempts.delete(candidateKey);
    }
  }
  const previous = authAttempts.get(key);
  const entry = !previous || now - previous.startedAt >= windowMs
    ? { startedAt: now, count: 0 }
    : previous;
  entry.count += 1;
  authAttempts.set(key, entry);
  return entry.count > maxAttempts;
}

function clearRateLimit(key) {
  authAttempts.delete(key);
}

function findUserByPhone(phone) {
  return [...users.entries()].find(([, user]) => user.phone === phone) || null;
}

function makeResetCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function hashResetCode(code, salt) {
  return toBase64Url(crypto.createHash("sha256").update(`${salt}:${code}`).digest());
}

function sendResetCode(phone, code) {
  if (!SMS_WEBHOOK_URL) {
    return RESET_CODE_EXPOSED
      ? Promise.resolve()
      : Promise.reject(new Error("sms service unavailable"));
  }
  return new Promise((resolve, reject) => {
    const target = new URL(SMS_WEBHOOK_URL);
    const body = Buffer.from(JSON.stringify({ phone, code, purpose: "password_reset", expiresInMinutes: 10 }));
    const transport = target.protocol === "https:" ? https : http;
    const request = transport.request(target, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": body.length,
        ...(SMS_WEBHOOK_TOKEN ? { Authorization: `Bearer ${SMS_WEBHOOK_TOKEN}` } : {}),
      },
      timeout: 5000,
    }, (response) => {
      response.resume();
      if (response.statusCode >= 200 && response.statusCode < 300) resolve();
      else reject(new Error(`sms webhook returned ${response.statusCode}`));
    });
    request.on("timeout", () => request.destroy(new Error("sms webhook timeout")));
    request.on("error", reject);
    request.end(body);
  });
}

function rotateAccountToken(oldToken, user) {
  const newToken = makeToken();
  users.delete(oldToken);
  users.set(newToken, user);
  for (const family of families.values()) {
    for (const member of family.members.values()) {
      if (member.accountToken === oldToken) member.accountToken = newToken;
    }
    for (const request of family.pendingBindRequests || []) {
      if (request.accountToken === oldToken) request.accountToken = newToken;
    }
  }
  return newToken;
}

function hashPassword(password, salt) {
  const actualSalt = salt || toBase64Url(crypto.randomBytes(16));
  const hash = toBase64Url(crypto.pbkdf2Sync(String(password), actualSalt, PASSWORD_ITERATIONS, PASSWORD_KEY_LEN, PASSWORD_DIGEST));
  return `pbkdf2_${PASSWORD_DIGEST}$${PASSWORD_ITERATIONS}$${actualSalt}$${hash}`;
}

function verifyPassword(password, stored) {
  const parts = String(stored || "").split("$");
  if (parts.length !== 4 || parts[0] !== `pbkdf2_${PASSWORD_DIGEST}`) return false;
  const iterations = Number(parts[1]);
  const salt = parts[2];
  const expected = parts[3];
  if (!Number.isFinite(iterations) || !salt || !expected) return false;
  const actual = toBase64Url(crypto.pbkdf2Sync(String(password), salt, iterations, PASSWORD_KEY_LEN, PASSWORD_DIGEST));
  return crypto.timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}

function userForToken(accountToken) {
  return accountToken ? users.get(accountToken) : null;
}

function publicUser(user) {
  if (!user) return null;
  return {
    id: user.id,
    phone: user.phone,
    name: user.name,
    createdAt: user.createdAt,
  };
}

function membershipsForAccount(accountToken) {
  const memberships = [];
  for (const [pairCode, family] of families.entries()) {
    for (const [authToken, member] of family.members.entries()) {
      if (member.accountToken !== accountToken) continue;
      memberships.push({
        pairCode,
        authToken,
        role: member.role,
        name: member.name || "",
        elderName: family.elderName || "长辈",
        familyMemberCount: familyMembers(family).length,
        active: Boolean(family.active),
        updatedAt: family.updatedAt || "",
      });
    }
  }
  return memberships.sort((left, right) => String(right.updatedAt).localeCompare(String(left.updatedAt)));
}

function statePayload() {
  return {
    version: 2,
    savedAt: new Date().toISOString(),
    users: [...users.entries()],
    families: [...families.entries()].map(([pairCode, family]) => [pairCode, serializeFamily(family)]),
  };
}

function applyStatePayload(parsed) {
  families.clear();
  users.clear();
  const entries = Array.isArray(parsed && parsed.families) ? parsed.families : [];
  for (const [pairCode, rawFamily] of entries) {
    if (pairCode) families.set(pairCode, hydrateFamily(rawFamily));
  }
  const userEntries = Array.isArray(parsed && parsed.users) ? parsed.users : [];
  for (const [token, user] of userEntries) {
    if (token && user) users.set(token, user);
  }
}

function iceConfig() {
  const urls = String(process.env.TURN_URLS || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  const servers = [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }];
  if (urls.length > 0) {
    servers.push({
      urls,
      username: String(process.env.TURN_USERNAME || ""),
      credential: String(process.env.TURN_CREDENTIAL || ""),
    });
  }
  return servers;
}

function familyMembers(family) {
  return [...family.members.entries()].filter(([, member]) => member.role === "family");
}

function memberReference(authToken, member) {
  if (member && member.userId) return String(member.userId);
  return "member_" + crypto.createHash("sha256").update(String(authToken || "")).digest("hex").slice(0, 16);
}

function maskMemberPhone(phone) {
  const value = String(phone || "");
  if (value.length < 7) return "";
  return value.slice(0, 3) + "****" + value.slice(-4);
}

function publicFamily(family, member, authToken) {
  const relatives = familyMembers(family);
  const pendingBindRequests = Array.isArray(family.pendingBindRequests) ? family.pendingBindRequests : [];
  return {
    active: family.active,
    sessionId: family.sessionId,
    elderName: family.elderName,
    deviceName: family.deviceName,
    updatedAt: family.updatedAt,
    frameUpdatedAt: family.frameUpdatedAt,
    lastFamilySeenAtMs: family.lastFamilySeenAtMs,
    lastFamilySeenAt: family.lastFamilySeenAt,
    masked: family.masked,
    controlRequested: family.controlRequested,
    controlAllowed: family.controlAllowed,
    controlDecision: family.controlDecision,
    controlReason: family.controlReason,
    controlUpdatedAt: family.controlUpdatedAt,
    invitePending: Boolean(family.inviteCode) && Date.now() <= family.inviteExpiresAt,
    inviteExpiresAt: family.inviteExpiresAt,
    memberCount: family.members.size,
    familyMemberCount: relatives.length,
    maxFamilyMembers: MAX_FAMILY_MEMBERS,
    helperJoined: Boolean(family.activeHelperToken),
    helperIsCurrent: Boolean(authToken) && family.activeHelperToken === authToken,
    helperName: family.activeHelperName,
    targetHelperName: family.targetHelperName,
    targetedForCurrent: !family.targetHelperRef
      || (member && member.role === "elder")
      || (member && member.role === "family" && memberReference(authToken, member) === family.targetHelperRef),
    familyMembers: member && member.role === "elder"
      ? relatives.map(([token, relative]) => ({
        ref: memberReference(token, relative),
        name: relative.name || "家属",
        phone: maskMemberPhone(relative.phone),
        createdAt: relative.createdAt || "",
      }))
      : [],
    helpInvitation: publicHelpInvitation(family, member, authToken),
    pendingBindCount: pendingBindRequests.length,
    pendingBindRequests: member && member.role === "elder"
      ? pendingBindRequests.map((item) => ({
        id: item.id,
        requesterName: item.requesterName,
        requesterPhone: item.requesterPhone,
        createdAt: item.createdAt,
        expiresAt: item.expiresAt,
      }))
      : [],
  };
}

function publicHelpInvitation(family, member, authToken) {
  const invitation = family.pendingHelpInvitation;
  if (!invitation) return null;
  if ((invitation.status === "pending" || invitation.status === "accepted")
      && Date.now() > Number(invitation.expiresAt || 0)) {
    invitation.status = "expired";
    invitation.updatedAt = new Date().toISOString();
    scheduleSave();
  }
  const currentRef = member && member.role === "family" ? memberReference(authToken, member) : "";
  if (!member || (member.role === "family" && invitation.targetHelperRef !== currentRef)) return null;
  return {
    id: invitation.id,
    status: invitation.status,
    elderName: invitation.elderName,
    targetHelperName: invitation.targetHelperName,
    createdAt: invitation.createdAt,
    updatedAt: invitation.updatedAt,
    expiresAt: invitation.expiresAt,
  };
}

function requireMember(res, pairCode, authToken, role) {
  const family = pairCode ? familyFor(pairCode) : null;
  const member = family && authToken ? family.members.get(authToken) : null;
  if (!family || !member || (role && member.role !== role)) {
    sendJson(res, 403, { error: "not bound" });
    return null;
  }
  return { family, member };
}

function requireActiveHelper(res, result, authToken) {
  if (!result.family.active || result.family.activeHelperToken !== authToken) {
    sendJson(res, 409, { error: "another family member is assisting" });
    return false;
  }
  return true;
}

function audit(family, type, detail) {
  family.audit.push({
    id: crypto.randomBytes(8).toString("hex"),
    type,
    detail: detail || {},
    createdAt: new Date().toISOString(),
  });
  if (family.audit.length > 200) {
    family.audit.splice(0, family.audit.length - 200);
  }
  scheduleSave();
}

function rememberCrash(family, payload, member) {
  family.crashes.push({
    id: crypto.randomBytes(8).toString("hex"),
    role: member ? member.role : String(payload.role || ""),
    message: String(payload.message || ""),
    stack: String(payload.stack || "").slice(0, 8000),
    device: String(payload.device || ""),
    appVersion: String(payload.appVersion || ""),
    createdAt: new Date().toISOString(),
  });
  if (family.crashes.length > 50) {
    family.crashes.splice(0, family.crashes.length - 50);
  }
  scheduleSave();
}

function clamp01(value, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(0, Math.min(1, number));
}

function serializeFamily(family) {
  return {
    ...family,
    frame: null,
    annotation: null,
    controlAction: null,
    active: false,
    sessionId: "",
    activeHelperToken: "",
    activeHelperName: "",
    targetHelperRef: "",
    targetHelperName: "",
    pendingHelpInvitation: family.pendingHelpInvitation
      && (family.pendingHelpInvitation.status === "pending" || family.pendingHelpInvitation.status === "accepted")
      && Date.now() <= Number(family.pendingHelpInvitation.expiresAt || 0)
      ? family.pendingHelpInvitation
      : null,
    pendingBindRequests: (family.pendingBindRequests || []).filter((item) => Date.now() <= Number(item.expiresAt || 0)),
    members: [...family.members.entries()],
    webrtc: {
      offer: null,
      answer: null,
      elderIce: [],
      familyIce: [],
      updatedAt: family.webrtc && family.webrtc.updatedAt ? family.webrtc.updatedAt : "",
    },
  };
}

function hydrateFamily(raw) {
  const family = { ...newFamily(), ...(raw || {}) };
  family.members = new Map(Array.isArray(raw && raw.members) ? raw.members : []);
  family.pendingBindRequests = Array.isArray(raw && raw.pendingBindRequests)
    ? raw.pendingBindRequests.filter((item) => Date.now() <= Number(item.expiresAt || 0))
    : [];
  family.frame = null;
  family.annotation = null;
  family.controlAction = null;
  family.active = false;
  family.sessionId = "";
  family.activeHelperToken = "";
  family.activeHelperName = "";
  family.targetHelperRef = "";
  family.targetHelperName = "";
  if (family.pendingHelpInvitation
      && ((family.pendingHelpInvitation.status !== "pending" && family.pendingHelpInvitation.status !== "accepted")
        || Date.now() > Number(family.pendingHelpInvitation.expiresAt || 0))) {
    family.pendingHelpInvitation = null;
  }
  family.webrtc = {
    offer: null,
    answer: null,
    elderIce: [],
    familyIce: [],
    updatedAt: "",
  };
  return family;
}

async function loadState() {
  if (pgPool) {
    try {
      await pgPool.query(`
        CREATE TABLE IF NOT EXISTS relay_state (
          id TEXT PRIMARY KEY,
          payload JSONB NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      `);
      const result = await pgPool.query("SELECT payload FROM relay_state WHERE id = $1", [STATE_ID]);
      if (result.rows.length > 0) {
        applyStatePayload(result.rows[0].payload);
      } else if (fs.existsSync(STATE_FILE)) {
        const parsed = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
        applyStatePayload(parsed);
        await saveStateNow();
        console.log(`Migrated local JSON relay state into PostgreSQL from ${STATE_FILE}`);
      }
      storageReady = true;
      console.log(`Loaded ${families.size} families and ${users.size} users from PostgreSQL.`);
      return;
    } catch (error) {
      console.error(`Failed to load relay state from PostgreSQL: ${error.message}`);
      throw error;
    }
  }
  try {
    if (!fs.existsSync(STATE_FILE)) return;
    const parsed = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
    applyStatePayload(parsed);
    storageReady = true;
    console.log(`Loaded ${families.size} persisted families and ${users.size} users from ${STATE_FILE}`);
  } catch (error) {
    console.error(`Failed to load relay state: ${error.message}`);
  }
}

async function saveStateNow() {
  if (pgPool) {
    try {
      await pgPool.query(
        `INSERT INTO relay_state (id, payload, updated_at)
         VALUES ($1, $2::jsonb, now())
         ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()`,
        [STATE_ID, JSON.stringify(statePayload())]
      );
    } catch (error) {
      console.error(`Failed to save relay state to PostgreSQL: ${error.message}`);
    }
    return;
  }
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
    const payload = statePayload();
    const tempFile = `${STATE_FILE}.tmp`;
    fs.writeFileSync(tempFile, JSON.stringify(payload, null, 2), { mode: 0o600 });
    fs.renameSync(tempFile, STATE_FILE);
  } catch (error) {
    console.error(`Failed to save relay state: ${error.message}`);
  }
}

function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    saveStateNow();
  }, SAVE_DEBOUNCE_MS);
}

function resetSessionState(family) {
  family.active = false;
  family.sessionId = "";
  family.annotation = null;
  family.frame = null;
  family.frameUpdatedAt = "";
  family.controlAllowed = false;
  family.controlRequested = false;
  family.controlDecision = "idle";
  family.controlReason = "";
  family.controlAction = null;
  family.lastFamilySeenAtMs = 0;
  family.lastFamilySeenAt = "";
  family.activeHelperToken = "";
  family.activeHelperName = "";
  family.targetHelperRef = "";
  family.targetHelperName = "";
  family.pendingHelpInvitation = null;
  family.updatedAt = new Date().toISOString();
  family.webrtc = {
    offer: null,
    answer: null,
    elderIce: [],
    familyIce: [],
    updatedAt: family.updatedAt,
  };
  scheduleSave();
}

const server = http.createServer(async (req, res) => {
  const startedAt = Date.now();
  res.on("finish", () => logRequest(req, res, startedAt));
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/ice-config") {
      sendJson(res, 200, { ok: true, iceServers: iceConfig() });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/account/register") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const phone = String(payload.phone || "").replace(/[^\d+]/g, "").trim();
      const password = String(payload.password || "");
      const name = String(payload.name || "").trim() || "亲友";
      const limitKey = rateLimitKey(req, "register", phone);
      if (isRateLimited(limitKey, 5)) {
        sendJson(res, 429, { error: "too many account attempts" });
        return;
      }
      if (phone.length < 6 || phone.length > 18) {
        sendJson(res, 400, { error: "valid phone is required" });
        return;
      }
      if (password.length < 8 || password.length > 64) {
        sendJson(res, 400, { error: "password must be 8-64 characters" });
        return;
      }
      const existing = findUserByPhone(phone);
      if (existing) {
        sendJson(res, 409, { error: "phone is already registered" });
        return;
      }
      const accountToken = makeToken();
      const nowIso = new Date().toISOString();
      const user = {
        id: makeId("user"),
        phone,
        name,
        passwordHash: hashPassword(password),
        createdAt: nowIso,
        lastLoginAt: nowIso,
      };
      users.set(accountToken, user);
      clearRateLimit(limitKey);
      scheduleSave();
      sendJson(res, 200, { ok: true, accountToken, user: publicUser(user), memberships: [] });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/account/login") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const phone = String(payload.phone || "").replace(/[^\d+]/g, "").trim();
      const password = String(payload.password || "");
      const limitKey = rateLimitKey(req, "login", phone);
      if (isRateLimited(limitKey)) {
        sendJson(res, 429, { error: "too many account attempts" });
        return;
      }
      if (phone.length < 6 || phone.length > 18) {
        sendJson(res, 400, { error: "valid phone is required" });
        return;
      }
      if (password.length < 6 || password.length > 64) {
        sendJson(res, 400, { error: "valid password is required" });
        return;
      }
      const existing = findUserByPhone(phone);
      if (!existing || !verifyPassword(password, existing[1].passwordHash)) {
        sendJson(res, 403, { error: "invalid phone or password" });
        return;
      }
      const accountToken = existing[0];
      const user = existing[1];
      clearRateLimit(limitKey);
      const nowIso = new Date().toISOString();
      user.lastLoginAt = nowIso;
      users.set(accountToken, user);
      scheduleSave();
      sendJson(res, 200, {
        ok: true,
        accountToken,
        user: publicUser(user),
        memberships: membershipsForAccount(accountToken),
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/account/password/reset/request") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const phone = String(payload.phone || "").replace(/[^\d+]/g, "").trim();
      const limitKey = rateLimitKey(req, "reset-request", phone);
      if (phone.length < 6 || phone.length > 18) {
        sendJson(res, 400, { error: "valid phone is required" });
        return;
      }
      if (isRateLimited(limitKey, 3, RESET_REQUEST_COOLDOWN_MS)) {
        sendJson(res, 429, { error: "too many reset attempts" });
        return;
      }
      const existing = findUserByPhone(phone);
      if (existing) {
        const code = makeResetCode();
        const salt = toBase64Url(crypto.randomBytes(12));
        existing[1].passwordReset = {
          salt,
          codeHash: hashResetCode(code, salt),
          expiresAt: Date.now() + RESET_CODE_TTL_MS,
          attempts: 0,
        };
        try {
          await sendResetCode(phone, code);
          scheduleSave();
          sendJson(res, 200, {
            ok: true,
            message: "reset code sent",
            ...(RESET_CODE_EXPOSED ? { debugCode: code } : {}),
          });
          return;
        } catch (error) {
          delete existing[1].passwordReset;
          sendJson(res, 503, { error: "sms service unavailable" });
          return;
        }
      }
      sendJson(res, 200, { ok: true, message: "reset code sent" });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/account/password/reset/confirm") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const phone = String(payload.phone || "").replace(/[^\d+]/g, "").trim();
      const code = String(payload.code || "").trim();
      const password = String(payload.password || "");
      const limitKey = rateLimitKey(req, "reset-confirm", phone);
      if (isRateLimited(limitKey, RESET_MAX_ATTEMPTS, RESET_CODE_TTL_MS)) {
        sendJson(res, 429, { error: "too many reset attempts" });
        return;
      }
      if (password.length < 8 || password.length > 64) {
        sendJson(res, 400, { error: "password must be 8-64 characters" });
        return;
      }
      const existing = findUserByPhone(phone);
      const reset = existing && existing[1].passwordReset;
      const valid = reset
        && Date.now() <= Number(reset.expiresAt || 0)
        && reset.attempts < RESET_MAX_ATTEMPTS
        && code.length === 6
        && crypto.timingSafeEqual(
          Buffer.from(hashResetCode(code, reset.salt)),
          Buffer.from(String(reset.codeHash || ""))
        );
      if (!valid) {
        if (reset) reset.attempts = Number(reset.attempts || 0) + 1;
        scheduleSave();
        sendJson(res, 403, { error: "invalid or expired reset code" });
        return;
      }
      const oldToken = existing[0];
      const user = existing[1];
      user.passwordHash = hashPassword(password);
      user.passwordChangedAt = new Date().toISOString();
      delete user.passwordReset;
      const accountToken = rotateAccountToken(oldToken, user);
      clearRateLimit(limitKey);
      scheduleSave();
      sendJson(res, 200, {
        ok: true,
        accountToken,
        user: publicUser(user),
        memberships: membershipsForAccount(accountToken),
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/account/delete") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const accountToken = String(payload.accountToken || "").trim();
      const password = String(payload.password || "");
      const user = userForToken(accountToken);
      if (!user || !verifyPassword(password, user.passwordHash)) {
        sendJson(res, 403, { error: "invalid phone or password" });
        return;
      }
      for (const [pairCode, family] of [...families.entries()]) {
        const ownedElder = [...family.members.values()].some((member) =>
          member.role === "elder" && member.accountToken === accountToken
        );
        if (ownedElder) {
          resetSessionState(family);
          families.delete(pairCode);
          continue;
        }
        for (const [token, member] of [...family.members.entries()]) {
          if (member.accountToken !== accountToken) continue;
          if (family.activeHelperToken === token) resetSessionState(family);
          family.members.delete(token);
        }
        family.pendingBindRequests = (family.pendingBindRequests || [])
          .filter((item) => item.accountToken !== accountToken);
        family.updatedAt = new Date().toISOString();
      }
      users.delete(accountToken);
      await saveStateNow();
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/account/me") {
      const accountToken = String(url.searchParams.get("accountToken") || "").trim();
      const user = userForToken(accountToken);
      if (!user) {
        sendJson(res, 403, { error: "not logged in" });
        return;
      }
      sendJson(res, 200, {
        ok: true,
        user: publicUser(user),
        memberships: membershipsForAccount(accountToken),
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/invite") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      if (!pairCode) {
        sendJson(res, 400, { error: "pairCode is required" });
        return;
      }
      const family = familyFor(pairCode);
      const accountToken = String(payload.accountToken || "").trim();
      const elderUser = userForToken(accountToken);
      const requestedToken = String(payload.authToken || "").trim();
      const existingElder = requestedToken ? family.members.get(requestedToken) : null;
      const retainingFamily = existingElder && existingElder.role === "elder";
      if (family.active) {
        sendJson(res, 409, { error: "assist session is active" });
        return;
      }
      const elderToken = retainingFamily ? requestedToken : makeToken();
      if (!retainingFamily) family.members.clear();
      family.inviteCode = makeInviteCode();
      family.inviteExpiresAt = Date.now() + 10 * 60 * 1000;
      family.elderName = String(payload.elderName || "长辈");
      family.deviceName = String(payload.deviceName || "");
      family.updatedAt = new Date().toISOString();
      family.sessionId = "";
      family.active = false;
      family.annotation = null;
      family.controlRequested = false;
      family.controlAllowed = false;
      family.controlDecision = "idle";
      family.controlReason = "";
      family.controlAction = null;
      family.pendingBindRequests = [];
      family.activeHelperToken = "";
      family.activeHelperName = "";
      family.audit = [];
      family.crashes = [];
      family.frame = null;
      family.frameUpdatedAt = "";
      family.lastFamilySeenAtMs = 0;
      family.lastFamilySeenAt = "";
      family.webrtc = {
        offer: null,
        answer: null,
        elderIce: [],
        familyIce: [],
        updatedAt: family.updatedAt,
      };
      family.members.set(elderToken, {
        role: "elder",
        name: family.elderName,
        deviceId: String(payload.deviceId || ""),
        accountToken,
        userId: elderUser ? elderUser.id : "",
        phone: elderUser ? elderUser.phone : "",
        createdAt: family.updatedAt,
      });
      audit(family, "invite_created", { elderName: family.elderName, familyMemberCount: familyMembers(family).length });
      sendJson(res, 200, {
        ok: true,
        inviteCode: family.inviteCode,
        inviteExpiresAt: family.inviteExpiresAt,
        authToken: elderToken,
        familyMemberCount: familyMembers(family).length,
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/bind") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      let pairCode = String(payload.pairCode || "").trim();
      const inviteCode = String(payload.inviteCode || "").trim();
      let family = pairCode ? families.get(pairCode) : null;
      if (!family || inviteCode !== family.inviteCode || Date.now() > family.inviteExpiresAt) {
        for (const [candidatePairCode, candidate] of families.entries()) {
          if (inviteCode && inviteCode === candidate.inviteCode && Date.now() <= candidate.inviteExpiresAt) {
            pairCode = candidatePairCode;
            family = candidate;
            break;
          }
        }
      }
      if (!family || !inviteCode || inviteCode !== family.inviteCode || Date.now() > family.inviteExpiresAt) {
        sendJson(res, 403, { error: "invalid or expired invite code" });
        return;
      }
      if (family.active) {
        sendJson(res, 409, { error: "assist session is active" });
        return;
      }
      const accountToken = String(payload.accountToken || "").trim();
      const requester = userForToken(accountToken);
      if (requester) {
        const existingPending = (family.pendingBindRequests || []).find((item) =>
          item.accountToken === accountToken || (item.deviceId && item.deviceId === String(payload.deviceId || ""))
        );
        if (existingPending && Date.now() <= Number(existingPending.expiresAt || 0)) {
          sendJson(res, 200, {
            ok: true,
            pendingApproval: true,
            pendingToken: existingPending.pendingToken,
            pairCode,
            message: "waiting for elder approval",
          });
          return;
        }
        const pendingToken = makeToken();
        family.pendingBindRequests = (family.pendingBindRequests || [])
          .filter((item) => Date.now() <= Number(item.expiresAt || 0));
        family.pendingBindRequests.push({
          id: makeId("bind"),
          pendingToken,
          accountToken,
          requesterUserId: requester.id,
          requesterName: String(payload.familyName || requester.name || "家属"),
          requesterPhone: requester.phone,
          deviceId: String(payload.deviceId || ""),
          createdAt: new Date().toISOString(),
          expiresAt: Date.now() + 10 * 60 * 1000,
        });
        family.updatedAt = new Date().toISOString();
        audit(family, "family_bind_requested", { name: String(payload.familyName || requester.name || "家属") });
        sendJson(res, 200, {
          ok: true,
          pendingApproval: true,
          pendingToken,
          pairCode,
          message: "waiting for elder approval",
        });
        return;
      }
      const existing = familyMembers(family).find(([, member]) => member.deviceId && member.deviceId === String(payload.deviceId || ""));
      if (existing) {
        sendJson(res, 200, { ok: true, pairCode, authToken: existing[0], alreadyBound: true, familyMemberCount: familyMembers(family).length });
        return;
      }
      if (familyMembers(family).length >= MAX_FAMILY_MEMBERS) {
        sendJson(res, 409, { error: "family member limit reached", maxFamilyMembers: MAX_FAMILY_MEMBERS });
        return;
      }
      const authToken = makeToken();
      family.members.set(authToken, {
        role: "family",
        name: String(payload.familyName || "家属"),
        deviceId: String(payload.deviceId || ""),
        createdAt: new Date().toISOString(),
      });
      family.updatedAt = new Date().toISOString();
      audit(family, "family_bound", { name: String(payload.familyName || "家属") });
      sendJson(res, 200, { ok: true, pairCode, authToken, familyMemberCount: familyMembers(family).length });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/unbind") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (result.family.active) {
        sendJson(res, 409, { error: "assist session is active" });
        return;
      }
      const name = result.member.name || "家属";
      result.family.members.delete(authToken);
      result.family.updatedAt = new Date().toISOString();
      audit(result.family, "family_unbound", { name });
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/bind/confirm") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const requestId = String(payload.requestId || "").trim();
      const approved = Boolean(payload.approved);
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const family = result.family;
      const pendingRequests = Array.isArray(family.pendingBindRequests) ? family.pendingBindRequests : [];
      const index = pendingRequests.findIndex((item) => item.id === requestId && Date.now() <= Number(item.expiresAt || 0));
      if (index < 0) {
        sendJson(res, 404, { error: "pending request not found" });
        return;
      }
      const request = pendingRequests[index];
      pendingRequests.splice(index, 1);
      family.pendingBindRequests = pendingRequests;
      if (!approved) {
        family.updatedAt = new Date().toISOString();
        audit(family, "family_bind_rejected", { name: request.requesterName, phone: request.requesterPhone });
        sendJson(res, 200, { ok: true, approved: false, family: publicFamily(family, result.member, authToken) });
        return;
      }
      if (family.active) {
        sendJson(res, 409, { error: "assist session is active" });
        return;
      }
      if (familyMembers(family).length >= MAX_FAMILY_MEMBERS) {
        sendJson(res, 409, { error: "family member limit reached", maxFamilyMembers: MAX_FAMILY_MEMBERS });
        return;
      }
      const familyToken = makeToken();
      family.members.set(familyToken, {
        role: "family",
        name: request.requesterName || "家属",
        deviceId: request.deviceId || "",
        accountToken: request.accountToken || "",
        userId: request.requesterUserId || "",
        phone: request.requesterPhone || "",
        createdAt: new Date().toISOString(),
      });
      family.updatedAt = new Date().toISOString();
      audit(family, "family_bind_approved", { name: request.requesterName, phone: request.requesterPhone });
      sendJson(res, 200, { ok: true, approved: true, familyMemberCount: familyMembers(family).length, family: publicFamily(family, result.member, authToken) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/bind/pending") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const pendingToken = String(url.searchParams.get("pendingToken") || "").trim();
      const family = pairCode ? families.get(pairCode) : null;
      if (!family || !pendingToken) {
        sendJson(res, 404, { error: "pending request not found" });
        return;
      }
      const pending = (family.pendingBindRequests || []).find((item) => item.pendingToken === pendingToken);
      if (pending && Date.now() <= Number(pending.expiresAt || 0)) {
        sendJson(res, 200, { ok: true, pendingApproval: true, expiresAt: pending.expiresAt });
        return;
      }
      const member = familyMembers(family).find(([, item]) => item.accountToken && item.accountToken === String(url.searchParams.get("accountToken") || "").trim());
      if (member) {
        sendJson(res, 200, { ok: true, approved: true, pairCode, authToken: member[0], familyMemberCount: familyMembers(family).length });
        return;
      }
      sendJson(res, 403, { error: "binding was not approved or expired" });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/invite/cancel") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      result.family.inviteCode = "";
      result.family.inviteExpiresAt = 0;
      result.family.updatedAt = new Date().toISOString();
      audit(result.family, "invite_cancelled", {});
      sendJson(res, 200, { ok: true, family: publicFamily(result.family, result.member, authToken) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/bind/status") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken);
      if (!result) return;
      if (Array.isArray(result.family.pendingBindRequests)) {
        const before = result.family.pendingBindRequests.length;
        result.family.pendingBindRequests = result.family.pendingBindRequests.filter((item) => Date.now() <= Number(item.expiresAt || 0));
        if (result.family.pendingBindRequests.length !== before) {
          audit(result.family, "expired_bind_requests_cleaned", { count: before - result.family.pendingBindRequests.length });
        }
      }
      sendJson(res, 200, { ok: true, member: result.member, family: publicFamily(result.family, result.member, authToken) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/help/invite") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const targetHelperRef = String(payload.targetHelperRef || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      if (result.family.active) {
        sendJson(res, 409, { error: "assist session is active" });
        return;
      }
      const target = familyMembers(result.family)
        .find(([token, member]) => memberReference(token, member) === targetHelperRef);
      if (!target) {
        sendJson(res, 404, { error: "family member not found" });
        return;
      }
      const now = new Date().toISOString();
      result.family.pendingHelpInvitation = {
        id: makeId("help"),
        status: "pending",
        elderName: String(payload.elderName || result.family.elderName || "长辈"),
        targetHelperRef,
        targetHelperName: target[1].name || "家属",
        createdAt: now,
        updatedAt: now,
        expiresAt: Date.now() + HELP_INVITE_TTL_MS,
      };
      result.family.updatedAt = now;
      audit(result.family, "help_invited", {
        invitationId: result.family.pendingHelpInvitation.id,
        targetHelperName: result.family.pendingHelpInvitation.targetHelperName,
      });
      sendJson(res, 200, {
        ok: true,
        invitation: publicHelpInvitation(result.family, result.member, authToken),
      });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/help/invite") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken);
      if (!result) return;
      sendJson(res, 200, {
        ok: true,
        invitation: publicHelpInvitation(result.family, result.member, authToken),
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/help/invite/respond") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const invitationId = String(payload.invitationId || "").trim();
      const accepted = Boolean(payload.accepted);
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      const invitation = result.family.pendingHelpInvitation;
      const currentRef = memberReference(authToken, result.member);
      if (!invitation || invitation.id !== invitationId || invitation.targetHelperRef !== currentRef) {
        sendJson(res, 404, { error: "help invitation not found" });
        return;
      }
      if (invitation.status !== "pending" || Date.now() > Number(invitation.expiresAt || 0)) {
        invitation.status = "expired";
        invitation.updatedAt = new Date().toISOString();
        scheduleSave();
        sendJson(res, 409, { error: "help invitation expired" });
        return;
      }
      invitation.status = accepted ? "accepted" : "declined";
      invitation.updatedAt = new Date().toISOString();
      if (accepted) invitation.expiresAt = Date.now() + HELP_INVITE_TTL_MS;
      result.family.updatedAt = invitation.updatedAt;
      audit(result.family, accepted ? "help_invite_accepted" : "help_invite_declined", {
        invitationId,
        name: result.member.name || "家属",
      });
      sendJson(res, 200, {
        ok: true,
        invitation: publicHelpInvitation(result.family, result.member, authToken),
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/help") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const { family } = result;
      if (familyMembers(family).length === 0) {
        sendJson(res, 409, { error: "no family member is bound" });
        return;
      }
      const targetHelperRef = String(payload.targetHelperRef || "").trim();
      const invitationId = String(payload.helpInvitationId || "").trim();
      const targetHelper = targetHelperRef
        ? familyMembers(family).find(([token, member]) => memberReference(token, member) === targetHelperRef)
        : null;
      if (targetHelperRef && !targetHelper) {
        sendJson(res, 404, { error: "family member not found" });
        return;
      }
      if (targetHelperRef) {
        const invitation = family.pendingHelpInvitation;
        if (!invitation || invitation.id !== invitationId
            || invitation.targetHelperRef !== targetHelperRef
            || invitation.status !== "accepted") {
          sendJson(res, 409, { error: "help invitation was not accepted" });
          return;
        }
      }
      family.sessionId = makeSessionId();
      family.active = true;
      family.elderName = String(payload.elderName || family.elderName || "长辈");
      family.deviceName = String(payload.deviceName || "");
      family.masked = Boolean(payload.masked);
      family.updatedAt = new Date().toISOString();
      family.frame = null;
      family.frameUpdatedAt = "";
      family.lastFamilySeenAtMs = 0;
      family.lastFamilySeenAt = "";
      family.controlRequested = false;
      family.controlAllowed = false;
      family.controlDecision = "idle";
      family.controlReason = "";
      family.controlUpdatedAt = family.updatedAt;
      family.controlAction = null;
      family.activeHelperToken = targetHelper ? targetHelper[0] : "";
      family.activeHelperName = targetHelper ? (targetHelper[1].name || "家属") : "";
      family.targetHelperRef = targetHelperRef;
      family.targetHelperName = targetHelper ? (targetHelper[1].name || "家属") : "";
      family.pendingHelpInvitation = null;
      family.webrtc = {
        offer: null,
        answer: null,
        elderIce: [],
        familyIce: [],
        updatedAt: family.updatedAt,
      };
      audit(family, "help_started", {
        sessionId: family.sessionId,
        elderName: family.elderName,
        targetHelperName: family.targetHelperName,
      });
      sendJson(res, 200, { ok: true, sessionId: family.sessionId, family: publicFamily(family, result.member, authToken) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/help") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      const currentHelperRef = memberReference(authToken, result.member);
      const targetedForCurrent = !result.family.targetHelperRef || result.family.targetHelperRef === currentHelperRef;
      if (result.family.active && targetedForCurrent && !result.family.activeHelperToken) {
        result.family.activeHelperToken = authToken;
        result.family.activeHelperName = result.member.name || "家属";
        audit(result.family, "family_joined", { name: result.family.activeHelperName, sessionId: result.family.sessionId });
      }
      if (result.family.activeHelperToken === authToken) {
        result.family.lastFamilySeenAtMs = Date.now();
        result.family.lastFamilySeenAt = new Date(result.family.lastFamilySeenAtMs).toISOString();
      }
      sendJson(res, 200, publicFamily(result.family, result.member, authToken));
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/end") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const sessionId = String(payload.sessionId || "").trim();
      if (result.family.active && (!sessionId || sessionId !== result.family.sessionId)) {
        sendJson(res, 200, { ok: true, stale: true });
        return;
      }
      resetSessionState(result.family);
      audit(result.family, "help_ended", { sessionId });
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/family/end") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      const sessionId = String(payload.sessionId || "").trim();
      if (!sessionId || sessionId !== result.family.sessionId) {
        sendJson(res, 200, { ok: true, stale: true });
        return;
      }
      resetSessionState(result.family);
      audit(result.family, "family_left", { sessionId, by: result.member.name });
      sendJson(res, 200, { ok: true, family: publicFamily(result.family, result.member, authToken) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/request") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      const now = Date.now();
      const elapsed = now - Number(result.family.lastControlRequestAtMs || 0);
      if (result.family.controlDecision === "pending" && elapsed < CONTROL_REQUEST_COOLDOWN_MS) {
        sendJson(res, 429, { error: "control request is too frequent", retryAfterMs: CONTROL_REQUEST_COOLDOWN_MS - elapsed });
        return;
      }
      result.family.lastControlRequestAtMs = now;
      result.family.controlRequested = true;
      result.family.controlAllowed = false;
      result.family.controlDecision = "pending";
      result.family.controlReason = "";
      result.family.controlUpdatedAt = new Date().toISOString();
      audit(result.family, "control_requested", { by: result.member.name });
      sendJson(res, 200, { ok: true, family: publicFamily(result.family) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/allow") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      result.family.controlAllowed = Boolean(payload.allowed);
      result.family.controlRequested = false;
      result.family.controlReason = String(payload.reason || "");
      result.family.controlDecision = result.family.controlAllowed
        ? "allowed"
        : (result.family.controlReason === "accessibility_not_enabled" ? "setup_required" : "denied");
      result.family.controlUpdatedAt = new Date().toISOString();
      audit(result.family, result.family.controlAllowed ? "control_allowed" : "control_denied", {
        by: result.member.name,
        reason: result.family.controlReason,
      });
      sendJson(res, 200, { ok: true, family: publicFamily(result.family) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/tap") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
      const now = Date.now();
      if (now - Number(result.family.lastControlActionAtMs || 0) < CONTROL_ACTION_COOLDOWN_MS) {
        sendJson(res, 429, { error: "control action is too frequent" });
        return;
      }
      result.family.lastControlActionAtMs = now;
      result.family.controlAction = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "tap",
        x: clamp01(payload.x, 0.5),
        y: clamp01(payload.y, 0.5),
        updatedAt: new Date().toISOString(),
        expiresAt: Date.now() + 3000,
      };
      audit(result.family, "control_tap", { x: result.family.controlAction.x, y: result.family.controlAction.y });
      sendJson(res, 200, { ok: true, action: result.family.controlAction });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/swipe") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
      const now = Date.now();
      if (now - Number(result.family.lastControlActionAtMs || 0) < CONTROL_ACTION_COOLDOWN_MS) {
        sendJson(res, 429, { error: "control action is too frequent" });
        return;
      }
      result.family.lastControlActionAtMs = now;
      result.family.controlAction = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "swipe",
        startX: clamp01(payload.startX, 0.5),
        startY: clamp01(payload.startY, 0.5),
        endX: clamp01(payload.endX, 0.5),
        endY: clamp01(payload.endY, 0.5),
        durationMs: Math.max(120, Math.min(1200, Number(payload.durationMs || 350))),
        updatedAt: new Date().toISOString(),
        expiresAt: Date.now() + 6000,
      };
      audit(result.family, "control_swipe", {
        startX: result.family.controlAction.startX,
        startY: result.family.controlAction.startY,
        endX: result.family.controlAction.endX,
        endY: result.family.controlAction.endY,
      });
      sendJson(res, 200, { ok: true, action: result.family.controlAction });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/global") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
      const now = Date.now();
      if (now - Number(result.family.lastControlActionAtMs || 0) < CONTROL_ACTION_COOLDOWN_MS) {
        sendJson(res, 429, { error: "control action is too frequent" });
        return;
      }
      result.family.lastControlActionAtMs = now;
      const action = String(payload.action || "").trim();
      if (!["home", "back", "recents"].includes(action)) {
        sendJson(res, 400, { error: "unsupported global action" });
        return;
      }
      result.family.controlAction = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "global",
        action,
        updatedAt: new Date().toISOString(),
        expiresAt: Date.now() + 6000,
      };
      audit(result.family, "control_global", { action });
      sendJson(res, 200, { ok: true, action: result.family.controlAction });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/control/action") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      if (result.family.controlAction && Date.now() > Number(result.family.controlAction.expiresAt || 0)) {
        result.family.controlAction = null;
      }
      const action = result.family.controlAction;
      result.family.controlAction = null;
      sendJson(res, 200, { action });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/webrtc/offer") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      if (String(payload.sessionId || "").trim() !== result.family.sessionId) {
        sendJson(res, 409, { error: "stale session" });
        return;
      }
      result.family.webrtc.offer = {
        type: String(payload.type || "offer"),
        sdp: String(payload.sdp || ""),
        updatedAt: new Date().toISOString(),
      };
      result.family.webrtc.answer = null;
      result.family.webrtc.elderIce = [];
      result.family.webrtc.familyIce = [];
      result.family.webrtc.updatedAt = result.family.webrtc.offer.updatedAt;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/webrtc/offer") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      sendJson(res, 200, { offer: result.family.webrtc.offer });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/webrtc/answer") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      if (String(payload.sessionId || "").trim() !== result.family.sessionId) {
        sendJson(res, 409, { error: "stale session" });
        return;
      }
      result.family.webrtc.answer = {
        type: String(payload.type || "answer"),
        sdp: String(payload.sdp || ""),
        updatedAt: new Date().toISOString(),
      };
      result.family.webrtc.updatedAt = result.family.webrtc.answer.updatedAt;
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/webrtc/answer") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      sendJson(res, 200, { answer: result.family.webrtc.answer });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/webrtc/ice") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const from = String(payload.from || "").trim();
      const role = from === "elder" ? "elder" : "family";
      const result = requireMember(res, pairCode, authToken, role);
      if (!result) return;
      if (role === "family" && !requireActiveHelper(res, result, authToken)) return;
      if (String(payload.sessionId || "").trim() !== result.family.sessionId) {
        sendJson(res, 409, { error: "stale session" });
        return;
      }
      const item = {
        sdpMid: String(payload.sdpMid || ""),
        sdpMLineIndex: Number(payload.sdpMLineIndex || 0),
        candidate: String(payload.candidate || ""),
        updatedAt: new Date().toISOString(),
      };
      if (role === "elder") {
        result.family.webrtc.elderIce.push(item);
      } else {
        result.family.webrtc.familyIce.push(item);
      }
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/webrtc/ice") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const role = String(url.searchParams.get("role") || "").trim();
      const from = String(url.searchParams.get("from") || "").trim();
      const since = Number(url.searchParams.get("since") || 0);
      const result = requireMember(res, pairCode, authToken, role === "elder" ? "elder" : "family");
      if (!result) return;
      if (role !== "elder" && !requireActiveHelper(res, result, authToken)) return;
      const source = from === "elder" ? result.family.webrtc.elderIce : result.family.webrtc.familyIce;
      sendJson(res, 200, { candidates: source.slice(since), next: source.length });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/frame") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const masked = url.searchParams.get("masked") === "1";
      const sessionId = String(url.searchParams.get("sessionId") || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const { family } = result;
      if (sessionId !== family.sessionId) {
        res.writeHead(409, { "Access-Control-Allow-Origin": "*" });
        res.end();
        return;
      }
      family.frame = await readBody(req);
      family.masked = masked;
      family.frameUpdatedAt = new Date().toISOString();
      family.updatedAt = family.frameUpdatedAt;
      scheduleSave();
      res.writeHead(204, { "Access-Control-Allow-Origin": "*" });
      res.end();
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/frame") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      const { family } = result;
      if (!family.frame) {
        res.writeHead(204, { "Access-Control-Allow-Origin": "*" });
        res.end();
        return;
      }
      res.writeHead(200, {
        "Content-Type": "image/jpeg",
        "Cache-Control": "no-store",
        "Content-Length": family.frame.length,
        "Access-Control-Allow-Origin": "*",
      });
      res.end(family.frame);
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/annotation") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!requireActiveHelper(res, result, authToken)) return;
      const sessionId = String(payload.sessionId || "").trim();
      if (!sessionId || sessionId !== result.family.sessionId) {
        sendJson(res, 409, { error: "stale session" });
        return;
      }
      result.family.annotation = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "circle",
        x: clamp01(payload.x, 0.5),
        y: clamp01(payload.y, 0.5),
        radius: Math.max(0.03, Math.min(0.18, Number(payload.radius || 0.08))),
        label: String(payload.label || "请点这里"),
        frameUpdatedAt: String(payload.frameUpdatedAt || ""),
        updatedAt: new Date().toISOString(),
        sessionId,
        expiresAt: Date.now() + 8000,
      };
      audit(result.family, "annotation_sent", { x: result.family.annotation.x, y: result.family.annotation.y });
      sendJson(res, 200, { ok: true, annotation: result.family.annotation });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/annotation") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      if (result.family.annotation && Date.now() > Number(result.family.annotation.expiresAt || 0)) {
        result.family.annotation = null;
      }
      sendJson(res, 200, { annotation: result.family.annotation });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/audit") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken);
      if (!result) return;
      sendJson(res, 200, { ok: true, audit: result.family.audit.slice(-50) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/crash") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken);
      if (!result) return;
      rememberCrash(result.family, payload, result.member);
      audit(result.family, "crash_reported", { role: result.member.role, message: String(payload.message || "").slice(0, 180) });
      sendJson(res, 200, { ok: true });
      return;
    }

    sendJson(res, 404, { error: "not found" });
  } catch (error) {
    sendJson(res, 500, { error: error.message });
  }
});

async function start() {
  await loadState();
  server.listen(port, host, () => {
    console.log(`Family Assist relay listening on http://${host}:${port}`);
    console.log(pgPool ? "Relay storage: PostgreSQL" : "Relay storage: local JSON fallback");
  });
}

async function shutdown(signal) {
  console.log(`Received ${signal}, saving relay state before exit.`);
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  await saveStateNow();
  if (pgPool) {
    await pgPool.end().catch(() => {});
  }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

start().catch((error) => {
  console.error(`Failed to start relay: ${error.stack || error.message}`);
  process.exit(1);
});
