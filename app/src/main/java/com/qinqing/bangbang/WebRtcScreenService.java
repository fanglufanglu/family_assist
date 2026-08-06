package com.qinqing.bangbang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;

import org.json.JSONObject;
import org.webrtc.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private final ExecutorService fallbackIo = Executors.newSingleThreadExecutor();
    private boolean monitoring;
    private String baseUrl;
    private String pairCode;
    private String authToken;
    private String sessionId;
    private long lastSessionCheckMs;
    private volatile boolean assistStateCheckInFlight;
    private volatile boolean rtcConnected;
    private volatile boolean fallbackUploadInFlight;
    private long lastFallbackUploadMs;

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
                    if (text.contains("CONNECTED") || text.contains("COMPLETED")) {
                        rtcConnected = true;
                    } else if (text.contains("FAILED") || text.contains("CLOSED") || text.contains("DISCONNECTED")) {
                        rtcConnected = false;
                    }
                    showForeground(text);
                }

                @Override
                public void onRemoteVideo(org.webrtc.VideoTrack track) {
                }

                @Override
                public void onLocalVideoFrame(VideoFrame frame) {
                    uploadFallbackFrame(frame);
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
            maybeStopIfSessionEnded();
            main.postDelayed(this, 1500);
        }
    };

    private void maybeStopIfSessionEnded() {
        long now = System.currentTimeMillis();
        if (assistStateCheckInFlight || now - lastSessionCheckMs < 1500) {
            return;
        }
        lastSessionCheckMs = now;
        assistStateCheckInFlight = true;
        monitorIo.execute(() -> {
            try {
                String pair = URLEncoder.encode(pairCode, StandardCharsets.UTF_8.name());
                String token = URLEncoder.encode(authToken, StandardCharsets.UTF_8.name());
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + pair + "&authToken=" + token);
                JSONObject family = result.optJSONObject("family");
                AssistNotifier.handleControlRequest(this, family);
                if (family != null && !family.optBoolean("active", false)) {
                    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                    prefs.edit()
                            .putString("pendingAssistMessage", "家属已结束本次协助。需要时，你可以再次点“开始协助”。")
                            .putBoolean("pendingAssistEndedEvent", true)
                            .apply();
                    if (!prefs.getBoolean("appForeground", false)) {
                        AssistNotifier.showAssistEndedNotification(this);
                    }
                    stopSelf();
                }
            } catch (Exception ignored) {
            } finally {
                assistStateCheckInFlight = false;
            }
        });
    }

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
        fallbackIo.shutdownNow();
        super.onDestroy();
    }

    private void uploadFallbackFrame(VideoFrame frame) {
        long now = System.currentTimeMillis();
        if (rtcConnected || fallbackUploadInFlight || now - lastFallbackUploadMs < 1200) {
            return;
        }
        lastFallbackUploadMs = now;
        fallbackUploadInFlight = true;
        frame.retain();
        fallbackIo.execute(() -> {
            VideoFrame.I420Buffer buffer = null;
            try {
                buffer = frame.getBuffer().toI420();
                byte[] nv21 = toNv21(buffer);
                SharedPreferences safety = getSharedPreferences(PREFS, MODE_PRIVATE);
                boolean masked = safety.getBoolean("manualPrivacyMask", false)
                        || (safety.getBoolean("sensitiveDetectionEnabled", true)
                        && safety.getBoolean("autoPrivacyMask", false));
                if (masked) {
                    int ySize = buffer.getWidth() * buffer.getHeight();
                    Arrays.fill(nv21, 0, ySize, (byte) 16);
                    Arrays.fill(nv21, ySize, nv21.length, (byte) 128);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new YuvImage(nv21, ImageFormat.NV21, buffer.getWidth(), buffer.getHeight(), null)
                        .compressToJpeg(new Rect(0, 0, buffer.getWidth(), buffer.getHeight()), 48, out);
                String path = "/api/frame?pairCode=" + encoded(pairCode)
                        + "&authToken=" + encoded(authToken)
                        + "&sessionId=" + encoded(sessionId)
                        + "&masked=" + (masked ? "1" : "0");
                NetworkClient.postJpeg(baseUrl, path, out.toByteArray());
            } catch (Exception ignored) {
            } finally {
                if (buffer != null) buffer.release();
                frame.release();
                fallbackUploadInFlight = false;
            }
        });
    }

    private byte[] toNv21(VideoFrame.I420Buffer buffer) {
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        byte[] output = new byte[width * height + chromaWidth * chromaHeight * 2];
        copyPlane(buffer.getDataY(), buffer.getStrideY(), width, height, output, 0, 1);
        ByteBuffer u = buffer.getDataU();
        ByteBuffer v = buffer.getDataV();
        int offset = width * height;
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                output[offset++] = v.get(row * buffer.getStrideV() + col);
                output[offset++] = u.get(row * buffer.getStrideU() + col);
            }
        }
        return output;
    }

    private void copyPlane(ByteBuffer source, int stride, int width, int height,
                           byte[] output, int offset, int outputStride) {
        int target = offset;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                output[target] = source.get(row * stride + col);
                target += outputStride;
            }
        }
    }

    private String encoded(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
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
