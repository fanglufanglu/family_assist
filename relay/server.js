const http = require("http");
const { URL } = require("url");
const crypto = require("crypto");

const port = Number(process.env.PORT || 8787);
const families = new Map();

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
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
    families.set(pairCode, {
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
      controlUpdatedAt: "",
      controlAction: null,
      audit: [],
      webrtc: {
        offer: null,
        answer: null,
        elderIce: [],
        familyIce: [],
        updatedAt: "",
      },
    });
  }
  return families.get(pairCode);
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

function publicFamily(family) {
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
    controlUpdatedAt: family.controlUpdatedAt,
    invitePending: Boolean(family.inviteCode) && Date.now() <= family.inviteExpiresAt,
    inviteExpiresAt: family.inviteExpiresAt,
    memberCount: family.members.size,
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

    if (req.method === "POST" && url.pathname === "/api/invite") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      if (!pairCode) {
        sendJson(res, 400, { error: "pairCode is required" });
        return;
      }
      const family = familyFor(pairCode);
      const elderToken = makeToken();
      family.members.clear();
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
      family.controlAction = null;
      family.audit = [];
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
      sendJson(res, 200, {
        ok: true,
        inviteCode: family.inviteCode,
        inviteExpiresAt: family.inviteExpiresAt,
        authToken: elderToken,
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/bind") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const inviteCode = String(payload.inviteCode || "").trim();
      const family = pairCode ? familyFor(pairCode) : null;
      if (!family || !inviteCode || inviteCode !== family.inviteCode || Date.now() > family.inviteExpiresAt) {
        sendJson(res, 403, { error: "invalid or expired invite code" });
        return;
      }
      const authToken = makeToken();
      family.members.set(authToken, {
        role: "family",
        name: String(payload.familyName || "家属"),
        deviceId: String(payload.deviceId || ""),
        createdAt: new Date().toISOString(),
      });
      family.inviteCode = "";
      family.inviteExpiresAt = 0;
      family.updatedAt = new Date().toISOString();
      sendJson(res, 200, { ok: true, authToken });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/bind/status") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken);
      if (!result) return;
      sendJson(res, 200, { ok: true, member: result.member, family: publicFamily(result.family) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/help") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const { family } = result;
      family.sessionId = makeSessionId();
      family.active = true;
      family.elderName = String(payload.elderName || family.elderName || "长辈");
      family.deviceName = String(payload.deviceName || "");
      family.masked = Boolean(payload.masked);
      family.updatedAt = new Date().toISOString();
      family.lastFamilySeenAtMs = 0;
      family.lastFamilySeenAt = "";
      family.webrtc = {
        offer: null,
        answer: null,
        elderIce: [],
        familyIce: [],
        updatedAt: family.updatedAt,
      };
      audit(family, "help_started", { sessionId: family.sessionId, elderName: family.elderName });
      sendJson(res, 200, { ok: true, sessionId: family.sessionId, family: publicFamily(family) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/help") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      result.family.lastFamilySeenAtMs = Date.now();
      result.family.lastFamilySeenAt = new Date(result.family.lastFamilySeenAtMs).toISOString();
      sendJson(res, 200, publicFamily(result.family));
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/end") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const sessionId = String(payload.sessionId || "").trim();
      if (sessionId && result.family.sessionId && sessionId !== result.family.sessionId) {
        sendJson(res, 200, { ok: true, stale: true });
        return;
      }
      result.family.active = false;
      result.family.sessionId = "";
      result.family.annotation = null;
      result.family.controlAllowed = false;
      result.family.controlRequested = false;
      result.family.controlAction = null;
      result.family.lastFamilySeenAtMs = 0;
      result.family.lastFamilySeenAt = "";
      result.family.updatedAt = new Date().toISOString();
      audit(result.family, "help_ended", { sessionId });
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/request") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      result.family.controlRequested = true;
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
      result.family.controlUpdatedAt = new Date().toISOString();
      audit(result.family, result.family.controlAllowed ? "control_allowed" : "control_denied", { by: result.member.name });
      sendJson(res, 200, { ok: true, family: publicFamily(result.family) });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/control/tap") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
      result.family.controlAction = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "tap",
        x: Number(payload.x || 0),
        y: Number(payload.y || 0),
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
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
      result.family.controlAction = {
        id: crypto.randomBytes(8).toString("hex"),
        type: "swipe",
        startX: Number(payload.startX || 0.5),
        startY: Number(payload.startY || 0.5),
        endX: Number(payload.endX || 0.5),
        endY: Number(payload.endY || 0.5),
        durationMs: Number(payload.durationMs || 350),
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
      if (!result.family.controlAllowed) {
        sendJson(res, 403, { error: "control is not allowed" });
        return;
      }
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
      sendJson(res, 200, { offer: result.family.webrtc.offer });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/webrtc/answer") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
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
      res.writeHead(204, { "Access-Control-Allow-Origin": "*" });
      res.end();
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/frame") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
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
      result.family.annotation = {
        type: "circle",
        x: Number(payload.x || 0),
        y: Number(payload.y || 0),
        radius: Number(payload.radius || 0.08),
        label: String(payload.label || "请点这里"),
        frameUpdatedAt: String(payload.frameUpdatedAt || ""),
        updatedAt: new Date().toISOString(),
        expiresAt: Date.now() + 6500,
      };
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

    sendJson(res, 404, { error: "not found" });
  } catch (error) {
    sendJson(res, 500, { error: error.message });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Family Assist relay listening on http://0.0.0.0:${port}`);
});
