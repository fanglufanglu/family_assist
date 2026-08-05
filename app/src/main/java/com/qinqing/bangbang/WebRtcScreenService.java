package com.qinqing.bangbang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class WebRtcScreenService extends Service {
    static final String EXTRA_BASE_URL = "baseUrl";
    static final String EXTRA_PAIR_CODE = "pairCode";
    static final String EXTRA_AUTH_TOKEN = "authToken";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "webrtc_screen";
    private WebRtcClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2, buildNotification("正在建立实时屏幕连接"));
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String baseUrl = intent.getStringExtra(EXTRA_BASE_URL);
        String pairCode = intent.getStringExtra(EXTRA_PAIR_CODE);
        String authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        if (resultData == null || baseUrl == null || pairCode == null || authToken == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (client == null) {
            client = new WebRtcClient(this, baseUrl, pairCode, authToken, new WebRtcClient.Listener() {
                @Override
                public void onState(String text) {
                    startForeground(2, buildNotification(text));
                }

                @Override
                public void onRemoteVideo(org.webrtc.VideoTrack track) {
                }
            });
            client.startElder(resultData);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.stop();
            client = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
