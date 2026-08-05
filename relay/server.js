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

function familyFor(pairCode) {
  if (!families.has(pairCode)) {
    families.set(pairCode, {
      inviteCode: "",
      inviteExpiresAt: 0,
      members: new Map(),
      active: false,
      elderName: "",
      deviceName: "",
      updatedAt: "",
      frame: null,
      frameUpdatedAt: "",
      masked: false,
      annotation: null,
      controlRequested: false,
      controlAllowed: false,
      controlUpdatedAt: "",
      controlAction: null,
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

function publicFamily(family) {
  return {
    active: family.active,
    elderName: family.elderName,
    deviceName: family.deviceName,
    updatedAt: family.updatedAt,
    frameUpdatedAt: family.frameUpdatedAt,
    masked: family.masked,
    controlRequested: family.controlRequested,
    controlAllowed: family.controlAllowed,
    controlUpdatedAt: family.controlUpdatedAt,
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

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && url.pathname === "/health") {
      sendJson(res, 200, { ok: true });
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
      family.inviteCode = makeInviteCode();
      family.inviteExpiresAt = Date.now() + 10 * 60 * 1000;
      family.elderName = String(payload.elderName || "长辈");
      family.deviceName = String(payload.deviceName || "");
      family.updatedAt = new Date().toISOString();
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
      family.active = true;
      family.elderName = String(payload.elderName || family.elderName || "长辈");
      family.deviceName = String(payload.deviceName || "");
      family.masked = Boolean(payload.masked);
      family.updatedAt = new Date().toISOString();
      sendJson(res, 200, { ok: true, family: publicFamily(family) });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/help") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const result = requireMember(res, pairCode, authToken, "family");
      if (!result) return;
      sendJson(res, 200, publicFamily(result.family));
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/end") {
      const payload = JSON.parse((await readBody(req)).toString("utf8"));
      const pairCode = String(payload.pairCode || "").trim();
      const authToken = String(payload.authToken || "").trim();
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      result.family.active = false;
      result.family.annotation = null;
      result.family.controlAllowed = false;
      result.family.controlRequested = false;
      result.family.controlAction = null;
      result.family.updatedAt = new Date().toISOString();
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

    if (req.method === "POST" && url.pathname === "/api/frame") {
      const pairCode = String(url.searchParams.get("pairCode") || "").trim();
      const authToken = String(url.searchParams.get("authToken") || "").trim();
      const masked = url.searchParams.get("masked") === "1";
      const result = requireMember(res, pairCode, authToken, "elder");
      if (!result) return;
      const { family } = result;
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
        expiresAt: Date.now() + 3500,
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

    sendJson(res, 404, { error: "not found" });
  } catch (error) {
    sendJson(res, 500, { error: error.message });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Family Assist relay listening on http://0.0.0.0:${port}`);
});
