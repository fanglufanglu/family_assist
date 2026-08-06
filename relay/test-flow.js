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
  const first = await post("/api/bind", { pairCode, inviteCode, familyName: "女儿", deviceId: "family-1" });
  const second = await post("/api/bind", { pairCode, inviteCode, familyName: "儿子", deviceId: "family-2" });
  assert(first.status === 200 && second.status === 200, "one invite should bind multiple relatives");
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
  const rightEnd = await post("/api/family/end", { pairCode, authToken: first.body.authToken, sessionId });
  assert(rightEnd.status === 200, "active relative should end the session");

  const nextHelp = await post("/api/help", { pairCode, authToken: elderToken, elderName: "妈妈" });
  assert(nextHelp.status === 200 && nextHelp.body.sessionId !== sessionId, "elder should start a fresh second session");
  console.log("Relay flow regression passed.");
}

run()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => relay.kill("SIGTERM"));
