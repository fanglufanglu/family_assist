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
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 2001;
    private static final String PREFS = "family-assist";
    private static final String DEFAULT_RELAY_URL = "https://super-duper-funicular-44776x6g7hjvwj-8787.app.github.dev";
    private static final String DEFAULT_PAIR_CODE = "family001";
    private static final long FRESH_FRAME_MS = 2500;

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
    private SurfaceViewRenderer rtcView;
    private WebRtcClient familyRtcClient;
    private boolean familyPolling;
    private boolean elderAnnotationPolling;
    private boolean familyPollInFlight;
    private boolean familyControlAllowed;
    private boolean rtcVideoReady;
    private long lastFrameReceivedAtMs;
    private String lastFrameUpdatedAt = "";

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
        configureSystemBars();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrl = migrateRelayUrl(prefs.getString("baseUrl", DEFAULT_RELAY_URL));
        pairCode = prefs.getString("pairCode", DEFAULT_PAIR_CODE);
        displayName = prefs.getString("displayName", "妈妈");
        authToken = prefs.getString("authToken", "");
        memberRole = prefs.getString("memberRole", "");
        deviceId = prefs.getString("deviceId", "");
        if (deviceId.isEmpty()) {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        seedDefaultSafetyPrefs();
        showSetup();
    }

    @Override
    protected void onDestroy() {
        familyPolling = false;
        elderAnnotationPolling = false;
        stopFamilyWebRtc();
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
        status = notice("请选择这台手机的身份。连接服务已自动配置，正常使用不需要填写地址。");

        Button elderButton = primaryButton("我是长辈");
        elderButton.setTextSize(23);
        Button familyButton = secondaryButton("我是家属");
        familyButton.setTextSize(22);
        Button settingsButton = secondaryButton("连接设置");

        elderButton.setOnClickListener(v -> showElder());
        familyButton.setOnClickListener(v -> showFamily());
        settingsButton.setOnClickListener(v -> showAdvancedSettings());

        LinearLayout elderCard = card("长辈手机", "用于发起求助。需要帮忙时，只点一个大按钮。");
        elderCard.addView(elderButton);
        root.addView(elderCard);

        LinearLayout familyCard = card("家属手机", "用于接收求助、查看屏幕，并给长辈画圈提示。");
        familyCard.addView(familyButton);
        root.addView(familyCard);

        LinearLayout safetyCard = card("使用规则", "必须先完成亲属绑定；长辈发起协助后，家属才能看到屏幕。");
        safetyCard.addView(settingsButton);
        root.addView(safetyCard);
        root.addView(status);
        setContentView(scroll(root));
    }

    private void showAdvancedSettings() {
        familyPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("连接设置", "仅用于测试环境或服务地址变更"));
        status = notice("当前已自动使用临时 Relay。正式版本会由后台自动分配连接服务。");

        EditText serverInput = input("Relay 地址", baseUrl);
        EditText codeInput = input("家庭码", pairCode);
        EditText nameInput = input("我的显示名称", displayName);
        Button saveButton = primaryButton("保存设置");
        Button backButton = secondaryButton("返回首页");

        saveButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            setStatus("设置已保存。");
        });
        backButton.setOnClickListener(v -> showSetup());

        LinearLayout connectionCard = card("测试连接", "如果 Codespaces 地址变化，可在这里临时更新。");
        connectionCard.addView(label("Relay 地址"));
        connectionCard.addView(serverInput);
        connectionCard.addView(label("家庭码"));
        connectionCard.addView(codeInput);
        connectionCard.addView(label("显示名称"));
        connectionCard.addView(nameInput);
        connectionCard.addView(saveButton);
        root.addView(connectionCard);
        root.addView(status);
        root.addView(backButton);
        setContentView(scroll(root));
    }

    private void showElder() {
        familyPolling = false;
        elderAnnotationPolling = true;
        root = verticalRoot();
        root.addView(hero("长辈模式", "需要帮忙时，只点下面蓝色按钮"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("家属不能主动进入你的手机。只有你点“找家人帮忙”后，家属才能看到屏幕。");

        if (!isBoundAs("elder")) {
            EditText nameInput = input("长辈名称，例如 妈妈", displayName);
            Button inviteButton = primaryButton("生成亲属绑定码");
            Button backButton = secondaryButton("返回首页");
            inviteButton.setOnClickListener(v -> {
                displayName = nameInput.getText().toString().trim();
                if (displayName.isEmpty()) {
                    displayName = "长辈";
                }
                prefs.edit().putString("displayName", displayName).apply();
                createInvite();
            });
            backButton.setOnClickListener(v -> {
                elderAnnotationPolling = false;
                showSetup();
            });

            LinearLayout bindCard = card("第一步：绑定家属", "点下面按钮生成 6 位码，把它告诉家属。绑定后再发起协助。");
            bindCard.addView(label("我的称呼"));
            bindCard.addView(nameInput);
            bindCard.addView(inviteButton);
            root.addView(bindCard);
            root.addView(status);
            root.addView(backButton);
            setContentView(scroll(root));
            return;
        }

        Button helpButton = primaryButton("开始协助");
        helpButton.setTextSize(24);
        Button privacyButton = secondaryButton(sensitiveButtonText());
        Button overlayButton = secondaryButton(annotationButtonText());
        Button controlButton = secondaryButton(remoteControlButtonText());
        Button accessibilityButton = secondaryButton("首次准备：打开辅助服务");
        Button stopButton = dangerButton("停止协助");
        Button backButton = secondaryButton("返回首页");

        helpButton.setOnClickListener(v -> requestHelpAndCapture());
        privacyButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("sensitiveDetectionEnabled", true);
            prefs.edit().putBoolean("sensitiveDetectionEnabled", next).apply();
            privacyButton.setText(sensitiveButtonText());
            setStatus(next ? "敏感页面保护已开启。" : "敏感页面保护已关闭。");
        });
        overlayButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("annotationAllowed", true);
            prefs.edit().putBoolean("annotationAllowed", next).apply();
            overlayButton.setText(annotationButtonText());
            setStatus(next ? "家属画圈提示已允许。" : "家属画圈提示已关闭。");
        });
        controlButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("remoteControlAllowed", false);
            allowRemoteControl(next);
            main.postDelayed(() -> controlButton.setText(remoteControlButtonText()), 200);
        });
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            stopService(new Intent(this, WebRtcScreenService.class));
            stopService(new Intent(this, AnnotationOverlayService.class));
            prefs.edit().putBoolean("remoteControlAllowed", false).apply();
            endHelpRequest();
            setStatus("已停止协助。");
        });
        backButton.setOnClickListener(v -> {
            elderAnnotationPolling = false;
            showSetup();
        });

        root.addView(helpButton);

        LinearLayout stepsCard = card("现在只要做一件事", "点上面的蓝色按钮。第一次使用时，手机会带你完成必要授权；以后再求助会更快。");
        root.addView(stepsCard);

        LinearLayout safetyCard = card("安全选项", "敏感页面保护和家属画圈默认开启；远程点击必须由长辈明确授权。");
        safetyCard.addView(privacyButton);
        safetyCard.addView(overlayButton);
        safetyCard.addView(controlButton);
        safetyCard.addView(accessibilityButton);
        root.addView(safetyCard);

        root.addView(status);
        root.addView(stopButton);
        root.addView(backButton);
        setContentView(scroll(root));
        pollElderAnnotationLoop();
    }

    private void showFamily() {
        if (!isBoundAs("family")) {
            showFamilyBind();
            return;
        }
        familyPolling = true;
        elderAnnotationPolling = false;
        familyControlAllowed = false;
        rtcVideoReady = false;
        lastFrameReceivedAtMs = 0;
        lastFrameUpdatedAt = "";
        root = verticalRoot();
        root.addView(hero("家属模式", "看屏幕，点一下提示长辈"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("正在等待长辈发起协助。实时画面出现后，点需要长辈点击的位置。");
        rtcView = new SurfaceViewRenderer(this);
        rtcView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        rtcView.setMirror(false);
        rtcView.setEnableHardwareScaler(true);
        rtcView.setBackgroundColor(0xFF0F172A);
        rtcView.setMinimumHeight(dp(420));
        rtcView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(460)
        ));
        rtcView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                long age = System.currentTimeMillis() - lastFrameReceivedAtMs;
                if (!rtcVideoReady && (lastFrameReceivedAtMs == 0 || age > FRESH_FRAME_MS)) {
                    setStatus("实时画面正在连接，请等画面出现后再点。");
                    pollFamilyOnce();
                    return true;
                }
                float[] point = normalizedViewPoint(rtcView, event.getX(), event.getY());
                float x = point[0];
                float y = point[1];
                sendAnnotation(x, y);
                if (familyControlAllowed) {
                    sendRemoteTap(x, y);
                }
                return true;
            }
            return true;
        });

        Button controlRequestButton = secondaryButton("请求远程点击授权");
        Button refreshButton = primaryButton("立即刷新");
        Button backButton = secondaryButton("返回首页");
        controlRequestButton.setOnClickListener(v -> requestRemoteControl());
        refreshButton.setOnClickListener(v -> pollFamilyOnce());
        backButton.setOnClickListener(v -> {
            familyPolling = false;
            stopFamilyWebRtc();
            showSetup();
        });

        LinearLayout screenCard = card("长辈实时屏幕", "点画面位置会发送红圈提示；长辈授权后，也会自动帮长辈点击。");
        screenCard.addView(rtcView);
        root.addView(screenCard);
        root.addView(status);
        root.addView(controlRequestButton);
        root.addView(refreshButton);
        root.addView(backButton);
        setContentView(scroll(root));
        startFamilyWebRtc();
        pollFamilyLoop();
    }

    private void showFamilyBind() {
        familyPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("绑定长辈", "输入长辈手机上显示的 6 位码"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("绑定成功后，这台手机才能接收长辈的求助。");

        EditText nameInput = input("我的称呼，例如 女儿", displayName);
        EditText inviteInput = input("6 位绑定码", "");
        Button bindButton = primaryButton("绑定长辈");
        Button backButton = secondaryButton("返回首页");

        bindButton.setOnClickListener(v -> {
            displayName = nameInput.getText().toString().trim();
            if (displayName.isEmpty()) {
                displayName = "家属";
            }
            prefs.edit().putString("displayName", displayName).apply();
            bindFamily(inviteInput.getText().toString().trim());
        });
        backButton.setOnClickListener(v -> showSetup());

        LinearLayout bindCard = card("亲属绑定", "请让长辈打开“我是长辈”，生成绑定码后告诉你。");
        bindCard.addView(label("我的称呼"));
        bindCard.addView(nameInput);
        bindCard.addView(label("绑定码"));
        bindCard.addView(inviteInput);
        bindCard.addView(bindButton);
        root.addView(bindCard);
        root.addView(status);
        root.addView(backButton);
        setContentView(scroll(root));
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
        if (!ensureAccessibilityReady()) {
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
                main.post(() -> {
                    showFamily();
                    setStatus("绑定成功。正在等待长辈发起协助。");
                });
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
        Intent intent = new Intent(this, WebRtcScreenService.class);
        intent.putExtra(WebRtcScreenService.EXTRA_BASE_URL, baseUrl);
        intent.putExtra(WebRtcScreenService.EXTRA_PAIR_CODE, pairCode);
        intent.putExtra(WebRtcScreenService.EXTRA_AUTH_TOKEN, authToken);
        intent.putExtra(WebRtcScreenService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void endHelpRequest() {
        prefs.edit().putBoolean("remoteControlAllowed", false).apply();
        io.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
            } catch (Exception ignored) {
            }
        });
    }

    private void startFamilyWebRtc() {
        stopFamilyWebRtc();
        familyRtcClient = new WebRtcClient(this, baseUrl, pairCode, authToken, new WebRtcClient.Listener() {
            @Override
            public void onState(String text) {
                setStatus(text);
            }

            @Override
            public void onRemoteVideo(VideoTrack track) {
                rtcVideoReady = true;
                lastFrameReceivedAtMs = System.currentTimeMillis();
                if (rtcView != null) {
                    track.addSink(rtcView);
                }
                setStatus("实时画面已连接。点画面可以提示长辈。");
            }
        });
        if (rtcView != null) {
            rtcView.init(familyRtcClient.eglContext(), null);
        }
        familyRtcClient.startFamily();
    }

    private void stopFamilyWebRtc() {
        if (familyRtcClient != null) {
            familyRtcClient.stop();
            familyRtcClient = null;
        }
        if (rtcView != null) {
            rtcView.release();
            rtcView = null;
        }
        rtcVideoReady = false;
    }

    private void pollFamilyLoop() {
        if (!familyPolling) {
            return;
        }
        pollFamilyOnce();
        main.postDelayed(this::pollFamilyLoop, 300);
    }

    private void pollFamilyOnce() {
        if (familyPollInFlight) {
            return;
        }
        familyPollInFlight = true;
        io.execute(() -> {
            try {
                String encoded = encoded(pairCode);
                String token = encoded(authToken);
                JSONObject help = NetworkClient.getJson(baseUrl, "/api/help?pairCode=" + encoded + "&authToken=" + token);
                boolean active = help.optBoolean("active", false);
                familyControlAllowed = help.optBoolean("controlAllowed", false);
                if (!active) {
                    main.post(() -> setStatus("正在等待协助请求..."));
                    return;
                }
                String elderName = help.optString("elderName", "长辈");
                String updatedAt = help.optString("updatedAt", "");
                main.post(() -> {
                    String controlText = familyControlAllowed ? "远程点击已授权。" : "未授权远程点击。";
                    setStatus(elderName + " 正在请求协助。" + controlText + " 最后更新：" + updatedAt);
                    lastFrameUpdatedAt = updatedAt;
                });
            } catch (Exception e) {
                main.post(() -> setStatus("连接 relay 失败：" + e.getMessage()));
            } finally {
                familyPollInFlight = false;
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
                        .put("label", "请点这里")
                        .put("frameUpdatedAt", lastFrameUpdatedAt);
                NetworkClient.postJson(baseUrl, "/api/annotation", payload);
                main.post(() -> setStatus("画圈提示已发送，几秒后会自动消失。"));
            } catch (Exception e) {
                main.post(() -> setStatus("发送画圈失败：" + e.getMessage()));
            }
        });
    }

    private void requestRemoteControl() {
        setStatus("正在向长辈请求远程点击授权...");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken);
                NetworkClient.postJson(baseUrl, "/api/control/request", payload);
                main.post(() -> setStatus("已请求长辈授权。长辈同意后，你点击截图就能直接帮忙点击。"));
            } catch (Exception e) {
                main.post(() -> setStatus("请求远程点击失败：" + e.getMessage()));
            }
        });
    }

    private void sendRemoteTap(float x, float y) {
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("x", x)
                        .put("y", y);
                NetworkClient.postJson(baseUrl, "/api/control/tap", payload);
                main.post(() -> setStatus("已发送远程点击。"));
            } catch (Exception e) {
                main.post(() -> setStatus("远程点击失败：" + e.getMessage()));
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

    private float[] normalizedViewPoint(View view, float touchX, float touchY) {
        int width = Math.max(1, view.getWidth());
        int height = Math.max(1, view.getHeight());
        float x = touchX / width;
        float y = touchY / height;
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
        pollElderControlRequestOnce();
        main.postDelayed(this::pollElderAnnotationLoop, 500);
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

    private void pollElderControlRequestOnce() {
        if (authToken.isEmpty()) {
            return;
        }
        io.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                if (family != null && family.optBoolean("controlRequested", false) && !prefs.getBoolean("remoteControlAllowed", false)) {
                    main.post(() -> setStatus("家属请求远程点击。需要你点“允许家属远程点击”后，家属才能操作。"));
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
        if (!prefs.getBoolean("annotationAllowed", true)) {
            return true;
        }
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
            setStatus("首次准备：请允许“显示在其他应用上层”，回来后再点“开始协助”。");
            return false;
        }
        return true;
    }

    private boolean ensureAccessibilityReady() {
        if (!prefs.getBoolean("sensitiveDetectionEnabled", true) && !prefs.getBoolean("remoteControlAllowed", false)) {
            return true;
        }
        if (isAccessibilityServiceEnabled()) {
            return true;
        }
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        setStatus("首次准备：请开启“亲情帮帮”辅助服务，回来后再点“开始协助”。");
        return false;
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

    private boolean isBoundAs(String role) {
        return !authToken.isEmpty() && role.equals(memberRole);
    }

    private void seedDefaultSafetyPrefs() {
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("sensitiveDetectionEnabled")) {
            editor.putBoolean("sensitiveDetectionEnabled", true);
        }
        if (!prefs.contains("annotationAllowed")) {
            editor.putBoolean("annotationAllowed", true);
        }
        if (!prefs.contains("remoteControlAllowed")) {
            editor.putBoolean("remoteControlAllowed", false);
        }
        editor.apply();
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String serviceName = getPackageName() + "/" + SensitiveAccessibilityService.class.getName();
        return enabled.toLowerCase().contains(serviceName.toLowerCase());
    }

    private void allowRemoteControl(boolean allowed) {
        if (allowed && !isAccessibilityServiceEnabled()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            setStatus("请先开启“亲情帮帮”辅助服务，回来后再允许远程点击。");
            return;
        }
        prefs.edit().putBoolean("remoteControlAllowed", allowed).apply();
        setStatus(allowed ? "已允许家属远程点击。本次协助结束后会自动关闭。" : "已关闭家属远程点击。");
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("allowed", allowed);
                NetworkClient.postJson(baseUrl, "/api/control/allow", payload);
            } catch (Exception e) {
                main.post(() -> setStatus("同步远程点击授权失败：" + e.getMessage()));
            }
        });
    }

    private String migrateRelayUrl(String value) {
        String normalized = NetworkClient.normalizeBaseUrl(value);
        if (normalized.isEmpty()
                || normalized.contains("192.168.")
                || normalized.contains("10.0.2.2")
                || normalized.contains("127.0.0.1")
                || normalized.contains("localhost")) {
            normalized = DEFAULT_RELAY_URL;
        }
        prefs.edit().putString("baseUrl", normalized).apply();
        return normalized;
    }

    private boolean isPrivacyMasked() {
        return prefs.getBoolean("manualPrivacyMask", false)
                || (prefs.getBoolean("sensitiveDetectionEnabled", true) && prefs.getBoolean("autoPrivacyMask", false));
    }

    private String sensitiveButtonText() {
        return prefs.getBoolean("sensitiveDetectionEnabled", true) ? "敏感保护：已开启" : "敏感保护：已关闭";
    }

    private String annotationButtonText() {
        return prefs.getBoolean("annotationAllowed", true) ? "画圈提示：已允许" : "画圈提示：已关闭";
    }

    private String remoteControlButtonText() {
        return prefs.getBoolean("remoteControlAllowed", false) ? "远程点击：已允许" : "远程点击：需长辈授权";
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

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        if (Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        }
    }

    private LinearLayout verticalRoot() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(12), dp(18), dp(24));
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
