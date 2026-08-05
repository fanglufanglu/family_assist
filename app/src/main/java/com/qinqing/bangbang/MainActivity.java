package com.qinqing.bangbang;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 2001;
    private static final String PREFS = "family-assist";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private SharedPreferences prefs;
    private String baseUrl;
    private String pairCode;
    private String displayName;
    private String authToken;
    private String memberRole;
    private String deviceId;
    private TextView status;
    private ImageView frameView;
    private boolean familyPolling;
    private boolean elderAnnotationPolling;

    private static final int COLOR_BG = 0xFFFFFBF7;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_MUTED = 0xFF697386;
    private static final int COLOR_LINE = 0xFFE7E0D8;
    private static final int COLOR_BLUE = 0xFF2563EB;
    private static final int COLOR_BLUE_DARK = 0xFF1D4ED8;
    private static final int COLOR_GREEN = 0xFF12B981;
    private static final int COLOR_RED = 0xFFDC2626;
    private static final int COLOR_WARM = 0xFFFFF1E8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrl = prefs.getString("baseUrl", "http://192.168.1.10:8787");
        pairCode = prefs.getString("pairCode", "family001");
        displayName = prefs.getString("displayName", "妈妈");
        authToken = prefs.getString("authToken", "");
        memberRole = prefs.getString("memberRole", "");
        deviceId = prefs.getString("deviceId", "");
        if (deviceId.isEmpty()) {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        showSetup();
    }

    @Override
    protected void onDestroy() {
        familyPolling = false;
        elderAnnotationPolling = false;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            startCaptureService(resultCode, data);
            setStatus("已开始共享屏幕截图。需要停止时，点下面的红色按钮。");
        } else if (requestCode == REQUEST_CAPTURE) {
            setStatus("你取消了屏幕共享授权。");
        }
    }

    private void showSetup() {
        familyPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();

        root.addView(hero("亲情帮帮", "爸妈点一下，家人看屏幕帮忙"));
        root.addView(statusPill(bindingStatusText()));

        EditText serverInput = input("例如 https://xxxx-8787.app.github.dev", baseUrl);
        EditText codeInput = input("家庭码，例如 family001", pairCode);
        EditText nameInput = input("显示名称，例如 妈妈 / 女儿", displayName);
        EditText inviteInput = input("家属输入长辈给的 6 位绑定码", "");
        status = notice("第一次使用：两台手机填写同一个 Relay 地址和家庭码，然后完成亲属绑定。");

        Button inviteButton = primaryButton("生成长辈绑定码");
        Button bindButton = secondaryButton("绑定这位长辈");
        Button elderButton = primaryButton("我是长辈，需要家人帮忙");
        Button familyButton = secondaryButton("我是家属，去帮长辈");

        inviteButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            createInvite();
        });
        bindButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            bindFamily(inviteInput.getText().toString().trim());
        });
        elderButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            showElder();
        });
        familyButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            showFamily();
        });

        LinearLayout roleCard = card("先选择身份", "长辈只需要记住蓝色按钮；家属负责配置和绑定。");
        roleCard.addView(elderButton);
        roleCard.addView(familyButton);
        root.addView(roleCard);

        LinearLayout connectionCard = card("准备连接", "两台手机填写同一个 Relay 地址和家庭码。");
        connectionCard.addView(label("Relay 地址"));
        connectionCard.addView(serverInput);
        connectionCard.addView(label("家庭码"));
        connectionCard.addView(codeInput);
        connectionCard.addView(label("我的显示名称"));
        connectionCard.addView(nameInput);
        root.addView(connectionCard);

        LinearLayout bindCard = card("亲属绑定", "长辈生成 6 位码，家属输入后才能查看协助请求。");
        bindCard.addView(inviteButton);
        bindCard.addView(label("亲属绑定码"));
        bindCard.addView(inviteInput);
        bindCard.addView(bindButton);
        root.addView(bindCard);

        root.addView(status);
        setContentView(scroll(root));
    }

    private void showElder() {
        familyPolling = false;
        elderAnnotationPolling = true;
        root = verticalRoot();
        root.addView(hero("长辈模式", "需要帮忙时，只点下面蓝色按钮"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("尚未发起协助。家属不能主动进入你的手机。");

        Button helpButton = primaryButton("找家人帮忙");
        helpButton.setTextSize(24);
        Button privacyButton = secondaryButton(privacyButtonText());
        Button overlayButton = secondaryButton("允许家属画圈提示");
        Button accessibilityButton = secondaryButton("开启敏感页面自动检测");
        Button stopButton = dangerButton("停止协助");
        Button backButton = secondaryButton("返回设置");

        helpButton.setOnClickListener(v -> requestHelpAndCapture());
        privacyButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("manualPrivacyMask", false);
            prefs.edit().putBoolean("manualPrivacyMask", next).apply();
            privacyButton.setText(privacyButtonText());
            setStatus(next ? "隐私遮罩已打开，家属端将看到保护画面。" : "隐私遮罩已关闭。");
        });
        overlayButton.setOnClickListener(v -> enableAnnotationOverlay());
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            stopService(new Intent(this, AnnotationOverlayService.class));
            endHelpRequest();
            setStatus("已停止协助。");
        });
        backButton.setOnClickListener(v -> {
            elderAnnotationPolling = false;
            showSetup();
        });

        root.addView(helpButton);

        LinearLayout stepsCard = card("接下来会发生什么", "1. 点“找家人帮忙”。\n2. 允许屏幕共享。\n3. 家属看到屏幕后，会用红圈告诉你点哪里。");
        root.addView(stepsCard);

        LinearLayout safetyCard = card("隐私保护", "遇到验证码、支付、银行卡页面时，家属端会看到保护画面。");
        safetyCard.addView(privacyButton);
        safetyCard.addView(accessibilityButton);
        safetyCard.addView(overlayButton);
        root.addView(safetyCard);

        root.addView(status);
        root.addView(stopButton);
        root.addView(backButton);
        setContentView(scroll(root));
        pollElderAnnotationLoop();
    }

    private void showFamily() {
        if (!ensureBound("家属")) {
            return;
        }
        familyPolling = true;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("家属模式", "看屏幕，点一下给长辈画圈"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("正在等待长辈发起协助。看到屏幕后，点需要长辈点击的位置。");
        frameView = new ImageView(this);
        frameView.setAdjustViewBounds(true);
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frameView.setBackground(rounded(0xFFF3F6FA, dp(18), COLOR_LINE));
        frameView.setPadding(dp(8), dp(8), dp(8), dp(8));
        frameView.setMinimumHeight(dp(320));
        frameView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        frameView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && frameView.getDrawable() != null) {
                float[] point = normalizedImagePoint(frameView, event.getX(), event.getY());
                float x = point[0];
                float y = point[1];
                sendAnnotation(x, y);
                return true;
            }
            return true;
        });

        Button refreshButton = primaryButton("立即刷新");
        Button backButton = secondaryButton("返回设置");
        refreshButton.setOnClickListener(v -> pollFamilyOnce());
        backButton.setOnClickListener(v -> {
            familyPolling = false;
            showSetup();
        });

        LinearLayout screenCard = card("长辈屏幕", "点截图上的位置发送“请点这里”的画圈提示。");
        screenCard.addView(frameView);
        root.addView(screenCard);
        root.addView(status);
        root.addView(refreshButton);
        root.addView(backButton);
        setContentView(scroll(root));
        pollFamilyLoop();
    }

    private void requestHelpAndCapture() {
        if (!ensureBound("长辈")) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        if (!ensureOverlayReady()) {
            return;
        }
        setStatus("正在发送协助请求...");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("masked", isPrivacyMasked());
                NetworkClient.postJson(baseUrl, "/api/help", payload);
                main.post(this::requestScreenCapturePermission);
            } catch (Exception e) {
                main.post(() -> setStatus("发送失败：" + e.getMessage()));
            }
        });
    }

    private void createInvite() {
        setStatus("正在生成亲属绑定码...");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("deviceId", deviceId);
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/invite", payload);
                authToken = result.optString("authToken", "");
                memberRole = "elder";
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("memberRole", memberRole)
                        .apply();
                String inviteCode = result.optString("inviteCode", "");
                main.post(() -> setStatus("绑定码：" + inviteCode + "。10 分钟内告诉家属输入。"));
            } catch (Exception e) {
                main.post(() -> setStatus("生成失败：" + e.getMessage()));
            }
        });
    }

    private void bindFamily(String inviteCode) {
        if (inviteCode.isEmpty()) {
            setStatus("请先输入长辈给你的 6 位绑定码。");
            return;
        }
        setStatus("正在绑定长辈...");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("inviteCode", inviteCode)
                        .put("familyName", displayName)
                        .put("deviceId", deviceId);
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/bind", payload);
                authToken = result.optString("authToken", "");
                memberRole = "family";
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("memberRole", memberRole)
                        .apply();
                main.post(() -> setStatus("绑定成功。现在可以进入家属模式等待协助。"));
            } catch (Exception e) {
                main.post(() -> setStatus("绑定失败：" + e.getMessage()));
            }
        });
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void startCaptureService(int resultCode, Intent data) {
        Intent intent = new Intent(this, CaptureService.class);
        intent.putExtra(CaptureService.EXTRA_BASE_URL, baseUrl);
        intent.putExtra(CaptureService.EXTRA_PAIR_CODE, pairCode);
        intent.putExtra(CaptureService.EXTRA_AUTH_TOKEN, authToken);
        intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void endHelpRequest() {
        io.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
            } catch (Exception ignored) {
            }
        });
    }

    private void pollFamilyLoop() {
        if (!familyPolling) {
            return;
        }
        pollFamilyOnce();
        main.postDelayed(this::pollFamilyLoop, 750);
    }

    private void pollFamilyOnce() {
        io.execute(() -> {
            try {
                String encoded = encoded(pairCode);
                String token = encoded(authToken);
                JSONObject help = NetworkClient.getJson(baseUrl, "/api/help?pairCode=" + encoded + "&authToken=" + token);
                boolean active = help.optBoolean("active", false);
                if (!active) {
                    main.post(() -> setStatus("正在等待协助请求..."));
                    return;
                }
                String elderName = help.optString("elderName", "长辈");
                String updatedAt = help.optString("updatedAt", "");
                Bitmap bitmap = NetworkClient.getJpeg(baseUrl, "/api/frame?pairCode=" + encoded + "&authToken=" + token + "&t=" + System.currentTimeMillis());
                main.post(() -> {
                    setStatus(elderName + " 正在请求协助。最后更新：" + updatedAt);
                    if (bitmap != null) {
                        frameView.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception e) {
                main.post(() -> setStatus("连接 relay 失败：" + e.getMessage()));
            }
        });
    }

    private void sendAnnotation(float x, float y) {
        setStatus("正在发送画圈提示...");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("x", x)
                        .put("y", y)
                        .put("radius", 0.08)
                        .put("label", "请点这里");
                NetworkClient.postJson(baseUrl, "/api/annotation", payload);
                main.post(() -> setStatus("画圈提示已发送。"));
            } catch (Exception e) {
                main.post(() -> setStatus("发送画圈失败：" + e.getMessage()));
            }
        });
    }

    private float[] normalizedImagePoint(ImageView imageView, float touchX, float touchY) {
        Drawable drawable = imageView.getDrawable();
        if (drawable == null) {
            return new float[]{0.5f, 0.5f};
        }
        int viewWidth = Math.max(1, imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight());
        int viewHeight = Math.max(1, imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom());
        int imageWidth = Math.max(1, drawable.getIntrinsicWidth());
        int imageHeight = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
        float displayedWidth = imageWidth * scale;
        float displayedHeight = imageHeight * scale;
        float left = imageView.getPaddingLeft() + (viewWidth - displayedWidth) / 2f;
        float top = imageView.getPaddingTop() + (viewHeight - displayedHeight) / 2f;
        float x = (touchX - left) / Math.max(1f, displayedWidth);
        float y = (touchY - top) / Math.max(1f, displayedHeight);
        return new float[]{
                Math.max(0f, Math.min(1f, x)),
                Math.max(0f, Math.min(1f, y))
        };
    }

    private void pollElderAnnotationLoop() {
        if (!elderAnnotationPolling) {
            return;
        }
        pollElderAnnotationOnce();
        main.postDelayed(this::pollElderAnnotationLoop, 750);
    }

    private void pollElderAnnotationOnce() {
        if (authToken.isEmpty()) {
            return;
        }
        io.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/annotation?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject annotation = result.optJSONObject("annotation");
                if (annotation != null) {
                    String label = annotation.optString("label", "请点这里");
                    main.post(() -> setStatus("家属提示：" + label + "。如果开启了画圈浮层，屏幕上会显示红圈。"));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void saveSetup(EditText serverInput, EditText codeInput, EditText nameInput) {
        baseUrl = NetworkClient.normalizeBaseUrl(serverInput.getText().toString());
        pairCode = codeInput.getText().toString().trim();
        displayName = nameInput.getText().toString().trim();
        if (displayName.isEmpty()) {
            displayName = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        prefs.edit()
                .putString("baseUrl", baseUrl)
                .putString("pairCode", pairCode)
                .putString("displayName", displayName)
                .apply();
    }

    private void enableAnnotationOverlay() {
        if (!ensureOverlayPermission()) {
            return;
        }
        startService(new Intent(this, AnnotationOverlayService.class));
        setStatus("画圈提示已开启。家属点屏幕后，你这里会出现红圈。");
    }

    private boolean ensureOverlayReady() {
        if (!ensureOverlayPermission()) {
            return false;
        }
        startService(new Intent(this, AnnotationOverlayService.class));
        return true;
    }

    private boolean ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
            setStatus("请先允许“显示在其他应用上层”。打开后回来再点“找家人帮忙”。");
            return false;
        }
        return true;
    }

    private boolean ensureBound(String expectedRoleLabel) {
        if (authToken.isEmpty()) {
            setStatus("请先完成亲属绑定。长辈生成绑定码，家属输入绑定码。");
            return false;
        }
        if ("长辈".equals(expectedRoleLabel) && !"elder".equals(memberRole)) {
            setStatus("这台设备当前是家属身份，请返回设置页重新生成长辈绑定码。");
            return false;
        }
        if ("家属".equals(expectedRoleLabel) && !"family".equals(memberRole)) {
            setStatus("这台设备当前是长辈身份，请家属端输入绑定码完成绑定。");
            return false;
        }
        return true;
    }

    private boolean isPrivacyMasked() {
        return prefs.getBoolean("manualPrivacyMask", false) || prefs.getBoolean("autoPrivacyMask", false);
    }

    private String privacyButtonText() {
        return prefs.getBoolean("manualPrivacyMask", false) ? "关闭隐私遮罩" : "打开隐私遮罩";
    }

    private String bindingStatusText() {
        if (authToken.isEmpty()) {
            return "绑定状态：未绑定。";
        }
        String role = "elder".equals(memberRole) ? "长辈" : "家属";
        return "绑定状态：已绑定为" + role + "。";
    }

    private String encoded(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private LinearLayout verticalRoot() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(22), dp(18), dp(28));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(COLOR_BG);
        return layout;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.addView(child);
        return scroll;
    }

    private LinearLayout hero(String heading, String subheading) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(18), dp(22), dp(18), dp(22));
        layout.setBackground(gradientHero());
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(14));
        layout.setLayoutParams(params);

        TextView logo = new TextView(this);
        logo.setText("亲");
        logo.setGravity(Gravity.CENTER);
        logo.setTextSize(24);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(COLOR_BLUE_DARK);
        logo.setBackground(rounded(0xFFFFFFFF, dp(20), 0x00FFFFFF));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        logoParams.setMargins(0, 0, 0, dp(12));
        layout.addView(logo, logoParams);

        TextView title = title(heading);
        title.setTextColor(0xFFFFFFFF);
        layout.addView(title);

        TextView body = body(subheading);
        body.setGravity(Gravity.CENTER);
        body.setTextColor(0xEFFFFFFF);
        body.setPadding(0, dp(2), 0, 0);
        layout.addView(body);
        return layout;
    }

    private LinearLayout card(String heading, String subheading) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(COLOR_SURFACE, dp(18), COLOR_LINE));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(14));
        layout.setLayoutParams(params);

        TextView title = sectionTitle(heading);
        TextView body = caption(subheading);
        layout.addView(title);
        layout.addView(body);
        return layout;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(28);
        view.setTextColor(COLOR_TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTextColor(COLOR_TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17);
        view.setTextColor(COLOR_MUTED);
        view.setLineSpacing(dp(2), 1.0f);
        view.setPadding(0, dp(4), 0, dp(10));
        return view;
    }

    private TextView caption(String text) {
        TextView view = body(text);
        view.setTextSize(14);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView notice(String text) {
        TextView view = body(text);
        view.setTextSize(15);
        view.setTextColor(0xFF4B5563);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(0xFFF8FAFC, dp(14), COLOR_LINE));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(12));
        view.setLayoutParams(params);
        return view;
    }

    private TextView statusPill(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_BLUE_DARK);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackground(rounded(0xFFEFF6FF, dp(999), 0xFFBFDBFE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        view.setLayoutParams(params);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_TEXT);
        view.setPadding(0, dp(8), 0, dp(6));
        return view;
    }

    private EditText input(String hint, String value) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setTextSize(16);
        view.setTextColor(COLOR_TEXT);
        view.setHintTextColor(0xFF9AA4B2);
        view.setSingleLine(true);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setBackground(rounded(0xFFF8FAFC, dp(12), COLOR_LINE));
        view.setLayoutParams(fullWidthParams());
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(rounded(COLOR_BLUE, dp(14), COLOR_BLUE));
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setLayoutParams(buttonParams());
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(COLOR_BLUE_DARK);
        button.setBackground(rounded(0xFFEFF6FF, dp(14), 0xFFBFDBFE));
        return button;
    }

    private Button dangerButton(String text) {
        Button button = primaryButton(text);
        button.setBackground(rounded(COLOR_RED, dp(14), COLOR_RED));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private GradientDrawable rounded(int fillColor, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        if (Color.alpha(strokeColor) != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private GradientDrawable gradientHero() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF7A59, 0xFFFF5F7E, 0xFF2F80ED}
        );
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
