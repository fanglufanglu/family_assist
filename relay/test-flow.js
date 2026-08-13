const { spawn } = require("child_process");

const port = 8796;
const baseUrl = `http://127.0.0.1:${port}`;
const relay = spawn(process.execPath, ["server.js"], {
  cwd: __dirname,
  env: {
    ...process.env,
    HOST: "127.0.0.1",
    PORT: String(port),
    RELAY_DATA_DIR: `/tmp/family-assist-relay-test-${process.pid}`,
    RESET_CODE_EXPOSED: "true",
    ELDER_HEARTBEAT_TIMEOUT_MS: "2500",
  },
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

async function startAcceptedHelp({ pairCode, elderToken, familyToken, targetHelperRef, elderName = "妈妈" }) {
  const invitation = await post("/api/help/invite", {
    pairCode,
    authToken: elderToken,
    elderName,
    targetHelperRef,
  });
  assert(invitation.status === 200 && invitation.body.invitation.id,
    "elder should create a targeted help invitation");
  const accepted = await post("/api/help/invite/respond", {
    pairCode,
    authToken: familyToken,
    invitationId: invitation.body.invitation.id,
    accepted: true,
  });
  assert(accepted.status === 200, "selected relative should accept the help invitation");
  return post("/api/help", {
    pairCode,
    authToken: elderToken,
    elderName,
    targetHelperRef,
    helpInvitationId: invitation.body.invitation.id,
  });
}

async function run() {
  await waitUntilReady();
  const pairCode = "regression001";
  const elderRegister = await post("/api/account/register", { phone: "13800000001", password: "elderPass123", name: "妈妈" });
  const familyRegister = await post("/api/account/register", { phone: "13800000002", password: "familyPass123", name: "女儿" });
  assert(elderRegister.status === 200 && elderRegister.body.accountToken, "elder account should register");
  assert(familyRegister.status === 200 && familyRegister.body.accountToken, "family account should register");
  const elderAccount = await post("/api/account/login", { phone: "13800000001", password: "elderPass123", name: "妈妈" });
  const familyAccount = await post("/api/account/login", { phone: "13800000002", password: "familyPass123", name: "女儿" });
  assert(elderAccount.status === 200 && elderAccount.body.accountToken, "elder account should log in with password");
  assert(familyAccount.status === 200 && familyAccount.body.accountToken, "family account should log in with password");
  assert(elderAccount.body.user.appRole === "" && familyAccount.body.user.appRole === "",
    "new accounts should choose an app role after login");
  const elderRole = await post("/api/account/role", {
    accountToken: elderAccount.body.accountToken,
    appRole: "elder",
  });
  const familyRole = await post("/api/account/role", {
    accountToken: familyAccount.body.accountToken,
    appRole: "family",
  });
  assert(elderRole.status === 200 && elderRole.body.user.appRole === "elder",
    "elder role should persist on the account");
  assert(familyRole.status === 200 && familyRole.body.user.appRole === "family",
    "family role should persist on the account");

  const resetRegister = await post("/api/account/register", { phone: "13800000003", password: "beforeReset123", name: "测试账号" });
  assert(resetRegister.status === 200, "reset test account should register");
  const resetRequest = await post("/api/account/password/reset/request", { phone: "13800000003" });
  assert(resetRequest.status === 200 && /^\d{6}$/.test(resetRequest.body.debugCode), "reset request should issue a test code");
  const resetConfirm = await post("/api/account/password/reset/confirm", {
    phone: "13800000003",
    code: resetRequest.body.debugCode,
    password: "afterReset123",
  });
  assert(resetConfirm.status === 200 && resetConfirm.body.accountToken !== resetRegister.body.accountToken,
    "password reset should rotate the account token");
  const oldLogin = await post("/api/account/login", { phone: "13800000003", password: "beforeReset123" });
  const newLogin = await post("/api/account/login", { phone: "13800000003", password: "afterReset123" });
  assert(oldLogin.status === 403 && newLogin.status === 200, "only the new password should work after reset");
  const deleteAccount = await post("/api/account/delete", {
    accountToken: newLogin.body.accountToken,
    password: "afterReset123",
  });
  const deletedLogin = await post("/api/account/login", { phone: "13800000003", password: "afterReset123" });
  assert(deleteAccount.status === 200 && deletedLogin.status === 403, "deleted account must not be able to log in");

  const securePairCode = "secure-regression001";
  const secureInvite = await post("/api/invite", {
    pairCode: securePairCode,
    elderName: "妈妈",
    deviceId: "secure-elder-1",
    accountToken: elderAccount.body.accountToken,
  });
  assert(secureInvite.status === 200, "logged-in elder should create a secure invite");
  const secureBind = await post("/api/bind", {
    pairCode: securePairCode,
    inviteCode: secureInvite.body.inviteCode,
    familyName: "女儿",
    deviceId: "secure-family-1",
    accountToken: familyAccount.body.accountToken,
  });
  assert(secureBind.status === 200 && secureBind.body.pendingApproval, "logged-in family binding should wait for elder approval");
  const secureStatus = await get(`/api/bind/status?pairCode=${securePairCode}&authToken=${secureInvite.body.authToken}`);
  const requestId = secureStatus.body.family.pendingBindRequests[0].id;
  const secureConfirm = await post("/api/bind/confirm", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    requestId,
    approved: true,
  });
  assert(secureConfirm.status === 200 && secureConfirm.body.approved, "elder should approve the family binding");
  const pendingDone = await get(`/api/bind/pending?pairCode=${securePairCode}&pendingToken=${secureBind.body.pendingToken}&accountToken=${familyAccount.body.accountToken}`);
  assert(pendingDone.status === 200 && pendingDone.body.approved && pendingDone.body.authToken, "family should receive auth token after approval");
  const rejectedRegister = await post("/api/account/register", {
    phone: "13800000004", password: "rejectedPass123", name: "儿子",
  });
  await post("/api/account/role", { accountToken: rejectedRegister.body.accountToken, appRole: "family" });
  const rejectedBind = await post("/api/bind", {
    pairCode: securePairCode,
    inviteCode: secureInvite.body.inviteCode,
    familyName: "儿子",
    deviceId: "secure-family-rejected",
    accountToken: rejectedRegister.body.accountToken,
  });
  const rejectedStatus = await get(`/api/bind/status?pairCode=${securePairCode}&authToken=${secureInvite.body.authToken}`);
  const rejectedRequest = rejectedStatus.body.family.pendingBindRequests
    .find((item) => item.requesterName === "儿子");
  await post("/api/bind/confirm", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    requestId: rejectedRequest.id,
    approved: false,
  });
  const rejectedResult = await get(`/api/bind/pending?pairCode=${securePairCode}`
    + `&pendingToken=${rejectedBind.body.pendingToken}&accountToken=${rejectedRegister.body.accountToken}`);
  assert(rejectedResult.status === 200 && rejectedResult.body.rejected,
    "family should receive an explicit binding rejection result");
  const relativesStatus = await get(`/api/bind/status?pairCode=${securePairCode}&authToken=${secureInvite.body.authToken}`);
  assert(relativesStatus.body.family.familyMembers.length === 1
    && relativesStatus.body.family.familyMembers[0].name === "女儿"
    && relativesStatus.body.family.familyMembers[0].ref,
  "elder should receive a safe list of bound relatives");
  assert(Array.isArray(secureInvite.body.familyMembers) && secureInvite.body.familyMembers.length === 0,
    "new invite should expose the elder's baseline family list");
  const cancellableHelpInvite = await post("/api/help/invite", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: relativesStatus.body.family.familyMembers[0].ref,
  });
  const cancelledHelpInvite = await post("/api/help/invite/cancel", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    invitationId: cancellableHelpInvite.body.invitation.id,
  });
  assert(cancelledHelpInvite.status === 200 && cancelledHelpInvite.body.invitation.status === "cancelled",
    "elder should be able to cancel a pending invitation");
  const secureHelpInvite = await post("/api/help/invite", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: relativesStatus.body.family.familyMembers[0].ref,
  });
  const unacceptedStart = await post("/api/help", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: relativesStatus.body.family.familyMembers[0].ref,
    helpInvitationId: secureHelpInvite.body.invitation.id,
  });
  assert(unacceptedStart.status === 409, "screen sharing must not start before the relative accepts");
  const secureInviteAccept = await post("/api/help/invite/respond", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
    invitationId: secureHelpInvite.body.invitation.id,
    accepted: true,
  });
  const acceptedInviteCancel = await post("/api/help/invite/cancel", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    invitationId: secureHelpInvite.body.invitation.id,
  });
  assert(acceptedInviteCancel.status === 200 && acceptedInviteCancel.body.invitation.status === "cancelled",
    "elder should be able to cancel after family accepts but before screen sharing starts");
  const secureHelpInviteAgain = await post("/api/help/invite", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: relativesStatus.body.family.familyMembers[0].ref,
  });
  const secureInviteAcceptAgain = await post("/api/help/invite/respond", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
    invitationId: secureHelpInviteAgain.body.invitation.id,
    accepted: true,
  });
  const targetedHelp = await post("/api/help", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: relativesStatus.body.family.familyMembers[0].ref,
    helpInvitationId: secureHelpInviteAgain.body.invitation.id,
  });
  assert(secureInviteAccept.status === 200 && secureInviteAccept.body.invitation.status === "accepted"
      && secureInviteAcceptAgain.status === 200,
    "selected relative must explicitly accept the invitation");
  assert(targetedHelp.status === 200 && targetedHelp.body.family.targetHelperName === "女儿",
    "elder should be able to request a selected relative");
  const roleDuringAssist = await post("/api/account/role", {
    accountToken: elderAccount.body.accountToken,
    appRole: "family",
  });
  assert(roleDuringAssist.status === 409, "account role must not change during an active assistance session");
  const targetedJoin = await get(`/api/help?pairCode=${securePairCode}&authToken=${pendingDone.body.authToken}`);
  assert(targetedJoin.body.helperIsCurrent && targetedJoin.body.targetedForCurrent,
    "the selected relative should join the targeted session");
  await post("/api/end", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    sessionId: targetedHelp.body.sessionId,
  });
  const cancellableFamilyRequest = await post("/api/help/family-request", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
  });
  const cancelledFamilyRequest = await post("/api/help/family-request/cancel", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
    requestId: cancellableFamilyRequest.body.request.id,
  });
  assert(cancelledFamilyRequest.status === 200 && cancelledFamilyRequest.body.request.status === "cancelled",
    "family should be able to cancel a pending assistance request");
  const withdrawableFamilyRequest = await post("/api/help/family-request", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
  });
  const withdrawableFamilyAccept = await post("/api/help/family-request/respond", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    requestId: withdrawableFamilyRequest.body.request.id,
    accepted: true,
  });
  const withdrawnFamilyRequest = await post("/api/help/family-request/withdraw", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    requestId: withdrawableFamilyRequest.body.request.id,
  });
  assert(withdrawableFamilyAccept.status === 200
      && withdrawnFamilyRequest.status === 200
      && withdrawnFamilyRequest.body.request.status === "cancelled",
    "elder should be able to cancel after accepting when system screen sharing is declined");
  const familyAssistRequest = await post("/api/help/family-request", {
    pairCode: securePairCode,
    authToken: pendingDone.body.authToken,
  });
  assert(familyAssistRequest.status === 200 && familyAssistRequest.body.request.status === "pending",
    "a bound relative should be able to request an assistance session");
  const elderSeesFamilyRequest = await get(`/api/help/family-request?pairCode=${securePairCode}&authToken=${secureInvite.body.authToken}`);
  assert(elderSeesFamilyRequest.body.request.helperName === "女儿",
    "the elder should see which relative requested assistance");
  const familyRequestAccepted = await post("/api/help/family-request/respond", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    requestId: familyAssistRequest.body.request.id,
    accepted: true,
  });
  assert(familyRequestAccepted.status === 200
    && familyRequestAccepted.body.helpInvitation.status === "accepted"
    && familyRequestAccepted.body.helpInvitation.targetHelperRef,
  "elder approval should create an accepted targeted help invitation");
  const familyInitiatedHelp = await post("/api/help", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    elderName: "妈妈",
    targetHelperRef: familyRequestAccepted.body.helpInvitation.targetHelperRef,
    helpInvitationId: familyRequestAccepted.body.helpInvitation.id,
  });
  assert(familyInitiatedHelp.status === 200 && familyInitiatedHelp.body.family.targetHelperName === "女儿",
    "screen sharing should start only after the elder accepts the relative's request");
  assert(familyInitiatedHelp.body.family.assistPhase === "active",
    "relay should expose one authoritative active assistance phase");
  const activeElderStatus = await get(`/api/bind/status?pairCode=${securePairCode}&authToken=${secureInvite.body.authToken}`);
  assert(activeElderStatus.body.family.active
    && activeElderStatus.body.family.activeHelperRef === relativesStatus.body.family.familyMembers[0].ref,
  "elder relative list state should identify the active helper");
  await post("/api/end", {
    pairCode: securePairCode,
    authToken: secureInvite.body.authToken,
    sessionId: familyInitiatedHelp.body.sessionId,
  });
  const elderRelogin = await post("/api/account/login", { phone: "13800000001", password: "elderPass123" });
  const familyRelogin = await post("/api/account/login", { phone: "13800000002", password: "familyPass123" });
  assert(elderRelogin.body.user.appRole === "elder" && familyRelogin.body.user.appRole === "family",
    "account role should survive logout, login, and device changes");
  assert(elderRelogin.body.memberships.some((item) => item.role === "elder" && item.pairCode === securePairCode),
    "elder login should restore an existing family membership");
  assert(familyRelogin.body.memberships.some((item) => item.role === "family" && item.pairCode === securePairCode),
    "family login should restore an existing family membership");

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

  const legacyRelatives = await get(`/api/bind/status?pairCode=${pairCode}&authToken=${elderToken}`);
  const firstRelative = legacyRelatives.body.family.familyMembers.find((item) => item.name === "女儿");
  const selectedRelative = legacyRelatives.body.family.familyMembers.find((item) => item.name === "儿子");
  const selectedInvite = await post("/api/help/invite", {
    pairCode,
    authToken: elderToken,
    elderName: "妈妈",
    targetHelperRef: selectedRelative.ref,
  });
  const unselectedAccept = await post("/api/help/invite/respond", {
    pairCode,
    authToken: first.body.authToken,
    invitationId: selectedInvite.body.invitation.id,
    accepted: true,
  });
  assert(unselectedAccept.status === 404, "an unselected relative must not accept the invitation");
  const selectedAccept = await post("/api/help/invite/respond", {
    pairCode,
    authToken: second.body.authToken,
    invitationId: selectedInvite.body.invitation.id,
    accepted: true,
  });
  const selectedHelp = await post("/api/help", {
    pairCode,
    authToken: elderToken,
    elderName: "妈妈",
    targetHelperRef: selectedRelative.ref,
    helpInvitationId: selectedInvite.body.invitation.id,
  });
  assert(selectedAccept.status === 200, "the selected relative should accept the invitation");
  const unselectedJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const selectedJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${second.body.authToken}`);
  assert(!unselectedJoin.body.targetedForCurrent && !unselectedJoin.body.helperIsCurrent,
    "an unselected relative must not claim a targeted session");
  assert(selectedJoin.body.targetedForCurrent && selectedJoin.body.helperIsCurrent,
    "the selected relative should claim a targeted session");
  await post("/api/end", { pairCode, authToken: elderToken, sessionId: selectedHelp.body.sessionId });

  const untargetedHelp = await post("/api/help", { pairCode, authToken: elderToken, elderName: "妈妈" });
  assert(untargetedHelp.status === 409, "elder must select an accepted relative before assistance starts");
  const help = await startAcceptedHelp({
    pairCode,
    elderToken,
    familyToken: first.body.authToken,
    targetHelperRef: firstRelative.ref,
  });
  assert(help.status === 200, "elder should start assistance");
  const sessionId = help.body.sessionId;

  const firstJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const secondJoin = await get(`/api/help?pairCode=${pairCode}&authToken=${second.body.authToken}`);
  assert(firstJoin.body.helperIsCurrent, "first relative should own the active session");
  assert(!secondJoin.body.helperIsCurrent, "second relative should be shown as occupied");

  const wrongEnd = await post("/api/family/end", { pairCode, authToken: second.body.authToken, sessionId });
  assert(wrongEnd.status === 409, "non-active relative must not end the session");

  const controlRequest = await post("/api/control/request", { pairCode, authToken: first.body.authToken });
  assert(controlRequest.status === 200 && controlRequest.body.family.controlRequested
    && controlRequest.body.family.controlDecision === "pending", "control request should be visible to elder");
  const setupRequired = await post("/api/control/allow", {
    pairCode, authToken: elderToken, allowed: false, reason: "accessibility_not_enabled",
  });
  assert(setupRequired.status === 200 && setupRequired.body.family.controlDecision === "setup_required",
    "unfinished accessibility setup should be visible to family");
  await post("/api/control/request", { pairCode, authToken: first.body.authToken });
  const controlAllow = await post("/api/control/allow", { pairCode, authToken: elderToken, allowed: true });
  assert(controlAllow.status === 200 && controlAllow.body.family.controlAllowed
    && controlAllow.body.family.controlDecision === "allowed", "control approval should be visible to family");
  const approvedState = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(approvedState.body.controlAllowed && !approvedState.body.controlRequested, "approved control state should synchronize");

  const annotation = await post("/api/annotation", {
    pairCode,
    authToken: first.body.authToken,
    sessionId,
    x: 0.42,
    y: 0.37,
    radius: 0.08,
    label: "请点这里",
  });
  assert(annotation.status === 200 && annotation.body.annotation.id, "family annotation should be accepted for the active session");
  const elderAnnotation = await get(`/api/annotation?pairCode=${pairCode}&authToken=${elderToken}`);
  assert(elderAnnotation.body.annotation.id === annotation.body.annotation.id, "elder should receive the current annotation");

  const rightEnd = await post("/api/family/end", { pairCode, authToken: first.body.authToken, sessionId });
  assert(rightEnd.status === 200, "active relative should end the session");
  const endedState = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const unrelatedEndedState = await get(`/api/help?pairCode=${pairCode}&authToken=${second.body.authToken}`);
  assert(!endedState.body.active && !endedState.body.controlAllowed && endedState.body.lastEndedForCurrent,
    "ending should clear active state and identify the relative who was assisting");
  assert(!unrelatedEndedState.body.lastEndedForCurrent,
    "an unrelated relative must not receive the session-ended event");

  const nextHelp = await startAcceptedHelp({
    pairCode,
    elderToken,
    familyToken: first.body.authToken,
    targetHelperRef: firstRelative.ref,
  });
  assert(nextHelp.status === 200 && nextHelp.body.sessionId !== sessionId, "elder should start a fresh second session");
  const heartbeat = await post("/api/elder/heartbeat", {
    pairCode, authToken: elderToken, sessionId: nextHelp.body.sessionId,
  });
  assert(heartbeat.status === 200 && heartbeat.body.family.active,
    "elder heartbeat should keep the current session active");
  await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  const staleAnnotation = await post("/api/annotation", {
    pairCode,
    authToken: first.body.authToken,
    sessionId,
    x: 0.5,
    y: 0.5,
  });
  assert(staleAnnotation.status === 409, "an annotation from an old session must be rejected");
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
  const abandonedHelp = await startAcceptedHelp({
    pairCode,
    elderToken,
    familyToken: first.body.authToken,
    targetHelperRef: firstRelative.ref,
  });
  assert(abandonedHelp.status === 200, "elder should start a session used for timeout recovery");
  await new Promise((resolve) => setTimeout(resolve, 2700));
  const recoveredState = await get(`/api/help?pairCode=${pairCode}&authToken=${first.body.authToken}`);
  assert(!recoveredState.body.active && recoveredState.body.lastEndReason === "elder_disconnected",
    "relay should clear an abandoned elder capture session");
  assert(recoveredState.body.assistPhase === "idle",
    "timed-out assistance should return to the idle phase");
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
