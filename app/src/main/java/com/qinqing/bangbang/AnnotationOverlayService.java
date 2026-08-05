package com.qinqing.bangbang;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnnotationOverlayService extends Service {
    private static final String PREFS = "family-assist";
    private static final long ANNOTATION_VISIBLE_MS = 6500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AnnotationView annotationView;
    private WindowManager windowManager;
    private boolean running;
    private String lastUpdatedAt = "";
    private long visibleUntilMs;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (annotationView == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            annotationView = new AnnotationView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(annotationView, params);
        }
        running = true;
        main.post(pollLoop);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (annotationView != null) {
            windowManager.removeView(annotationView);
            annotationView = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            pollOnce();
            main.postDelayed(this, 200);
        }
    };

    private void pollOnce() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String baseUrl = prefs.getString("baseUrl", "");
        String pairCode = prefs.getString("pairCode", "");
        String authToken = prefs.getString("authToken", "");
        if (baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            return;
        }
        io.execute(() -> {
            try {
                String pair = URLEncoder.encode(pairCode, StandardCharsets.UTF_8.name());
                String token = URLEncoder.encode(authToken, StandardCharsets.UTF_8.name());
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/annotation?pairCode=" + pair + "&authToken=" + token);
                JSONObject annotation = result.optJSONObject("annotation");
                main.post(() -> showAnnotation(annotation));
            } catch (Exception ignored) {
            }
        });
    }

    private void showAnnotation(JSONObject annotation) {
        if (annotationView == null) {
            return;
        }
        if (annotation == null) {
            annotationView.clear();
            return;
        }
        String updatedAt = annotation.optString("updatedAt", "");
        if (!updatedAt.equals(lastUpdatedAt)) {
            lastUpdatedAt = updatedAt;
            visibleUntilMs = System.currentTimeMillis() + ANNOTATION_VISIBLE_MS;
            main.postDelayed(() -> {
                if (annotationView != null && updatedAt.equals(lastUpdatedAt)) {
                    annotationView.clear();
                }
            }, ANNOTATION_VISIBLE_MS);
        } else if (System.currentTimeMillis() > visibleUntilMs) {
            annotationView.clear();
            return;
        }
        annotationView.setAnnotation(
                (float) annotation.optDouble("x", 0.5),
                (float) annotation.optDouble("y", 0.5),
                (float) annotation.optDouble("radius", 0.08),
                annotation.optString("label", "请点这里")
        );
    }
}
