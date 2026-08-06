package com.qinqing.bangbang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

public class CaptureService extends Service {
    static final String EXTRA_BASE_URL = "baseUrl";
    static final String EXTRA_PAIR_CODE = "pairCode";
    static final String EXTRA_AUTH_TOKEN = "authToken";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "screen_capture";
    private static final String PREFS = "family-assist";

    private HandlerThread workerThread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private String baseUrl;
    private String pairCode;
    private String authToken;
    private long lastControlCheckMs;
    private boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        AssistNotifier.createControlChannel(this);
        workerThread = new HandlerThread("capture-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForeground();
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL);
        pairCode = intent.getStringExtra(EXTRA_PAIR_CODE);
        authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null || baseUrl == null || pairCode == null || authToken == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startProjection(resultCode, resultData);
        return START_STICKY;
    }

    private void showForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        endRelaySession();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("assistActive", false)
                .putBoolean("remoteControlAllowed", false)
                .remove("assistStartedAtMs")
                .apply();
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (projection != null) {
            projection.stop();
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    private void startProjection(int resultCode, Intent resultData) {
        if (projection != null) {
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, worker);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "family-assist-screen",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                worker
        );
        worker.post(captureLoop);
    }

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            captureAndUpload();
            maybePollControlRequest();
            worker.postDelayed(this, 520);
        }
    };

    private void endRelaySession() {
        if (baseUrl == null || pairCode == null || authToken == null
                || baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
            } catch (Exception ignored) {
            }
        }, "capture-end-relay").start();
    }

    private void maybePollControlRequest() {
        long now = System.currentTimeMillis();
        if (now - lastControlCheckMs < 1500) {
            return;
        }
        lastControlCheckMs = now;
        AssistNotifier.pollControlRequest(this, baseUrl, pairCode, authToken);
    }

    private void captureAndUpload() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap raw = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, width, height);
            raw.recycle();

            int targetWidth = Math.min(420, cropped.getWidth());
            int targetHeight = Math.max(1, cropped.getHeight() * targetWidth / cropped.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true);
            cropped.recycle();

            boolean masked = shouldMask();
            if (masked) {
                scaled.recycle();
                scaled = privacyMaskBitmap(targetWidth, targetHeight);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 28, out);
            scaled.recycle();
            String encodedPairCode = URLEncoder.encode(pairCode, StandardCharsets.UTF_8.name());
            String encodedAuthToken = URLEncoder.encode(authToken, StandardCharsets.UTF_8.name());
            String path = "/api/frame?pairCode=" + encodedPairCode
                    + "&authToken=" + encodedAuthToken
                    + "&masked=" + (masked ? "1" : "0");
            NetworkClient.postJpeg(baseUrl, path, out.toByteArray());
        } catch (Exception ignored) {
            // MVP: keep capturing even when one upload fails.
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private boolean shouldMask() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean("manualPrivacyMask", false) || prefs.getBoolean("autoPrivacyMask", false);
    }

    private Bitmap privacyMaskBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(17, 24, 39));

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTextSize(42f);
        title.setFakeBoldText(true);
        title.setTextAlign(Paint.Align.CENTER);

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.rgb(209, 213, 219));
        body.setTextSize(26f);
        body.setTextAlign(Paint.Align.CENTER);

        canvas.drawText("隐私保护中", width / 2f, height / 2f - 24f, title);
        canvas.drawText("当前页面可能包含密码、验证码或支付信息", width / 2f, height / 2f + 28f, body);
        return bitmap;
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("亲情协助进行中")
                .setContentText("正在共享屏幕截图，可随时回到 APP 停止")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕协助",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }
}
