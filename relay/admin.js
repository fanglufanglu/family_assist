const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const SESSION_TTL_MS = 8 * 60 * 60 * 1000;
const LOGIN_WINDOW_MS = 10 * 60 * 1000;
const LOGIN_MAX_ATTEMPTS = 8;

function createAdminConsole(options) {
  const adminDir = path.join(__dirname, "admin");
  const sessions = new Map();
  const loginAttempts = new Map();
  const auditFile = path.join(options.dataDir, "admin-audit.json");
  let adminAudit = loadAudit(auditFile);

  function handle(req, res, url) {
    if (!url.pathname.startsWith("/admin")) return false;
    if (req.method === "GET" && (url.pathname === "/admin" || url.pathname === "/admin/")) {
      return sendFile(res, path.join(adminDir, "index.html"), "text/html; charset=utf-8");
    }
    if (req.method === "GET" && url.pathname === "/admin/assets/brand.png") {
      return sendFile(res, path.join(__dirname, "..", "brand", "qinqing-bangbang-app-icon-1024.png"), "image/png");
    }
    if (req.method === "GET" && url.pathname.startsWith("/admin/assets/")) {
      const filename = path.basename(url.pathname);
      const contentType = filename.endsWith(".css") ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8";
      return sendFile(res, path.join(adminDir, filename), contentType);
    }
    if (!url.pathname.startsWith("/admin/api/")) {
      sendAdminJson(res, 404, { error: "not found" });
      return true;
    }
    routeApi(req, res, url).catch((error) => {
      console.error(`Admin API failed: ${error.stack || error.message}`);
      sendAdminJson(res, 500, { error: "admin service error" });
    });
    return true;
  }

  async function routeApi(req, res, url) {
    if (req.method === "POST" && url.pathname === "/admin/api/login") {
      const remote = options.requestAddress(req);
      if (rateLimited(loginAttempts, remote)) {
        sendAdminJson(res, 429, { error: "too many login attempts" });
        return;
      }
      const payload = JSON.parse((await options.readBody(req)).toString("utf8"));
      const username = String(payload.username || "").trim();
      const password = String(payload.password || "");
      if (!adminConfigured() || username !== adminUsername() || !verifyAdminPassword(password)) {
        appendAudit("login_failed", username || "unknown", req, {});
        sendAdminJson(res, 403, { error: "invalid credentials" });
        return;
      }
      loginAttempts.delete(remote);
      const id = randomToken();
      const csrf = randomToken();
      sessions.set(id, { username, csrf, createdAt: Date.now(), lastSeenAt: Date.now() });
      appendAudit("login_succeeded", username, req, {});
      const secure = String(req.headers["x-forwarded-proto"] || "") === "https";
      res.setHeader("Set-Cookie", `qbb_admin=${id}; HttpOnly; SameSite=Strict; Path=/admin; Max-Age=${SESSION_TTL_MS / 1000}${secure ? "; Secure" : ""}`);
      sendAdminJson(res, 200, { ok: true, user: { username, role: "super_admin" }, csrf });
      return;
    }

    const auth = requireAdmin(req, res);
    if (!auth) return;

    if (req.method === "POST") {
      const csrf = String(req.headers["x-admin-csrf"] || "");
      if (!csrf || !safeEqual(csrf, auth.session.csrf)) {
        sendAdminJson(res, 403, { error: "invalid csrf token" });
        return;
      }
    }

    if (req.method === "POST" && url.pathname === "/admin/api/logout") {
      sessions.delete(auth.id);
      appendAudit("logout", auth.session.username, req, {});
      res.setHeader("Set-Cookie", "qbb_admin=; HttpOnly; SameSite=Strict; Path=/admin; Max-Age=0");
      sendAdminJson(res, 200, { ok: true });
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/me") {
      sendAdminJson(res, 200, { ok: true, user: { username: auth.session.username, role: "super_admin" }, csrf: auth.session.csrf });
      return;
    }

    const snapshot = options.snapshot();
    if (req.method === "GET" && url.pathname === "/admin/api/dashboard") {
      sendAdminJson(res, 200, dashboard(snapshot));
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/users") {
      sendAdminJson(res, 200, paginate(filterUsers(snapshot.users, url.searchParams.get("q")), url));
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/families") {
      sendAdminJson(res, 200, paginate(filterRows(familyRows(snapshot), url.searchParams.get("q")), url));
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/sessions") {
      sendAdminJson(res, 200, paginate(filterRows(sessionRows(snapshot), url.searchParams.get("q")), url));
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/diagnostics") {
      sendAdminJson(res, 200, diagnostics(snapshot));
      return;
    }
    if (req.method === "GET" && url.pathname === "/admin/api/audit") {
      sendAdminJson(res, 200, { items: adminAudit.slice().reverse().slice(0, 300) });
      return;
    }
    const endMatch = url.pathname.match(/^\/admin\/api\/sessions\/([^/]+)\/end$/);
    if (req.method === "POST" && endMatch) {
      const payload = JSON.parse((await options.readBody(req)).toString("utf8"));
      const reason = String(payload.reason || "").trim();
      if (reason.length < 4 || reason.length > 200) {
        sendAdminJson(res, 400, { error: "reason is required" });
        return;
      }
      const result = options.endSession(decodeURIComponent(endMatch[1]), auth.session.username, reason);
      if (!result) {
        sendAdminJson(res, 404, { error: "active session not found" });
        return;
      }
      appendAudit("session_force_ended", auth.session.username, req, {
        sessionId: result.sessionId,
        familyId: result.familyId,
        reason,
      });
      sendAdminJson(res, 200, { ok: true });
      return;
    }
    sendAdminJson(res, 404, { error: "not found" });
  }

  function requireAdmin(req, res) {
    const now = Date.now();
    for (const [sessionId, candidate] of sessions.entries()) {
      if (now - candidate.lastSeenAt > SESSION_TTL_MS) sessions.delete(sessionId);
    }
    const cookies = parseCookies(req.headers.cookie || "");
    const id = cookies.qbb_admin || "";
    const session = sessions.get(id);
    if (!session || Date.now() - session.lastSeenAt > SESSION_TTL_MS) {
      sessions.delete(id);
      sendAdminJson(res, 401, { error: "admin login required" });
      return null;
    }
    session.lastSeenAt = Date.now();
    return { id, session };
  }

  function adminUsername() {
    return String(process.env.ADMIN_USERNAME || "admin").trim();
  }

  function adminConfigured() {
    const password = String(process.env.ADMIN_PASSWORD || "");
    const passwordHash = String(process.env.ADMIN_PASSWORD_HASH || "");
    return password.length >= 12 || passwordHash.startsWith("pbkdf2_sha256$");
  }

  function verifyAdminPassword(password) {
    const configuredHash = String(process.env.ADMIN_PASSWORD_HASH || "");
    if (configuredHash) return verifyPasswordHash(password, configuredHash);
    return safeEqual(password, String(process.env.ADMIN_PASSWORD || ""));
  }

  function appendAudit(type, actor, req, detail) {
    adminAudit.push({
      id: crypto.randomBytes(8).toString("hex"),
      type,
      actor,
      remote: options.requestAddress(req),
      detail,
      createdAt: new Date().toISOString(),
    });
    if (adminAudit.length > 1000) adminAudit = adminAudit.slice(-1000);
    try {
      fs.mkdirSync(path.dirname(auditFile), { recursive: true, mode: 0o700 });
      fs.writeFileSync(auditFile, JSON.stringify(adminAudit, null, 2), { mode: 0o600 });
    } catch (error) {
      console.error(`Failed to save admin audit: ${error.message}`);
    }
  }

  return { handle };
}

function dashboard(snapshot) {
  const families = familyRows(snapshot);
  const sessions = sessionRows(snapshot);
  const active = sessions.filter((item) => item.status === "active");
  const now = Date.now();
  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  const recentSessions = sessions.filter((item) => Date.parse(item.startedAt || 0) >= now - 7 * 86400000);
  const started = recentSessions.length;
  const ended = recentSessions.filter((item) => item.status === "ended").length;
  const timedOut = recentSessions.filter((item) => item.endReason === "elder_disconnected").length;
  const signaling = recentSessions.filter((item) => item.webrtcSignaled).length;
  return {
    generatedAt: new Date().toISOString(),
    metrics: {
      users: snapshot.users.length,
      newUsersToday: snapshot.users.filter((item) => Date.parse(item.createdAt || 0) >= todayStart.getTime()).length,
      elders: snapshot.users.filter((item) => item.appRole === "elder").length,
      relatives: snapshot.users.filter((item) => item.appRole === "family").length,
      families: families.length,
      relationships: families.reduce((sum, item) => sum + item.relativeCount, 0),
      activeSessions: active.length,
      crashes7d: snapshot.crashes.filter((item) => Date.parse(item.createdAt || 0) >= now - 7 * 86400000).length,
    },
    funnel: [
      { label: "发起协助", value: started },
      { label: "共享开始", value: started },
      { label: "WebRTC 信令", value: signaling },
      { label: "正常结束", value: ended - timedOut },
    ],
    quality: {
      sessions7d: started,
      completionRate: started ? Math.round((ended / started) * 100) : 0,
      timeoutRate: started ? Math.round((timedOut / started) * 100) : 0,
      signalingRate: started ? Math.round((signaling / started) * 100) : 0,
    },
    activeSessions: active,
    alerts: buildAlerts(snapshot, active),
  };
}

function familyRows(snapshot) {
  return snapshot.families.map((entry) => {
    const family = entry.family;
    const members = [...family.members.values()];
    const elder = members.find((member) => member.role === "elder");
    const relatives = members.filter((member) => member.role === "family");
    return {
      id: publicId(entry.pairCode),
      pairCode: maskCode(entry.pairCode),
      elderName: family.elderName || (elder && elder.name) || "长辈",
      relativeCount: relatives.length,
      relativeNames: relatives.map((member) => member.name || "家属").join("、"),
      phase: assistPhase(family),
      active: Boolean(family.active),
      helperName: family.activeHelperName || "",
      updatedAt: family.updatedAt || "",
      lastEndReason: family.lastEndReason || "",
    };
  }).sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
}

function sessionRows(snapshot) {
  const rows = [];
  for (const entry of snapshot.families) {
    const family = entry.family;
    const starts = new Map();
    for (const event of family.audit || []) {
      const sessionId = event.detail && event.detail.sessionId;
      if (event.type === "help_started" && sessionId) {
        starts.set(sessionId, {
          id: sessionId,
          familyId: publicId(entry.pairCode),
          elderName: event.detail.elderName || family.elderName || "长辈",
          helperName: event.detail.targetHelperName || "家属",
          status: "active",
          phase: "active",
          startedAt: event.createdAt,
          endedAt: "",
          endReason: "",
          webrtcSignaled: false,
          controlRequested: false,
        });
      }
      const row = sessionId ? starts.get(sessionId) : null;
      if (!row) continue;
      if (event.type === "webrtc_answer_created") row.webrtcSignaled = true;
      if (event.type === "control_requested") row.controlRequested = true;
      if (["help_ended", "family_left", "help_timed_out", "admin_session_ended"].includes(event.type)) {
        row.status = "ended";
        row.phase = "ended";
        row.endedAt = event.createdAt;
        row.endReason = event.type === "help_timed_out" ? "elder_disconnected"
          : event.type === "family_left" ? "family_ended"
            : event.type === "admin_session_ended" ? "admin_ended" : "elder_ended";
      }
    }
    for (const row of starts.values()) rows.push(row);
    if (family.active && family.sessionId && !starts.has(family.sessionId)) {
      rows.push({
        id: family.sessionId,
        familyId: publicId(entry.pairCode),
        elderName: family.elderName || "长辈",
        helperName: family.activeHelperName || "家属",
        status: "active",
        phase: assistPhase(family),
        startedAt: family.updatedAt || "",
        endedAt: "",
        endReason: "",
        webrtcSignaled: Boolean(family.webrtc && family.webrtc.answer),
        controlRequested: Boolean(family.controlRequested || family.controlAllowed),
      });
    }
  }
  return rows.sort((a, b) => String(b.startedAt).localeCompare(String(a.startedAt)));
}

function filterUsers(users, query) {
  const q = String(query || "").trim().toLowerCase();
  return users.map((item) => ({
    id: item.id || publicId(item.phone),
    name: item.name || "用户",
    phone: maskPhone(item.phone),
    role: item.appRole || "unselected",
    createdAt: item.createdAt || "",
    lastLoginAt: item.lastLoginAt || "",
  })).filter((item) => !q || JSON.stringify(item).toLowerCase().includes(q))
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
}

function diagnostics(snapshot) {
  return {
    health: snapshot.health,
    alerts: buildAlerts(snapshot, sessionRows(snapshot).filter((item) => item.status === "active")),
    crashes: snapshot.crashes.slice().sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, 200),
    recentEvents: snapshot.businessAudit.slice().sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, 200),
  };
}

function buildAlerts(snapshot, active) {
  const alerts = [];
  if (!snapshot.health.postgres) alerts.push({ severity: "warning", title: "当前使用本地存储", detail: "正式环境建议启用 PostgreSQL。" });
  if (!snapshot.health.turnConfigured) alerts.push({ severity: "critical", title: "TURN 未配置", detail: "跨运营商网络下 WebRTC 连接可能失败。" });
  for (const session of active) {
    const age = Date.now() - Date.parse(session.startedAt || Date.now());
    if (age > 2 * 60 * 60 * 1000) alerts.push({ severity: "warning", title: "长时间协助会话", detail: `${session.elderName} 的协助已持续超过 2 小时。` });
  }
  const recentCrashes = snapshot.crashes.filter((item) => Date.parse(item.createdAt || 0) >= Date.now() - 86400000);
  if (recentCrashes.length) alerts.push({ severity: "warning", title: "近 24 小时出现崩溃", detail: `共收到 ${recentCrashes.length} 条崩溃报告。` });
  return alerts;
}

function assistPhase(family) {
  if (family.active) return "active";
  if (family.pendingHelpInvitation && family.pendingHelpInvitation.status === "accepted") return "waiting_screen_permission";
  if (family.pendingHelpInvitation && family.pendingHelpInvitation.status === "pending") return "waiting_family_acceptance";
  if (family.pendingFamilyAssistRequest && family.pendingFamilyAssistRequest.status === "pending") return "waiting_elder_acceptance";
  return "idle";
}

function paginate(items, url) {
  const page = Math.max(1, Number(url.searchParams.get("page") || 1));
  const pageSize = Math.min(100, Math.max(10, Number(url.searchParams.get("pageSize") || 30)));
  return { items: items.slice((page - 1) * pageSize, page * pageSize), total: items.length, page, pageSize };
}

function filterRows(items, query) {
  const q = String(query || "").trim().toLowerCase();
  return q ? items.filter((item) => JSON.stringify(item).toLowerCase().includes(q)) : items;
}

function maskPhone(value) {
  const phone = String(value || "");
  if (phone.length < 7) return "***";
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

function maskCode(value) {
  const code = String(value || "");
  return code.length > 6 ? `${code.slice(0, 3)}...${code.slice(-3)}` : "******";
}

function publicId(value) {
  return crypto.createHash("sha256").update(String(value || "")).digest("hex").slice(0, 12);
}

function randomToken() {
  return crypto.randomBytes(32).toString("base64url");
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ""));
  const b = Buffer.from(String(right || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function verifyPasswordHash(password, stored) {
  const parts = String(stored || "").split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2_sha256") return false;
  const actual = crypto.pbkdf2Sync(String(password), parts[2], Number(parts[1]), 32, "sha256").toString("base64url");
  return safeEqual(actual, parts[3]);
}

function parseCookies(header) {
  return String(header || "").split(";").reduce((result, item) => {
    const index = item.indexOf("=");
    if (index > 0) result[item.slice(0, index).trim()] = item.slice(index + 1).trim();
    return result;
  }, {});
}

function rateLimited(attempts, key) {
  const now = Date.now();
  const previous = attempts.get(key);
  const current = !previous || now - previous.startedAt > LOGIN_WINDOW_MS ? { startedAt: now, count: 0 } : previous;
  current.count += 1;
  attempts.set(key, current);
  return current.count > LOGIN_MAX_ATTEMPTS;
}

function loadAudit(filename) {
  try {
    const parsed = JSON.parse(fs.readFileSync(filename, "utf8"));
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

function sendFile(res, filename, contentType) {
  try {
    const body = fs.readFileSync(filename);
    res.writeHead(200, {
      "Content-Type": contentType,
      "Content-Length": body.length,
      "Cache-Control": "no-cache",
      "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
    });
    res.end(body);
  } catch (_) {
    sendAdminJson(res, 404, { error: "not found" });
  }
  return true;
}

function sendAdminJson(res, status, payload) {
  if (res.writableEnded) return;
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  });
  res.end(body);
}

module.exports = { createAdminConsole };
