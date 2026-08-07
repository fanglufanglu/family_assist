const http = require("http");
const fs = require("fs");
const path = require("path");
const { URL } = require("url");
const crypto = require("crypto");

const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || "0.0.0.0";
const families = new Map();
const MAX_FAMILY_MEMBERS = 5;
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 8 * 1024 * 1024);
const DATA_DIR = process.env.RELAY_DATA_DIR || path.join(__dirname, "..", ".data");
const STATE_FILE = process.env.RELAY_STATE_FILE || path.join(DATA_DIR, "relay-state.json");
const SAVE_DEBOUNCE_MS = 250;
const CONTROL_REQUEST_COOLDOWN_MS = 15_000;
const CONTROL_ACTION_COOLDOWN_MS = 120;
let saveTimer = null;

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
  });
  res.end(body);
}

function logRequest(req, res, startedAt) {
  const forwardedFor = req.headers["x-forwarded-for"] || "";
  const remote = forwardedFor || req.socket.remoteAddress || "";
  const userAgent = req.headers["user-agent"] || "";
  const elapsedMs = Date.now() - startedAt;
  console.log(
    `${new Date().toISOString()} ${req.method} ${req.url} ${res.statusCode} ${elapsedMs}ms remote=${remote} ua="${userAgent}"`
  );
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
  return crypto.randomBytes(24).toString("base64url");
}

function makeInviteCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function makeSessionId() {
  return crypto.randomBytes(12).toString("hex");
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

function publicFamily(family, member, authToken) {
  const relatives = familyMembers(family);
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
  family.frame = null;
  family.annotation = null;
  family.controlAction = null;
  family.active = false;
  family.sessionId = "";
  family.activeHelperToken = "";
  family.activeHelperName = "";
  family.webrtc = {
    offer: null,
    answer: null,
    elderIce: [],
    familyIce: [],
    updatedAt: "",
  };
  return family;
}

function loadState() {
  try {
    if (!fs.existsSync(STATE_FILE)) return;
    const parsed = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
    const entries = Array.isArray(parsed.families) ? parsed.families : [];
    for (const [pairCode, rawFamily] of entries) {
      if (pairCode) families.set(pairCode, hydrateFamily(rawFamily));
    }
    console.log(`Loaded ${families.size} persisted families from ${STATE_FILE}`);
  } catch (error) {
    console.error(`Failed to load relay state: ${error.message}`);
  }
}

function saveStateNow() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
    const payload = {
      version: 1,
      savedAt: new Date().toISOString(),
      families: [...families.entries()].map(([pairCode, family]) => [pairCode, serializeFamily(family)]),
    };
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

loadState();

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

    if (req.method === "POST" && url.pathname === "/api/invite") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      if (!pairCode) {
        sendJson(res, 400, { error: "pairCode is required" });
        return;
      }
      const family = familyFor(pairCode);
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
      sendJson(res, 200, { ok: true, member: result.member, family: publicFamily(result.family, result.member, authToken) });
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
      family.activeHelperToken = "";
      family.activeHelperName = "";
      family.webrtc = {
        offer: null,
        answer: null,
        elderIce: [],
        familyIce: [],
        updatedAt: family.updatedAt,
      };
      audit(family, "help_started", { sessionId: family.sessionId, elderName: family.elderName });
      sendJson(res, 200, { ok: true, sessionId: family.sessionId, family: publicFamily(family, result.member, authToken) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/help") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (result.family.active && !result.family.activeHelperToken) {
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

server.listen(port, host, () => {
  console.log(`Family Assist relay listening on http://${host}:${port}`);
});

function shutdown(signal) {
  console.log(`Received ${signal}, saving relay state before exit.`);
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  saveStateNow();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
