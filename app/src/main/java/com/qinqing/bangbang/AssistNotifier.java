package com.qinqing.bangbang;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class AssistNotifier {
    private static final String PREFS = "family-assist";
    private static final String CHANNEL_CONTROL = "control_requests";
    private static final int NOTIFICATION_CONTROL_REQUEST = 3001;
    private static final int NOTIFICATION_ASSIST_ENDED = 3002;

    private AssistNotifier() {
    }

    static void createControlChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_CONTROL,
                "协助授权提醒",
                NotificationManager.IMPORTANCE_HIGH
        );
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    static void pollControlRequest(Context context, String baseUrl, String pairCode, String authToken) {
        if (baseUrl == null || pairCode == null || authToken == null
                || baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            return;
        }
        try {
            JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
            JSONObject family = result.optJSONObject("family");
            if (family == null || !family.optBoolean("controlRequested", false)) {
                return;
            }
            String updatedAt = family.optString("controlUpdatedAt", "");
            if (updatedAt.isEmpty()) {
                return;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean("appForeground", false)
                    && prefs.getBoolean("elderPageVisible", false)) {
                return;
            }
            String notifiedAt = prefs.getString("notifiedControlRequestAt", "");
            String handledAt = prefs.getString("handledControlRequestAt", "");
            if (updatedAt.equals(notifiedAt) || updatedAt.equals(handledAt)) {
                return;
            }
            prefs.edit()
                    .putString("pendingControlRequestAt", updatedAt)
                    .putString("notifiedControlRequestAt", updatedAt)
                    .apply();
            showControlRequestNotification(context);
        } catch (Exception ignored) {
        }
    }

    private static void showControlRequestNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_CONTROL)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle("家属请求远程点击")
                .setContentText("点这里回到亲情帮帮，确认是否允许本次协助。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_CONTROL_REQUEST, notification);
        }
    }

    static void showAssistEndedNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                1,
                intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_CONTROL)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle("本次协助已结束")
                .setContentText("家属已结束协助。需要帮助时可以再次发起。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ASSIST_ENDED, notification);
        }
    }

    static void cancelAssistEndedNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ASSIST_ENDED);
        }
    }

    private static String encoded(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
