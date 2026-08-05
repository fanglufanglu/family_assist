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
                if (action != null && "tap".equals(action.optString("type"))) {
                    float x = (float) action.optDouble("x", 0.5);
                    float y = (float) action.optDouble("y", 0.5);
                    main.post(() -> performTap(x, y));
                }
            } catch (Exception ignored) {
            } finally {
                pollingControl = false;
            }
        });
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

    private boolean isSensitivePackage(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return false;
        }
        String value = pkg.toString().toLowerCase();
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
                || value.toLowerCase().contains("password");
    }
}
