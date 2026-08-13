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

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FamilyInviteMonitorService extends Service {
    private static final String PREFS = "family-assist";
    private static final String CHANNEL_ID = "family_invite_monitor";
    private static final int NOTIFICATION_ID = 3010;
    private static final long POLL_INTERVAL_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean polling;

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
        createChannel();
        promoteToForeground();
        main.post(pollLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        return START_STICKY;
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
                if ("family".equals(selectedAppRole) && !accountToken.isEmpty()) {
                    pollAllFamilyMemberships(baseUrl, accountToken, prefs);
                } else if ("family".equals(role) && !pairCode.isEmpty() && !authToken.isEmpty()) {
                    JSONObject result = NetworkClient.getJson(baseUrl,
                            "/api/help/invite?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                    JSONObject invitation = result.optJSONObject("invitation");
                    if (invitation != null && "pending".equals(invitation.optString("status", "pending"))) {
                        invitation.put("membershipPairCode", pairCode);
                        invitation.put("membershipAuthToken", authToken);
                        AssistNotifier.handleHelpInvite(this, invitation);
                    }
                } else if ("elder".equals(role) && !pairCode.isEmpty() && !authToken.isEmpty()) {
                    JSONObject result = NetworkClient.getJson(baseUrl,
                            "/api/help/family-request?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                    JSONObject request = result.optJSONObject("request");
                    if (request != null && "pending".equals(request.optString("status", "pending"))) {
                        AssistNotifier.handleFamilyAssistRequest(this, request);
                    }
                }
            } catch (Exception ignored) {
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
        String handledId = prefs.getString("handledFamilyHelpInvitationId", "");
        for (int index = 0; index < memberships.length(); index++) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || !"family".equals(membership.optString("role", ""))) continue;
            String membershipPairCode = membership.optString("pairCode", "");
            String membershipAuthToken = membership.optString("authToken", "");
            if (membershipPairCode.isEmpty() || membershipAuthToken.isEmpty()) continue;
            JSONObject result = NetworkClient.getJson(baseUrl,
                    "/api/help/invite?pairCode=" + encoded(membershipPairCode)
                            + "&authToken=" + encoded(membershipAuthToken));
            JSONObject invitation = result.optJSONObject("invitation");
            if (invitation == null || !"pending".equals(invitation.optString("status", "pending"))
                    || handledId.equals(invitation.optString("id", ""))) continue;
            invitation.put("membershipPairCode", membershipPairCode);
            invitation.put("membershipAuthToken", membershipAuthToken);
            AssistNotifier.handleHelpInvite(this, invitation);
            return;
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
