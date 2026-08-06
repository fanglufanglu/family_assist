package com.qinqing.bangbang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcScreenService extends Service {
    static final String EXTRA_BASE_URL = "baseUrl";
    static final String EXTRA_PAIR_CODE = "pairCode";
    static final String EXTRA_AUTH_TOKEN = "authToken";
    static final String EXTRA_SESSION_ID = "sessionId";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "webrtc_screen";
    private static final String PREFS = "family-assist";
    private WebRtcClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService monitorIo = Executors.newSingleThreadExecutor();
    private boolean monitoring;
    private String baseUrl;
    private String pairCode;
    private String authToken;
    private String sessionId;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        AssistNotifier.createControlChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForeground("正在建立实时屏幕连接");
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL);
        pairCode = intent.getStringExtra(EXTRA_PAIR_CODE);
        authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (resultData == null || baseUrl == null || pairCode == null || authToken == null || sessionId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (client == null) {
            client = new WebRtcClient(this, baseUrl, pairCode, authToken, sessionId, new WebRtcClient.Listener() {
                @Override
                public void onState(String text) {
                    showForeground(text);
                }

                @Override
                public void onRemoteVideo(org.webrtc.VideoTrack track) {
                }
            });
            client.startElder(resultData);
        }
        monitoring = true;
        main.post(monitorLoop);
        return START_NOT_STICKY;
    }

    private final Runnable monitorLoop = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) {
                return;
            }
            monitorIo.execute(() -> AssistNotifier.pollControlRequest(WebRtcScreenService.this, baseUrl, pairCode, authToken));
            main.postDelayed(this, 1500);
        }
    };

    private void showForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(2, notification);
        }
    }

    @Override
    public void onDestroy() {
        monitoring = false;
        endRelaySession();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("assistActive", false)
                .putBoolean("remoteControlAllowed", false)
                .remove("assistStartedAtMs")
                .apply();
        if (client != null) {
            client.stop();
            client = null;
        }
        monitorIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void endRelaySession() {
        if (baseUrl == null || pairCode == null || authToken == null
                || baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("sessionId", sessionId));
            } catch (Exception ignored) {
            }
        }, "webrtc-end-relay").start();
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("亲情帮帮实时协助")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "实时屏幕协助",
                android.app.NotificationManager.IMPORTANCE_LOW
        );
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}
