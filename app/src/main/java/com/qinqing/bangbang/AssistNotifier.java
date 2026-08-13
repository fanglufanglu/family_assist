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

import org.json.JSONArray;
import org.json.JSONObject;

final class AssistNotifier {
    private static final String PREFS = "family-assist";
    static final String CHANNEL_URGENT = "assist_urgent_v2";
    private static final int NOTIFICATION_CONTROL_REQUEST = 3001;
    private static final int NOTIFICATION_ASSIST_ENDED = 3002;
    private static final int NOTIFICATION_HELP_INVITE = 3003;
    private static final int NOTIFICATION_FAMILY_ASSIST_REQUEST = 3004;
    private static final int NOTIFICATION_PEER_EVENT = 3005;

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
        channel.setDescription("家人求助、远程操作授权和协助结束提醒");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 350, 180, 350});
        channel.enableLights(true);
        channel.setLightColor(0xFFD83F5F);
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
                .setColor(0xFFD83F5F)
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

    static synchronized void handleHelpInvite(Context context, JSONObject invitation) {
        if (invitation == null || !"pending".equals(invitation.optString("status", "pending"))) return;
        String id = invitation.optString("id", "");
        if (id.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (id.equals(prefs.getString("handledFamilyHelpInvitationId", ""))) return;
        if (id.equals(prefs.getString("respondingFamilyHelpInvitationId", ""))) {
            long respondingAt = prefs.getLong("respondingFamilyHelpInvitationAtMs", 0L);
            if (respondingAt > 0 && System.currentTimeMillis() - respondingAt < 15_000L) return;
            prefs.edit()
                    .remove("respondingFamilyHelpInvitationId")
                    .remove("respondingFamilyHelpInvitationAtMs")
                    .commit();
        }
        prefs.edit().putString("pendingFamilyHelpInvitation", invitation.toString()).commit();
        if (isAppUiForeground(context)) return;
        if (id.equals(prefs.getString("notifiedFamilyHelpInvitationId", ""))) return;
        prefs.edit().putString("notifiedFamilyHelpInvitationId", id).commit();
        showHelpInviteNotification(context, invitation.optString("elderName", "长辈"));
        showUrgentOverlay(context,
                invitation.optString("elderName", "长辈") + "需要你帮忙",
                "请打开亲情帮帮，接受或拒绝本次协助请求。",
                "help-invite:" + id);
    }

    static void showHelpInviteNotification(Context context, String elderName) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra("openFamilyHelpInvite", true)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 2, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_URGENT)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle(elderName + "需要你帮忙")
                .setContentText("点这里确认是否接受本次协助请求。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setColor(0xFFD83F5F)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("接受后长辈才会开始共享屏幕。请点这里确认是否方便协助。"))
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_HELP_INVITE, notification);
    }

    static void cancelHelpInviteNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_HELP_INVITE);
    }

    static synchronized void handleFamilyAssistRequest(Context context, JSONObject request) {
        if (request == null || !"pending".equals(request.optString("status", "pending"))) return;
        String id = request.optString("id", "");
        if (id.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("pendingFamilyAssistRequest", request.toString()).commit();
        if (isAppUiForeground(context)) return;
        if (id.equals(prefs.getString("notifiedFamilyAssistRequestId", ""))) return;
        prefs.edit().putString("notifiedFamilyAssistRequestId", id).commit();
        String helperName = request.optString("helperName", "家属");
        showFamilyAssistRequestNotification(context, helperName);
        showUrgentOverlay(context,
                helperName + "请求协助你",
                "请打开亲情帮帮，先确认本次协助请求。",
                "family-assist-request:" + id);
    }

    private static void showFamilyAssistRequestNotification(Context context, String helperName) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 4, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_URGENT)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle(helperName + "请求协助你")
                .setContentText("点这里确认本次协助请求。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setColor(0xFFD83F5F)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("先确认协助请求，再由系统确认是否共享屏幕。远程点击不会自动开启。"))
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_FAMILY_ASSIST_REQUEST, notification);
    }

    static void cancelFamilyAssistRequestNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_FAMILY_ASSIST_REQUEST);
    }

    static synchronized void handlePeerEvent(Context context, String eventKey, String title, String message) {
        if (eventKey == null || eventKey.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String seenKey = "peerEventSeen_" + Integer.toHexString(eventKey.hashCode());
        if (prefs.getBoolean(seenKey, false)) return;
        try {
            JSONArray queue = pendingPeerEvents(prefs);
            if (eventKey.startsWith("assist-ended-")) {
                // A terminal event makes earlier session updates obsolete, but
                // account and binding messages must remain visible.
                JSONArray relevant = new JSONArray();
                for (int index = 0; index < queue.length(); index++) {
                    JSONObject item = queue.optJSONObject(index);
                    String queuedKey = item == null ? "" : item.optString("key", "");
                    if (!queuedKey.startsWith("assist-started-")
                            && !queuedKey.startsWith("control-")) {
                        relevant.put(item);
                    }
                }
                queue = relevant;
            }
            while (queue.length() >= 8) {
                JSONArray trimmed = new JSONArray();
                for (int index = 1; index < queue.length(); index++) {
                    trimmed.put(queue.optJSONObject(index));
                }
                queue = trimmed;
            }
            queue.put(new JSONObject()
                    .put("key", eventKey)
                    .put("title", title)
                    .put("message", message));
            prefs.edit()
                    .putBoolean(seenKey, true)
                    .putString("pendingPeerEvents", queue.toString())
                    .remove("pendingPeerEvent")
                    .commit();
        } catch (Exception ignored) {
            return;
        }
        if (isAppUiForeground(context)) return;
        showPeerEventNotification(context, title, message);
        showUrgentOverlay(context, title, message, "peer-event:" + eventKey);
    }

    static JSONArray pendingPeerEvents(SharedPreferences prefs) {
        try {
            JSONArray queue = new JSONArray(prefs.getString("pendingPeerEvents", "[]"));
            String legacy = prefs.getString("pendingPeerEvent", "");
            if (!legacy.isEmpty()) queue.put(new JSONObject(legacy));
            return queue;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void showPeerEventNotification(Context context, String title, String message) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 5, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_URGENT)
                : new Notification.Builder(context);
        Notification notification = builder
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(0xFFD83F5F)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_PEER_EVENT, notification);
    }

    static void cancelPeerEventNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_PEER_EVENT);
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
        if (updatedAt.equals(prefs.getString("handledAssistEndedAt", ""))) {
            return;
        }
        prefs.edit()
                .putString("pendingAssistMessage", "家人已结束本次协助。需要帮助时，可以再次发起求助。")
                .putString("pendingAssistEndedAt", updatedAt)
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
