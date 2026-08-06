const { spawn } = require("child_process");

const port = 8796;
const baseUrl = `http://127.0.0.1:${port}`;
const relay = spawn(process.execPath, ["server.js"], {
  cwd: __dirname,
  env: { ...process.env, PORT: String(port) },
  stdio: ["ignore", "pipe", "inherit"],
});

function assert(value, message) {
  if (!value) throw new Error(message);
}

async function post(path, payload) {
  const response = await fetch(baseUrl + path, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  let body = {};
  try {
    body = await response.json();
  } catch (_) {
  }
  return { status: response.status, body };
}

async function get(path) {
  const response = await fetch(baseUrl + path);
  return { status: response.status, body: await response.json() };
}

async function waitUntilReady() {
  for (let i = 0; i < 30; i += 1) {
    try {
      const health = await get("/health");
      if (health.status === 200) return;
    } catch (_) {
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("relay did not start");
}

async function run() {
  await waitUntilReady();
  const pairCode = "regression001";
  const invite = await post("/api/invite", { pairCode, elderName: "妈妈", deviceId: "elder-1" });
  assert(invite.status === 200, "elder should create an invite");

  const elderToken = invite.body.authToken;
  const inviteCode = invite.body.inviteCode;
  const first = await post("/api/bind", { pairCode: "old-local-code", inviteCode, familyName: "女儿", deviceId: "family-1" });
  const second = await post("/api/bind", { pairCode, inviteCode, familyName: "儿子", deviceId: "family-2" });
  assert(first.status === 200 && second.status === 200, "one invite should bind multiple relatives");
  assert(first.body.pairCode === pairCode, "binding should return the elder-specific pair code");
  assert(second.body.familyMemberCount === 2, "bound relative count should be accurate");
  await post("/api/bind", { pairCode, inviteCode, familyName: "家属3", deviceId: "family-3" });
  await post("/api/bind", { pairCode, inviteCode, familyName: "家属4", deviceId: "family-4" });
  await post("/api/bind", { pairCode, inviteCode, familyName: "家属5", deviceId: "family-5" });
  const overLimit = await post("/api/bind", { pairCode, inviteCode, familyName: "家属6", deviceId: "family-6" });
  assert(overLimit.status === 409, "family binding should enforce the member limit");

  const help = await post("/api/help", { pairCode, authToken: elderToken, elderName: "妈妈" });
  assert(help.status === 200, "elder should start assistance");
  const sessionId = help.body.sessionId;

  const firstJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const secondJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${second.body.authToken}`);
  assert(firstJoin.body.helperIsCurrent, "first relative should own the active session");
  assert(!secondJoin.body.helperIsCurrent, "second relative should be shown as occupied");

  const wrongEnd = await post("/api/family/end", { pairCode, authToken: second.body.authToken, sessionId });
  assert(wrongEnd.status === 409, "non-active relative must not end the session");

  const controlRequest = await post("/api/control/request", { pairCode, authToken: first.body.authToken });
  assert(controlRequest.status === 200 && controlRequest.body.family.controlRequested, "control request should be visible to elder");
  const controlAllow = await post("/api/control/allow", { pairCode, authToken: elderToken, allowed: true });
  assert(controlAllow.status === 200 && controlAllow.body.family.controlAllowed, "control approval should be visible to family");
  const approvedState = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(approvedState.body.controlAllowed && !approvedState.body.controlRequested, "approved control state should synchronize");

  const rightEnd = await post("/api/family/end", { pairCode, authToken: first.body.authToken, sessionId });
  assert(rightEnd.status === 200, "active relative should end the session");
  const endedState = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(!endedState.body.active && !endedState.body.controlAllowed, "ending should clear active and control state");

  const nextHelp = await post("/api/help", { pairCode, authToken: elderToken, elderName: "妈妈" });
  assert(nextHelp.status === 200 && nextHelp.body.sessionId !== sessionId, "elder should start a fresh second session");
  await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const offer = await post("/api/webrtc/offer", {
    pairCode, authToken: elderToken, sessionId: nextHelp.body.sessionId, type: "offer", sdp: "offer-sdp",
  });
  assert(offer.status === 200, "elder WebRTC offer should be accepted");
  const relayedOffer = await get(`/api/webrtc/offer?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(relayedOffer.body.offer.sdp === "offer-sdp", "family should receive the current offer");
  const answer = await post("/api/webrtc/answer", {
    pairCode, authToken: first.body.authToken, sessionId: nextHelp.body.sessionId, type: "answer", sdp: "answer-sdp",
  });
  assert(answer.status === 200, "family WebRTC answer should be accepted");
  const relayedAnswer = await get(`/api/webrtc/answer?pairCode=${pairCode}&authToken=${elderToken}`);
  assert(relayedAnswer.body.answer.sdp === "answer-sdp", "elder should receive the current answer");
  const staleEnd = await post("/api/end", { pairCode, authToken: elderToken, sessionId });
  assert(staleEnd.body.stale, "an old session must not end a newer session");
  const stillActive = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(stillActive.body.active && stillActive.body.sessionId === nextHelp.body.sessionId, "new session should survive stale end requests");
  const finalEnd = await post("/api/end", { pairCode, authToken: elderToken, sessionId: nextHelp.body.sessionId });
  assert(finalEnd.status === 200 && !finalEnd.body.stale, "elder should end the current session");
  const unbind = await post("/api/unbind", { pairCode, authToken: first.body.authToken });
  assert(unbind.status === 200, "family should be able to unbind after assistance ends");
  const elderAfterUnbind = await get(`/api/bind/status?pairCode=${pairCode}&authToken=${elderToken}`);
  assert(elderAfterUnbind.body.family.familyMemberCount === 4, "unbind should update the relative count");
  console.log("Relay flow regression passed.");
}

run()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => relay.kill("SIGTERM"));
