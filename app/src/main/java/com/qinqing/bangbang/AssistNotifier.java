package com.qinqing.bangbang;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONObject;

final class AssistNotifier {
    private static final String PREFS = "family-assist";
    static final String CHANNEL_URGENT = "assist_urgent_v2";
    private static final int NOTIFICATION_CONTROL_REQUEST = 3001;
    private static final int NOTIFICATION_ASSIST_ENDED = 3002;

    private AssistNotifier() {
    }

    static void createControlChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_URGENT,
                "重要协助提醒",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("远程操作授权和协助结束提醒");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 350, 180, 350});
        channel.enableLights(true);
        channel.setLightColor(0xFFE84B67);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                Notification.AUDIO_ATTRIBUTES_DEFAULT
        );
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    static void handleControlRequest(Context context, JSONObject family) {
        if (family == null || !family.optBoolean("controlRequested", false)) {
            return;
        }
        handleControlRequest(context, family.optString("controlUpdatedAt", ""));
    }

    static synchronized void handleControlRequest(Context context, String updatedAt) {
        if (updatedAt.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (isAppUiForeground(context)
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
                .commit();
        showControlRequestNotification(context);
        showUrgentOverlay(context,
                "家属请求远程操作",
                "请打开亲情帮帮，确认是否允许本次操作。",
                "control:" + updatedAt);
    }

    static void showControlRequestNotification(Context context) {
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
                ? new Notification.Builder(context, CHANNEL_URGENT)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle("家属请求远程点击")
                .setContentText("点这里回到亲情帮帮，确认是否允许本次协助。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setColor(0xFFE84B67)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("点这里回到亲情帮帮，确认是否允许本次远程操作。"))
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
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
                ? new Notification.Builder(context, CHANNEL_URGENT)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle("家人已结束本次协助")
                .setContentText("屏幕共享已停止。需要帮助时，可以再次发起求助。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(0xFFDC2626)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("屏幕共享和远程操作都已停止。需要帮助时，可以再次发起求助。"))
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ASSIST_ENDED, notification);
        }
    }

    static synchronized void handleAssistEnded(Context context, JSONObject family) {
        if (family == null || family.optBoolean("active", true)) {
            return;
        }
        String updatedAt = family.optString("updatedAt", "");
        if (updatedAt.isEmpty()) {
            updatedAt = "ended";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("pendingAssistMessage", "家人已结束本次协助。需要帮助时，可以再次发起求助。")
                .putBoolean("pendingAssistEndedEvent", true)
                .commit();
        if (isAppUiForeground(context)) {
            return;
        }
        String notifiedAt = prefs.getString("notifiedAssistEndedAt", "");
        if (updatedAt.equals(notifiedAt)) {
            return;
        }
        prefs.edit().putString("notifiedAssistEndedAt", updatedAt).commit();
        showAssistEndedNotification(context);
        showUrgentOverlay(context,
                "本次协助已结束",
                "家人已结束协助，屏幕共享和远程操作都已停止。",
                "ended:" + updatedAt);
    }

    static void cancelAssistEndedNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ASSIST_ENDED);
        }
    }

    static void cancelControlRequestNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_CONTROL_REQUEST);
        }
    }

    static boolean isAppUiForeground(Context context) {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        boolean processVisible = info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                || info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return processVisible && prefs.getBoolean("appForeground", false);
    }

    private static void showUrgentOverlay(Context context, String title, String message, String eventId) {
        if (Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, AnnotationOverlayService.class)
                .setAction(AnnotationOverlayService.ACTION_SHOW_URGENT)
                .putExtra(AnnotationOverlayService.EXTRA_URGENT_TITLE, title)
                .putExtra(AnnotationOverlayService.EXTRA_URGENT_MESSAGE, message)
                .putExtra(AnnotationOverlayService.EXTRA_URGENT_EVENT_ID, eventId);
        try {
            context.startService(intent);
        } catch (RuntimeException ignored) {
        }
    }
}
