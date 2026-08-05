package com.qinqing.bangbang;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    private static final int NOTIFICATION_CONTROL_REQUEST = 3001;
    private static final String PREFS = "family-assist";
    private static final String CHANNEL_CONTROL = "control_requests";
    private static final String DEFAULT_RELAY_URL = "https://super-duper-funicular-44776x6g7hjvwj-8787.app.github.dev";
    private static final String DEFAULT_PAIR_CODE = "family001";
    private static final long FRESH_FRAME_MS = 2500;
    private static final long FAMILY_WAIT_POLL_MS = 1000;
    private static final long FAMILY_ACTIVE_POLL_MS = 520;
    private static final long ELDER_STATUS_POLL_MS = 900;
    private static final long HELP_CONNECT_TIMEOUT_MS = 90_000;
    private static final long ACTION_BUTTON_RESET_MS = 1800;
    private static final long ANNOTATION_THROTTLE_MS = 850;
    private static final boolean USE_WEBRTC = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService statusIo = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaIo = Executors.newSingleThreadExecutor();

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
    private boolean elderBindPolling;
    private boolean elderScreenVisible;
    private boolean refreshElderOnResume;
    private boolean remotePromptShowing;
    private boolean appInForeground;
    private boolean familyPollInFlight;
    private boolean familyFrameInFlight;
    private boolean elderStatusInFlight;
    private boolean familyControlAllowed;
    private boolean rtcVideoReady;
    private boolean familyLastActive;
    private boolean captureRequestInProgress;
    private boolean inviteInProgress;
    private boolean bindInProgress;
    private boolean remoteRequestInProgress;
    private long lastAnnotationSentAtMs;
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
        createControlNotificationChannel();
        showSetup();
    }

    @Override
    protected void onDestroy() {
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        stopFamilyWebRtc();
        statusIo.shutdownNow();
        mediaIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInForeground = true;
        String pendingControl = prefs.getString("pendingControlRequestAt", "");
        if (!pendingControl.isEmpty()) {
            prefs.edit().remove("pendingControlRequestAt").apply();
            main.postDelayed(() -> showRemoteControlPrompt(pendingControl), 250);
        }
        if (refreshElderOnResume && elderScreenVisible) {
            refreshElderOnResume = false;
            main.postDelayed(this::showElder, 200);
        }
    }

    @Override
    protected void onPause() {
        appInForeground = false;
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        captureRequestInProgress = false;
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            prefs.edit()
                    .putBoolean("assistActive", true)
                    .putLong("assistStartedAtMs", System.currentTimeMillis())
                    .apply();
            ensureOverlayReady();
            startCaptureService(resultCode, data);
            publishHelpRequest();
            showElder();
            setStatus("协助已开始，家属正在连接。需要结束时点“停止协助”。");
        } else if (requestCode == REQUEST_CAPTURE) {
            prefs.edit().putBoolean("assistActive", false).apply();
            endHelpRequest();
            setStatus("你取消了屏幕共享授权。");
        }
    }

    private void showSetup() {
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
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
        elderBindPolling = false;
        elderScreenVisible = false;
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
        elderBindPolling = false;
        elderAnnotationPolling = true;
        elderScreenVisible = true;
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
                createInvite(inviteButton);
            });
            backButton.setOnClickListener(v -> {
                elderAnnotationPolling = false;
                elderScreenVisible = false;
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

        if (!prefs.getBoolean("familyBound", false)) {
            showElderInvite(prefs.getString("pendingInviteCode", ""));
            return;
        }

        boolean assisting = prefs.getBoolean("assistActive", false);
        Button helpButton = primaryButton(elderPrimaryButtonText());
        helpButton.setTextSize(24);
        helpButton.setEnabled(!assisting);
        helpButton.setAlpha(assisting ? 0.62f : 1f);
        Button stopButton = dangerButton("停止协助");
        Button safetyButton = secondaryButton("更多设置");
        Button backButton = secondaryButton("返回首页");

        helpButton.setOnClickListener(v -> handleElderPrimaryAction());
        stopButton.setOnClickListener(v -> stopAssistance("已停止协助。"));
        safetyButton.setOnClickListener(v -> showSafetySettings());
        backButton.setOnClickListener(v -> {
            elderAnnotationPolling = false;
            elderScreenVisible = false;
            showSetup();
        });

        root.addView(helpButton);

        LinearLayout stepsCard = card(assisting ? "协助进行中" : "现在只要做一件事", elderAssistHintText());
        root.addView(stepsCard);

        root.addView(status);
        if (assisting) {
            root.addView(stopButton);
        }
        root.addView(safetyButton);
        if (!assisting) {
            root.addView(backButton);
        }
        setContentView(scroll(root));
        pollElderAnnotationLoop();
    }

    private void showElderInvite(String inviteCode) {
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = true;
        elderScreenVisible = true;
        root = verticalRoot();
        root.addView(hero("绑定家属", "请把下面号码告诉家属"));
        root.addView(statusPill("绑定状态：等待家属输入绑定码。"));
        status = notice("家属绑定成功后，这里会自动变成“可以开始协助”。");

        TextView codeView = title(inviteCode == null || inviteCode.isEmpty() ? "------" : inviteCode);
        codeView.setTextSize(44);
        codeView.setTextColor(COLOR_BLUE_DARK);
        codeView.setPadding(0, dp(12), 0, dp(12));

        Button regenerateButton = secondaryButton("重新生成绑定码");
        Button backButton = secondaryButton("返回首页");
        regenerateButton.setOnClickListener(v -> createInvite(regenerateButton));
        backButton.setOnClickListener(v -> {
            elderBindPolling = false;
            elderScreenVisible = false;
            showSetup();
        });

        LinearLayout bindCard = card("让家属完成绑定", "请家属打开“我是家属”，输入这个 6 位号码。");
        bindCard.addView(codeView);
        bindCard.addView(regenerateButton);
        root.addView(bindCard);
        root.addView(status);
        root.addView(backButton);
        setContentView(scroll(root));
        pollElderBindLoop();
    }

    private void showSafetySettings() {
        familyPolling = false;
        elderBindPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("安全设置", "需要时再调整，平时不用管"));
        root.addView(statusPill(bindingStatusText()));
        status = notice("敏感保护和远程点击需要开启辅助服务；画圈提示需要允许显示在其他应用上层。");

        Button privacyButton = secondaryButton(sensitiveButtonText());
        Button overlayButton = secondaryButton(annotationButtonText());
        Button controlButton = secondaryButton(remoteControlButtonText());
        Button accessibilityButton = secondaryButton(accessibilityButtonText());
        Button backButton = primaryButton("返回长辈模式");

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
        accessibilityButton.setOnClickListener(v -> {
            refreshElderOnResume = true;
            elderScreenVisible = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        backButton.setOnClickListener(v -> showElder());

        LinearLayout safetyCard = card("权限与保护", "这些设置不影响普通求助。远程点击必须长辈明确允许。");
        safetyCard.addView(privacyButton);
        safetyCard.addView(overlayButton);
        safetyCard.addView(controlButton);
        safetyCard.addView(accessibilityButton);
        root.addView(safetyCard);
        root.addView(status);
        root.addView(backButton);
        setContentView(scroll(root));
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
        status = notice("正在等待长辈发起协助。看到画面后，点需要长辈点击的位置。");
        View screenView = USE_WEBRTC ? buildRtcView() : buildFrameView();

        Button controlRequestButton = secondaryButton("请求远程点击授权");
        Button refreshButton = primaryButton("立即刷新");
        Button backButton = secondaryButton("返回首页");
        controlRequestButton.setOnClickListener(v -> requestRemoteControl(controlRequestButton));
        refreshButton.setOnClickListener(v -> {
            if (!refreshButton.isEnabled()) {
                return;
            }
            temporarilyDisable(refreshButton, "刷新中...");
            pollFamilyOnce();
        });
        backButton.setOnClickListener(v -> {
            familyPolling = false;
            stopFamilyWebRtc();
            showSetup();
        });

        LinearLayout screenCard = card(USE_WEBRTC ? "长辈实时屏幕" : "长辈屏幕", "点画面位置会发送红圈提示；长辈授权后，也会自动帮长辈点击。");
        screenCard.addView(screenView);
        root.addView(screenCard);
        root.addView(status);
        root.addView(controlRequestButton);
        root.addView(refreshButton);
        root.addView(backButton);
        setContentView(scroll(root));
        if (USE_WEBRTC) {
            startFamilyWebRtc();
        }
        pollFamilyLoop();
    }

    private View buildFrameView() {
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
                long age = System.currentTimeMillis() - lastFrameReceivedAtMs;
                if (lastFrameReceivedAtMs == 0 || age > FRESH_FRAME_MS) {
                    setStatus("屏幕画面正在刷新，请等画面稳定后再点。");
                    pollFamilyOnce();
                    return true;
                }
                float[] point = normalizedImagePoint(frameView, event.getX(), event.getY());
                sendAnnotation(point[0], point[1]);
                if (familyControlAllowed) {
                    sendRemoteTap(point[0], point[1]);
                }
                return true;
            }
            return true;
        });
        return frameView;
    }

    private View buildRtcView() {
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
                if (!rtcVideoReady) {
                    setStatus("实时画面正在连接，请等画面出现后再点。");
                    return true;
                }
                float[] point = normalizedViewPoint(rtcView, event.getX(), event.getY());
                sendAnnotation(point[0], point[1]);
                if (familyControlAllowed) {
                    sendRemoteTap(point[0], point[1]);
                }
                return true;
            }
            return true;
        });
        return rtcView;
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
            bindFamily(inviteInput.getText().toString().trim(), bindButton);
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
        if (captureRequestInProgress) {
            setStatus("正在打开屏幕共享授权，请稍等。");
            return;
        }
        if (!ensureBound("长辈")) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        if (!ensureOverlayReady()) {
            return;
        }
        captureRequestInProgress = true;
        setStatus("正在打开屏幕共享授权...");
        requestScreenCapturePermission();
    }

    private void publishHelpRequest() {
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("masked", isPrivacyMasked());
                NetworkClient.postJson(baseUrl, "/api/help", payload);
            } catch (Exception e) {
                main.post(() -> setStatus("发送失败：" + e.getMessage()));
            }
        });
    }

    private void createInvite(Button sourceButton) {
        if (inviteInProgress) {
            return;
        }
        inviteInProgress = true;
        setButtonBusy(sourceButton, "生成中...");
        setStatus("正在生成亲属绑定码...");
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("deviceId", deviceId);
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/invite", payload);
                authToken = result.optString("authToken", "");
                memberRole = "elder";
                String inviteCode = result.optString("inviteCode", "");
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("memberRole", memberRole)
                        .putString("pendingInviteCode", inviteCode)
                        .putBoolean("familyBound", false)
                        .apply();
                main.post(() -> showElderInvite(inviteCode));
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("生成失败：" + e.getMessage());
                });
            } finally {
                inviteInProgress = false;
            }
        });
    }

    private void bindFamily(String inviteCode, Button sourceButton) {
        if (bindInProgress) {
            return;
        }
        if (inviteCode.isEmpty()) {
            setStatus("请先输入长辈给你的 6 位绑定码。");
            return;
        }
        bindInProgress = true;
        setButtonBusy(sourceButton, "绑定中...");
        setStatus("正在绑定长辈...");
        statusIo.execute(() -> {
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
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("绑定失败：" + e.getMessage());
                });
            } finally {
                bindInProgress = false;
            }
        });
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void startCaptureService(int resultCode, Intent data) {
        Intent intent = USE_WEBRTC ? new Intent(this, WebRtcScreenService.class) : new Intent(this, CaptureService.class);
        if (USE_WEBRTC) {
            intent.putExtra(WebRtcScreenService.EXTRA_BASE_URL, baseUrl);
            intent.putExtra(WebRtcScreenService.EXTRA_PAIR_CODE, pairCode);
            intent.putExtra(WebRtcScreenService.EXTRA_AUTH_TOKEN, authToken);
            intent.putExtra(WebRtcScreenService.EXTRA_RESULT_DATA, data);
        } else {
            intent.putExtra(CaptureService.EXTRA_BASE_URL, baseUrl);
            intent.putExtra(CaptureService.EXTRA_PAIR_CODE, pairCode);
            intent.putExtra(CaptureService.EXTRA_AUTH_TOKEN, authToken);
            intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void endHelpRequest() {
        prefs.edit().putBoolean("remoteControlAllowed", false).apply();
        statusIo.execute(() -> {
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
        main.postDelayed(this::pollFamilyLoop, familyLastActive ? FAMILY_ACTIVE_POLL_MS : FAMILY_WAIT_POLL_MS);
    }

    private void pollFamilyOnce() {
        if (familyPollInFlight) {
            return;
        }
        familyPollInFlight = true;
        statusIo.execute(() -> {
            try {
                String encoded = encoded(pairCode);
                String token = encoded(authToken);
                JSONObject help = NetworkClient.getJson(baseUrl, "/api/help?pairCode=" + encoded + "&authToken=" + token);
                boolean active = help.optBoolean("active", false);
                familyLastActive = active;
                familyControlAllowed = help.optBoolean("controlAllowed", false);
                if (!active) {
                    main.post(() -> setStatus("正在等待协助请求..."));
                    return;
                }
                String elderName = help.optString("elderName", "长辈");
                String updatedAt = help.optString("updatedAt", "");
                String frameUpdatedAt = help.optString("frameUpdatedAt", "");
                main.post(() -> {
                    String controlText = familyControlAllowed ? "远程点击已授权。" : "未授权远程点击。";
                    if (lastFrameReceivedAtMs == 0) {
                        setStatus(elderName + " 已开始协助，正在接收第一张画面。" + controlText);
                    } else {
                        setStatus(elderName + " 正在协助中。" + controlText + " 最后更新：" + updatedAt);
                    }
                });
                if (!USE_WEBRTC) {
                    requestLatestFrame(encoded, token, frameUpdatedAt);
                }
            } catch (Exception e) {
                familyLastActive = false;
                main.post(() -> setStatus("连接 relay 失败：" + e.getMessage()));
            } finally {
                familyPollInFlight = false;
            }
        });
    }

    private void requestLatestFrame(String encodedPair, String encodedToken, String frameUpdatedAt) {
        if (familyFrameInFlight || frameUpdatedAt.isEmpty() || frameUpdatedAt.equals(lastFrameUpdatedAt)) {
            return;
        }
        familyFrameInFlight = true;
        mediaIo.execute(() -> {
            try {
                Bitmap bitmap = NetworkClient.getJpeg(baseUrl, "/api/frame?pairCode=" + encodedPair + "&authToken=" + encodedToken + "&t=" + System.currentTimeMillis());
                main.post(() -> {
                    if (bitmap != null && frameView != null) {
                        lastFrameUpdatedAt = frameUpdatedAt;
                        lastFrameReceivedAtMs = System.currentTimeMillis();
                        frameView.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception e) {
                main.post(() -> setStatus("画面接收较慢，正在重试：" + e.getMessage()));
            } finally {
                familyFrameInFlight = false;
            }
        });
    }

    private void sendAnnotation(float x, float y) {
        long now = System.currentTimeMillis();
        if (now - lastAnnotationSentAtMs < ANNOTATION_THROTTLE_MS) {
            setStatus("画圈提示已发送，请稍等一下。");
            return;
        }
        lastAnnotationSentAtMs = now;
        setStatus("正在发送画圈提示...");
        statusIo.execute(() -> {
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

    private void requestRemoteControl(Button sourceButton) {
        if (remoteRequestInProgress) {
            return;
        }
        remoteRequestInProgress = true;
        setButtonBusy(sourceButton, "已请求，等待长辈...");
        setStatus("正在向长辈请求远程点击授权...");
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken);
                NetworkClient.postJson(baseUrl, "/api/control/request", payload);
                main.post(() -> setStatus("已请求长辈授权。长辈同意后，你点击截图就能直接帮忙点击。"));
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("请求远程点击失败：" + e.getMessage());
                });
            } finally {
                remoteRequestInProgress = false;
                main.postDelayed(() -> restoreButton(sourceButton), 5000);
            }
        });
    }

    private void sendRemoteTap(float x, float y) {
        statusIo.execute(() -> {
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
        pollElderStatusOnce();
        main.postDelayed(this::pollElderAnnotationLoop, ELDER_STATUS_POLL_MS);
    }

    private void pollElderBindLoop() {
        if (!elderBindPolling || authToken.isEmpty()) {
            return;
        }
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                if (family != null && !family.optBoolean("invitePending", true)) {
                    prefs.edit()
                            .putBoolean("familyBound", true)
                            .remove("pendingInviteCode")
                            .apply();
                    main.post(() -> {
                        elderBindPolling = false;
                        showElder();
                        setStatus("家属已绑定。现在点蓝色按钮就可以开始协助。");
                    });
                    return;
                }
                main.post(() -> setStatus("正在等待家属输入绑定码..."));
            } catch (Exception e) {
                main.post(() -> setStatus("正在等待家属绑定。网络检查失败：" + e.getMessage()));
            }
        });
        main.postDelayed(this::pollElderBindLoop, 1200);
    }

    private void pollElderAnnotationOnce() {
        if (authToken.isEmpty()) {
            return;
        }
        statusIo.execute(() -> {
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

    private void pollElderStatusOnce() {
        if (authToken.isEmpty() || elderStatusInFlight) {
            return;
        }
        elderStatusInFlight = true;
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                if (family == null) {
                    return;
                }
                handleAssistConnectionTimeout(family);
                if (family.optBoolean("controlRequested", false) && !prefs.getBoolean("remoteControlAllowed", false)) {
                    String updatedAt = family.optString("controlUpdatedAt", "");
                    String handledAt = prefs.getString("handledControlRequestAt", "");
                    if (!updatedAt.isEmpty() && !updatedAt.equals(handledAt)) {
                        main.post(() -> handleRemoteControlRequest(updatedAt));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                elderStatusInFlight = false;
            }
        });
    }

    private void handleAssistConnectionTimeout(JSONObject family) {
        if (!prefs.getBoolean("assistActive", false) || !family.optBoolean("active", false)) {
            return;
        }
        long startedAt = prefs.getLong("assistStartedAtMs", 0);
        long now = System.currentTimeMillis();
        if (startedAt <= 0 || now - startedAt < HELP_CONNECT_TIMEOUT_MS) {
            return;
        }
        long lastSeenAt = family.optLong("lastFamilySeenAtMs", 0);
        boolean neverConnected = lastSeenAt < startedAt;
        boolean disconnectedTooLong = lastSeenAt > 0 && now - lastSeenAt > HELP_CONNECT_TIMEOUT_MS;
        if (neverConnected || disconnectedTooLong) {
            main.post(() -> stopAssistance("家属长时间没有连接。本次协助已结束，需要时请重新点“开始协助”。"));
        }
    }

    private void stopAssistance(String message) {
        stopService(new Intent(this, CaptureService.class));
        stopService(new Intent(this, WebRtcScreenService.class));
        stopService(new Intent(this, AnnotationOverlayService.class));
        prefs.edit()
                .putBoolean("remoteControlAllowed", false)
                .putBoolean("assistActive", false)
                .remove("assistStartedAtMs")
                .apply();
        endHelpRequest();
        showElder();
        setStatus(message);
    }

    private void handleRemoteControlRequest(String updatedAt) {
        if (!appInForeground) {
            prefs.edit()
                    .putString("pendingControlRequestAt", updatedAt)
                    .putString("handledControlRequestAt", updatedAt)
                    .apply();
            showControlRequestNotification();
            return;
        }
        showRemoteControlPrompt(updatedAt);
    }

    private void showRemoteControlPrompt(String updatedAt) {
        if (remotePromptShowing || isFinishing()) {
            return;
        }
        remotePromptShowing = true;
        boolean accessibilityReady = isAccessibilityServiceEnabled();
        new AlertDialog.Builder(this)
                .setTitle("家属想远程帮你点击")
                .setMessage(accessibilityReady
                        ? "允许后，家属点屏幕时会直接帮你点。协助结束后会自动关闭。"
                        : "远程点击需要先开启辅助服务。不会开启也没关系，家属仍然可以用红圈提示你。")
                .setPositiveButton(accessibilityReady ? "允许本次协助" : "去开启辅助服务", (dialog, which) -> {
                    remotePromptShowing = false;
                    markControlRequestHandled(updatedAt);
                    if (accessibilityReady) {
                        allowRemoteControl(true);
                        showElder();
                    } else {
                        allowRemoteControl(false);
                        refreshElderOnResume = true;
                        elderScreenVisible = true;
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                })
                .setNegativeButton("暂不允许", (dialog, which) -> {
                    remotePromptShowing = false;
                    markControlRequestHandled(updatedAt);
                    allowRemoteControl(false);
                })
                .setOnCancelListener(dialog -> {
                    remotePromptShowing = false;
                    markControlRequestHandled(updatedAt);
                    allowRemoteControl(false);
                })
                .show();
    }

    private void markControlRequestHandled(String updatedAt) {
        prefs.edit()
                .putString("handledControlRequestAt", updatedAt)
                .remove("pendingControlRequestAt")
                .apply();
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

    private void handleElderPrimaryAction() {
        if (needsOverlayPermission()) {
            ensureOverlayPermission();
            return;
        }
        requestHelpAndCapture();
    }

    private boolean needsOverlayPermission() {
        return prefs.getBoolean("annotationAllowed", true)
                && Build.VERSION.SDK_INT >= 23
                && !Settings.canDrawOverlays(this);
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
            refreshElderOnResume = true;
            startActivity(intent);
            setStatus("请在系统页允许“显示在其他应用上层”，完成后按返回键回来。");
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
            showAccessibilityGuide();
            return;
        }
        prefs.edit().putBoolean("remoteControlAllowed", allowed).apply();
        setStatus(allowed ? "已允许家属远程点击。本次协助结束后会自动关闭。" : "已关闭家属远程点击。");
        statusIo.execute(() -> {
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

    private void showAccessibilityGuide() {
        new AlertDialog.Builder(this)
                .setTitle("需要开启辅助服务")
                .setMessage("接下来会打开系统设置。请找到“亲情帮帮”，打开开关，然后按返回键回到这里。不会开启时，可以先让家属用画圈提示帮你。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    refreshElderOnResume = true;
                    elderScreenVisible = true;
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                })
                .setNegativeButton("先不用", null)
                .show();
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
        if (!isAccessibilityServiceEnabled()) {
            return prefs.getBoolean("sensitiveDetectionEnabled", true) ? "敏感保护：待开启" : "敏感保护：已关闭";
        }
        return prefs.getBoolean("sensitiveDetectionEnabled", true) ? "敏感保护：已开启" : "敏感保护：已关闭";
    }

    private String annotationButtonText() {
        return prefs.getBoolean("annotationAllowed", true) ? "画圈提示：已允许" : "画圈提示：已关闭";
    }

    private String remoteControlButtonText() {
        return prefs.getBoolean("remoteControlAllowed", false) ? "远程点击：已允许" : "远程点击：需长辈授权";
    }

    private String accessibilityButtonText() {
        return isAccessibilityServiceEnabled() ? "辅助服务：已开启" : "可选：开启敏感保护和远程点击";
    }

    private String elderPrimaryButtonText() {
        if (prefs.getBoolean("assistActive", false)) {
            return "协助进行中";
        }
        if (needsOverlayPermission()) {
            return "允许画圈提示";
        }
        return "开始协助";
    }

    private String elderAssistHintText() {
        if (prefs.getBoolean("assistActive", false)) {
            return "家属正在连接或查看你的屏幕。请保持此页面或切到需要帮忙的 App；需要结束时点“停止协助”。";
        }
        if (needsOverlayPermission()) {
            return "第一次需要先允许家属的红圈提示。点蓝色按钮后，在系统页允许“显示在其他应用上层”，再按返回键回来。";
        }
        if (!isAccessibilityServiceEnabled()) {
            return "点蓝色按钮即可开始屏幕协助。敏感保护和远程点击需要辅助服务，可稍后由家属指导开启。";
        }
        return "点蓝色按钮，确认屏幕共享后，家属就能看到实时画面并帮你。";
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

    private void setButtonBusy(Button button, String busyText) {
        if (button == null) {
            return;
        }
        if (button.getTag() == null) {
            button.setTag(button.getText().toString());
        }
        button.setEnabled(false);
        button.setAlpha(0.62f);
        button.setText(busyText);
    }

    private void restoreButton(Button button) {
        if (button == null) {
            return;
        }
        Object original = button.getTag();
        if (original instanceof String) {
            button.setText((String) original);
            button.setTag(null);
        }
        button.setEnabled(true);
        button.setAlpha(1f);
    }

    private void temporarilyDisable(Button button, String busyText) {
        setButtonBusy(button, busyText);
        main.postDelayed(() -> restoreButton(button), ACTION_BUTTON_RESET_MS);
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

    private void createControlNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_CONTROL,
                "协助授权提醒",
                NotificationManager.IMPORTANCE_HIGH
        );
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void showControlRequestNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL_CONTROL)
                : new android.app.Notification.Builder(this);
        android.app.Notification notification = builder
                .setContentTitle("家属请求远程点击")
                .setContentText("点这里回到亲情帮帮，确认是否允许本次协助。")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_CONTROL_REQUEST, notification);
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
