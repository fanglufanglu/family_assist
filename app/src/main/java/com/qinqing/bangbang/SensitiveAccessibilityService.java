package com.qinqing.bangbang;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.WindowManager;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SensitiveAccessibilityService extends AccessibilityService {
    private static final String PREFS = "family-assist";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean pollingControl;
    private long lastControlPollMs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        main.post(controlLoop);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("sensitiveDetectionEnabled", true)) {
            boolean sensitive = isSensitivePackage(event) || containsSensitiveText(getRootInActiveWindow(), 0);
            prefs.edit().putBoolean("autoPrivacyMask", sensitive).apply();
        }
        pollRemoteControlIfNeeded(prefs);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(controlLoop);
        io.shutdownNow();
        super.onDestroy();
    }

    private final Runnable controlLoop = new Runnable() {
        @Override
        public void run() {
            pollRemoteControlIfNeeded(getSharedPreferences(PREFS, MODE_PRIVATE));
            main.postDelayed(this, 300);
        }
    };

    private void pollRemoteControlIfNeeded(SharedPreferences prefs) {
        if (!prefs.getBoolean("remoteControlAllowed", false)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pollingControl || now - lastControlPollMs < 250) {
            return;
        }
        lastControlPollMs = now;
        pollingControl = true;
        String baseUrl = prefs.getString("baseUrl", "");
        String pairCode = prefs.getString("pairCode", "");
        String authToken = prefs.getString("authToken", "");
        if (baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
            pollingControl = false;
            return;
        }
        io.execute(() -> {
            try {
                String pair = URLEncoder.encode(pairCode, StandardCharsets.UTF_8.name());
                String token = URLEncoder.encode(authToken, StandardCharsets.UTF_8.name());
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/control/action?pairCode=" + pair + "&authToken=" + token);
                JSONObject action = result.optJSONObject("action");
                if (action != null) {
                    handleControlAction(action);
                }
            } catch (Exception ignored) {
            } finally {
                pollingControl = false;
            }
        });
    }

    private void handleControlAction(JSONObject action) {
        String type = action.optString("type");
        if ("tap".equals(type)) {
            float x = (float) action.optDouble("x", 0.5);
            float y = (float) action.optDouble("y", 0.5);
            main.post(() -> performTap(x, y));
        } else if ("swipe".equals(type)) {
            float startX = (float) action.optDouble("startX", 0.5);
            float startY = (float) action.optDouble("startY", 0.8);
            float endX = (float) action.optDouble("endX", 0.5);
            float endY = (float) action.optDouble("endY", 0.2);
            long durationMs = Math.max(180, Math.min(800, action.optLong("durationMs", 350)));
            main.post(() -> performSwipe(startX, startY, endX, endY, durationMs));
        } else if ("global".equals(type)) {
            String globalAction = action.optString("action");
            main.post(() -> performGlobal(globalAction));
        }
    }

    private void performTap(float normalizedX, float normalizedY) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        float x = Math.max(1f, Math.min(metrics.widthPixels - 1f, normalizedX * metrics.widthPixels));
        float y = Math.max(1f, Math.min(metrics.heightPixels - 1f, normalizedY * metrics.heightPixels));
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private void performSwipe(float normalizedStartX, float normalizedStartY, float normalizedEndX, float normalizedEndY, long durationMs) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        DisplayMetrics metrics = displayMetrics();
        if (metrics == null) {
            return;
        }
        float startX = clamp(normalizedStartX * metrics.widthPixels, 1f, metrics.widthPixels - 1f);
        float startY = clamp(normalizedStartY * metrics.heightPixels, 1f, metrics.heightPixels - 1f);
        float endX = clamp(normalizedEndX * metrics.widthPixels, 1f, metrics.widthPixels - 1f);
        float endY = clamp(normalizedEndY * metrics.heightPixels, 1f, metrics.heightPixels - 1f);
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private void performGlobal(String action) {
        if ("home".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if ("back".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else if ("recents".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
    }

    private DisplayMetrics displayMetrics() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return null;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isSensitivePackage(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return false;
        }
        String value = pkg.toString().toLowerCase(Locale.ROOT);
        return value.contains("alipay")
                || value.contains("bank")
                || value.contains("wallet")
                || value.contains("tenpay")
                || value.contains("cmb")
                || value.contains("icbc")
                || value.contains("ccb")
                || value.contains("boc");
    }

    private boolean containsSensitiveText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 5) {
            return false;
        }
        if (isSensitiveText(node.getText()) || isSensitiveText(node.getContentDescription())) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsSensitiveText(node.getChild(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitiveText(CharSequence text) {
        if (text == null) {
            return false;
        }
        String value = text.toString();
        return value.contains("验证码")
                || value.contains("支付密码")
                || value.contains("转账")
                || value.contains("银行卡")
                || value.contains("身份证")
                || value.contains("人脸识别")
                || value.contains("贷款")
                || value.contains("免密支付")
                || value.toLowerCase(Locale.ROOT).contains("password");
    }
}
