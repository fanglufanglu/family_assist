package com.qinqing.bangbang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FamilyInviteMonitorService extends Service {
    private static final String TAG = "FamilyInviteMonitor";
    private static final String PREFS = "family-assist";
    private static final String CHANNEL_ID = "family_invite_monitor";
    private static final int NOTIFICATION_ID = 3010;
    private static final long POLL_INTERVAL_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean polling;
    private boolean foregroundReady;

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        foregroundReady = promoteToForegroundSafely();
        if (!foregroundReady) {
            stopSelf();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("familyMonitorSafeMode", false)) {
            Log.w(TAG, "Persistent monitor disabled for device compatibility");
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return;
        }
        main.post(pollLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(pollLoop);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollOnce() {
        if (polling) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String role = prefs.getString("memberRole", "");
        String pairCode = prefs.getString("pairCode", "");
        String authToken = prefs.getString("authToken", "");
        String accountToken = prefs.getString("accountToken", "");
        String selectedAppRole = prefs.getString("selectedAppRole", "");
        String baseUrl = prefs.getString("baseUrl", "");
        if (baseUrl.isEmpty()) {
            return;
        }
        polling = true;
        io.execute(() -> {
            try {
                pollPendingBinding(baseUrl, accountToken, prefs);
                if ("family".equals(selectedAppRole) && !accountToken.isEmpty()) {
                    pollAllFamilyMemberships(baseUrl, accountToken, prefs);
                } else if ("family".equals(role) && !pairCode.isEmpty() && !authToken.isEmpty()) {
                    pollFamilyMembership(baseUrl, pairCode, authToken);
                } else if ("elder".equals(role) && !pairCode.isEmpty() && !authToken.isEmpty()) {
                    pollElderMembership(baseUrl, pairCode, authToken);
                }
            } catch (Exception error) {
                Log.w(TAG, "Cross-device event polling failed", error);
            } finally {
                polling = false;
            }
        });
    }

    private void pollAllFamilyMemberships(String baseUrl, String accountToken, SharedPreferences prefs) throws Exception {
        JSONObject account = NetworkClient.getJson(baseUrl,
                "/api/account/me?accountToken=" + encoded(accountToken));
        JSONArray memberships = account.optJSONArray("memberships");
        if (memberships == null) return;
        for (int index = 0; index < memberships.length(); index++) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || !"family".equals(membership.optString("role", ""))) continue;
            String membershipPairCode = membership.optString("pairCode", "");
            String membershipAuthToken = membership.optString("authToken", "");
            if (membershipPairCode.isEmpty() || membershipAuthToken.isEmpty()) continue;
            pollFamilyMembership(baseUrl, membershipPairCode, membershipAuthToken);
        }
    }

    private void pollFamilyMembership(String baseUrl, String pairCode, String authToken) throws Exception {
        JSONObject state = NetworkClient.getJson(baseUrl,
                "/api/help?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
        JSONObject invitation = state.optJSONObject("helpInvitation");
        if (invitation != null) {
            String invitationState = invitation.optString("status", "pending");
            if ("pending".equals(invitationState)) {
                invitation.put("membershipPairCode", pairCode);
                invitation.put("membershipAuthToken", authToken);
                AssistNotifier.handleHelpInvite(this, invitation);
            } else if (("cancelled".equals(invitationState) || "expired".equals(invitationState))
                    && isRecent(invitation.optString("updatedAt", ""))) {
                AssistNotifier.handlePeerEvent(this,
                        "help-invite-" + invitation.optString("id", "") + "-" + invitationState,
                        "求助请求已结束",
                        "长辈已取消求助，或请求已超时。无需继续等待。 ");
            }
        }
        JSONObject request = state.optJSONObject("familyAssistRequest");
        if (request != null && isRecent(request.optString("updatedAt", ""))) {
            String requestState = request.optString("status", "");
            String elderName = request.optString("elderName", "长辈");
            if ("accepted".equals(requestState)) {
                notifyRequestResult(request, requestState, elderName + "已同意协助", "正在等待对方确认屏幕共享。 ");
            } else if ("declined".equals(requestState)) {
                notifyRequestResult(request, requestState, elderName + "暂时不需要协助", "可以稍后再次发起请求。 ");
            } else if ("expired".equals(requestState)) {
                notifyRequestResult(request, requestState, "协助请求已超时", "对方暂未回应，可以重新发起。 ");
            }
        }
        if (state.optBoolean("active", false)
                && state.optBoolean("targetedForCurrent", false)
                && state.optBoolean("helperIsCurrent", false)) {
            AssistNotifier.handlePeerEvent(this,
                    "assist-started-" + state.optString("sessionId", ""),
                    state.optString("elderName", "长辈") + "已开始共享屏幕",
                    "点这里进入亲情帮帮，开始本次协助。 ");
        }
        String controlDecision = state.optString("controlDecision", "idle");
        String controlUpdatedAt = state.optString("controlUpdatedAt", "");
        if (isRecent(controlUpdatedAt) && ("allowed".equals(controlDecision)
                || "denied".equals(controlDecision) || "setup_required".equals(controlDecision))) {
            String title = "allowed".equals(controlDecision) ? "长辈已允许远程操作"
                    : ("setup_required".equals(controlDecision) ? "长辈尚未完成辅助服务设置" : "长辈未允许远程操作");
            String message = "allowed".equals(controlDecision)
                    ? "本次协助中可以使用远程操作。"
                    : "你仍可继续查看屏幕和发送画圈提示。";
            AssistNotifier.handlePeerEvent(this, "control-" + controlUpdatedAt + "-" + controlDecision, title, message);
        }
        String endReason = state.optString("lastEndReason", "");
        if (!state.optBoolean("active", false)
                && state.optBoolean("lastEndedForCurrent", false)
                && ("elder_ended".equals(endReason) || "elder_disconnected".equals(endReason)
                    || "admin_ended".equals(endReason))
                && isRecent(state.optString("lastEndedAt", ""))) {
            boolean disconnected = "elder_disconnected".equals(endReason);
            boolean adminEnded = "admin_ended".equals(endReason);
            AssistNotifier.handlePeerEvent(this,
                    "assist-ended-" + state.optString("lastEndedAt", ""),
                    disconnected ? "协助连接已断开"
                            : (adminEnded ? "平台已结束本次协助" : "长辈已结束本次求助"),
                    adminEnded
                            ? "为保护协助安全，屏幕共享和远程操作均已关闭。"
                            : disconnected
                            ? "长辈的屏幕共享已停止，可以稍后重新发起协助。"
                            : "屏幕共享和远程操作均已关闭。 ");
        }
    }

    private void pollElderMembership(String baseUrl, String pairCode, String authToken) throws Exception {
        JSONObject state = NetworkClient.getJson(baseUrl,
                "/api/help?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
        JSONObject request = state.optJSONObject("familyAssistRequest");
        if (request != null) {
            String requestState = request.optString("status", "pending");
            if ("pending".equals(requestState)) {
                AssistNotifier.handleFamilyAssistRequest(this, request);
            } else if (("cancelled".equals(requestState) || "expired".equals(requestState))
                    && isRecent(request.optString("updatedAt", ""))) {
                AssistNotifier.handlePeerEvent(this,
                        "family-request-" + request.optString("id", "") + "-" + requestState,
                        "家属已取消协助请求",
                        "这次请求已经结束，无需继续操作。 ");
            }
        }
        JSONArray bindRequests = state.optJSONArray("pendingBindRequests");
        if (bindRequests != null && bindRequests.length() > 0) {
            JSONObject bindRequest = bindRequests.optJSONObject(0);
            if (bindRequest != null) {
                String requesterName = bindRequest.optString("requesterName", "家属");
                AssistNotifier.handlePeerEvent(this,
                        "bind-request-" + bindRequest.optString("id", ""),
                        requesterName + "申请成为你的协助家人",
                        "请打开亲情帮帮，确认是否同意本次绑定申请。 ");
            }
        }
        JSONObject membershipEvent = state.optJSONObject("lastMembershipEvent");
        if (membershipEvent != null
                && "family_unbound".equals(membershipEvent.optString("type", ""))
                && isRecent(membershipEvent.optString("updatedAt", ""))) {
            AssistNotifier.handlePeerEvent(this,
                    "membership-" + membershipEvent.optString("updatedAt", ""),
                    membershipEvent.optString("name", "家属") + "已解除绑定",
                    "对方将不再接收你的求助提醒。 ");
        }
        JSONObject invitation = state.optJSONObject("helpInvitation");
        if (invitation != null && isRecent(invitation.optString("updatedAt", ""))) {
            String invitationState = invitation.optString("status", "");
            String helperName = invitation.optString("targetHelperName", "家属");
            if ("accepted".equals(invitationState)) {
                AssistNotifier.handlePeerEvent(this,
                        "help-invite-" + invitation.optString("id", "") + "-accepted",
                        helperName + "已接受求助",
                        "请回到亲情帮帮，继续确认屏幕共享。 ");
            } else if ("declined".equals(invitationState)) {
                AssistNotifier.handlePeerEvent(this,
                        "help-invite-" + invitation.optString("id", "") + "-declined",
                        helperName + "暂时无法帮忙",
                        "可以选择其他家人重新发起求助。 ");
            } else if ("expired".equals(invitationState)) {
                AssistNotifier.handlePeerEvent(this,
                        "help-invite-" + invitation.optString("id", "") + "-expired",
                        "求助请求已超时",
                        "家属暂未回应，可以重新选择家人。 ");
            }
        }
        if (state.optBoolean("controlRequested", false)) {
            AssistNotifier.handleControlRequest(this, state);
        }
        if (!state.optBoolean("active", true)
                && ("family_ended".equals(state.optString("lastEndReason", ""))
                    || "admin_ended".equals(state.optString("lastEndReason", "")))
                && isRecent(state.optString("lastEndedAt", ""))) {
            AssistNotifier.handleAssistEnded(this, state);
        }
    }

    private void pollPendingBinding(String baseUrl, String accountToken, SharedPreferences prefs) {
        String pendingToken = prefs.getString("pendingBindToken", "");
        String pendingPairCode = prefs.getString("pendingBindPairCode", "");
        if (accountToken.isEmpty() || pendingToken.isEmpty() || pendingPairCode.isEmpty()) return;
        try {
            JSONObject result = NetworkClient.getJson(baseUrl,
                    "/api/bind/pending?pairCode=" + encoded(pendingPairCode)
                            + "&pendingToken=" + encoded(pendingToken)
                            + "&accountToken=" + encoded(accountToken));
            if (result.optBoolean("approved", false)) {
                AssistNotifier.handlePeerEvent(this,
                        "bind-approved-" + pendingToken,
                        "长辈已同意绑定",
                        "现在可以在“家人”页面向长辈发起协助请求。 ");
            } else if (result.optBoolean("rejected", false)) {
                AssistNotifier.handlePeerEvent(this,
                        "bind-rejected-" + pendingToken,
                        "长辈未同意本次绑定",
                        "如有需要，请与长辈确认后重新申请。 ");
            }
        } catch (Exception ignored) {
            // Keep polling while the request is pending or the network is temporarily unavailable.
        }
    }

    private void notifyRequestResult(JSONObject request, String state, String title, String message) {
        AssistNotifier.handlePeerEvent(this,
                "family-request-" + request.optString("id", "") + "-" + state,
                title, message);
    }

    private boolean isRecent(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return false;
        try {
            long elapsed = System.currentTimeMillis() - Instant.parse(timestamp).toEpochMilli();
            return elapsed >= 0 && elapsed <= 10 * 60 * 1000L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "亲情协助提醒",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持家人求助提醒可及时送达");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void promoteToForeground() {
        Notification notification = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean promoteToForegroundSafely() {
        try {
            createChannel();
            promoteToForeground();
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Foreground family monitor is unavailable on this device", error);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean("familyMonitorSafeMode", true)
                    .commit();
            return false;
        }
    }

    private Notification buildForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 3, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("亲情协助提醒已开启")
                .setContentText("收到家人协助请求时会及时提醒")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
