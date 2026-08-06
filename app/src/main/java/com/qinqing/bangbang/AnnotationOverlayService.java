package com.qinqing.bangbang;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnnotationOverlayService extends Service {
    static final String ACTION_SHOW_URGENT = "com.qinqing.bangbang.SHOW_URGENT";
    static final String EXTRA_URGENT_TITLE = "urgentTitle";
    static final String EXTRA_URGENT_MESSAGE = "urgentMessage";
    static final String EXTRA_URGENT_EVENT_ID = "urgentEventId";
    private static final String PREFS = "family-assist";
    private static final String TAG = "FamilyAssistOverlay";
    private static final long ANNOTATION_VISIBLE_MS = 7000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AnnotationView annotationView;
    private FrameLayout overlayRoot;
    private View urgentView;
    private WindowManager windowManager;
    private boolean running;
    private boolean pollLoopStarted;
    private volatile boolean pollInFlight;
    private String lastUpdatedAt = "";
    private long visibleUntilMs;
    private String lastUrgentEventId = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (annotationView == null) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission is unavailable; annotation service stopped");
                stopSelf();
                return START_NOT_STICKY;
            }
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            annotationView = new AnnotationView(this);
            overlayRoot = new FrameLayout(this);
            overlayRoot.addView(annotationView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
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
            try {
                windowManager.addView(overlayRoot, params);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to attach annotation overlay", error);
                annotationView = null;
                overlayRoot = null;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        running = true;
        if (intent != null && ACTION_SHOW_URGENT.equals(intent.getAction())) {
            showUrgentMessage(
                    intent.getStringExtra(EXTRA_URGENT_TITLE),
                    intent.getStringExtra(EXTRA_URGENT_MESSAGE),
                    intent.getStringExtra(EXTRA_URGENT_EVENT_ID)
            );
        }
        if (!pollLoopStarted) {
            pollLoopStarted = true;
            main.post(pollLoop);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        pollLoopStarted = false;
        main.removeCallbacksAndMessages(null);
        removeUrgentView();
        if (overlayRoot != null) {
            windowManager.removeView(overlayRoot);
            annotationView = null;
            overlayRoot = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showUrgentMessage(String title, String message, String eventId) {
        if (overlayRoot == null || eventId == null || eventId.equals(lastUrgentEventId)) {
            return;
        }
        lastUrgentEventId = eventId;
        removeUrgentView();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setElevation(dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFEFEFF);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), 0xFFD6DEE9);
        panel.setBackground(background);

        TextView titleView = new TextView(this);
        titleView.setText(title == null ? "亲情帮帮提醒" : title);
        titleView.setTextColor(0xFF172033);
        titleView.setTextSize(18);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView messageView = new TextView(this);
        messageView.setText(message == null ? "点这里打开亲情帮帮。" : message);
        messageView.setTextColor(0xFF526079);
        messageView.setTextSize(15);
        messageView.setPadding(0, dp(5), 0, 0);
        panel.addView(titleView);
        panel.addView(messageView);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        params.setMargins(dp(10), dp(18), dp(10), 0);
        try {
            overlayRoot.addView(panel, params);
            urgentView = panel;
            main.postDelayed(this::removeUrgentView, 12_000);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to attach urgent overlay", error);
        }
    }

    private void removeUrgentView() {
        if (urgentView == null || overlayRoot == null) {
            return;
        }
        try {
            overlayRoot.removeView(urgentView);
        } catch (RuntimeException ignored) {
        }
        urgentView = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            pollOnce();
            main.postDelayed(this, 600);
        }
    };

    private void pollOnce() {
        if (pollInFlight) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String baseUrl = prefs.getString("baseUrl", "");
        String pairCode = prefs.getString("pairCode", "");
        String authToken = prefs.getString("authToken", "");
        if (baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            return;
        }
        pollInFlight = true;
        io.execute(() -> {
            try {
                String pair = URLEncoder.encode(pairCode, StandardCharsets.UTF_8.name());
                String token = URLEncoder.encode(authToken, StandardCharsets.UTF_8.name());
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/annotation?pairCode=" + pair + "&authToken=" + token);
                JSONObject annotation = result.optJSONObject("annotation");
                main.post(() -> showAnnotation(annotation));
            } catch (Exception error) {
                Log.w(TAG, "Annotation poll failed: " + error.getMessage());
            } finally {
                pollInFlight = false;
            }
        });
    }

    private void showAnnotation(JSONObject annotation) {
        if (annotationView == null) {
            return;
        }
        if (annotation == null) {
            if (System.currentTimeMillis() > visibleUntilMs) {
                annotationView.clear();
            }
            return;
        }
        String updatedAt = annotation.optString("updatedAt", "");
        if (!updatedAt.equals(lastUpdatedAt)) {
            lastUpdatedAt = updatedAt;
            visibleUntilMs = System.currentTimeMillis() + ANNOTATION_VISIBLE_MS;
            Log.i(TAG, "Showing annotation " + annotation.optString("id", updatedAt));
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
