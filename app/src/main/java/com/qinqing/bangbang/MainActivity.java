package com.qinqing.bangbang;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.VideoFrame;
import org.webrtc.VideoTrack;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 2001;
    private static final int REQUEST_NOTIFICATIONS = 2002;
    private static final String PREFS = "family-assist";
    private static final String DEFAULT_RELAY_URL = "http://47.238.240.30";
    private static final String DEFAULT_PAIR_CODE = "family001";
    private static final long FRESH_FRAME_MS = 2500;
    private static final long FAMILY_WAIT_POLL_MS = 1000;
    private static final long FAMILY_ACTIVE_POLL_MS = 520;
    private static final long ELDER_STATUS_POLL_MS = 900;
    private static final long HELP_CONNECT_TIMEOUT_MS = 90_000;
    private static final long ACTION_BUTTON_RESET_MS = 1800;
    private static final long ANNOTATION_THROTTLE_MS = 850;
    private static final long RTC_STALE_FRAME_MS = 3200;
    private static final long RTC_FRAME_SAMPLE_MS = 180;
    private static final String PREF_ASSIST_SESSION_ID = "assistSessionId";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService statusIo = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaIo = Executors.newSingleThreadExecutor();
    private final ExecutorService videoAnalysisIo = Executors.newSingleThreadExecutor();
    private final Runnable familyPollLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!familyPolling) {
                return;
            }
            pollFamilyOnce();
            main.postDelayed(this, familyLastActive ? FAMILY_ACTIVE_POLL_MS : FAMILY_WAIT_POLL_MS);
        }
    };
    private final Runnable elderAnnotationLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!elderAnnotationPolling) {
                return;
            }
            pollElderAnnotationOnce();
            pollElderStatusOnce();
            main.postDelayed(this, ELDER_STATUS_POLL_MS);
        }
    };
    private final Runnable elderBindLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!elderBindPolling || authToken.isEmpty()) {
                return;
            }
            pollElderBindOnce();
            main.postDelayed(this, 1200);
        }
    };
    private final Runnable familyBindPendingLoopRunnable = new Runnable() {
        @Override
        public void run() {
            String pendingToken = prefs.getString("pendingBindToken", "");
            if (pendingToken.isEmpty()) {
                return;
            }
            pollFamilyBindPendingOnce();
            main.postDelayed(this, 1500);
        }
    };

    private LinearLayout root;
    private SharedPreferences prefs;
    private String baseUrl;
    private String pairCode;
    private String displayName;
    private String accountToken;
    private String accountPhone;
    private String authToken;
    private String memberRole;
    private String deviceId;
    private TextView status;
    private ImageView frameView;
    private WebRtcClient familyRtcClient;
    private boolean familyPolling;
    private boolean elderAnnotationPolling;
    private boolean elderBindPolling;
    private boolean elderScreenVisible;
    private boolean elderUiAssisting;
    private boolean refreshElderOnResume;
    private boolean remotePromptShowing;
    private boolean appInForeground;
    private boolean familyPollInFlight;
    private boolean familyFrameInFlight;
    private boolean elderStatusInFlight;
    private boolean familyControlAllowed;
    private Button familyControlRequestButton;
    private Button familyRemoteButton;
    private Button familyEndButton;
    private LinearLayout familyActionBar;
    private TextView familyScreenLabelView;
    private FrameLayout familyScreenSurface;
    private ImageButton familyFullscreenButton;
    private Dialog familyFullscreenDialog;
    private TextView familyFullscreenModeView;
    private Button familyFullscreenControlRequestButton;
    private Button familyFullscreenRemoteButton;
    private Button familyFullscreenEndButton;
    private LinearLayout familyWaitingView;
    private TextView familyWaitingTitle;
    private TextView familyWaitingCaption;
    private Button familyChangeBindingButton;
    private volatile boolean rtcVideoReady;
    private boolean rtcTrackAttached;
    private boolean familyMediaReady;
    private boolean assistEndPromptShowing;
    private boolean familyLastActive;
    private boolean elderInviteBoundShown;
    private String familyLastSessionId = "";
    private String currentPage = "home";
    private boolean captureRequestInProgress;
    private boolean resumeCaptureAfterNotificationPermission;
    private boolean resumeCaptureAfterNotificationSettings;
    private boolean inviteInProgress;
    private boolean bindInProgress;
    private boolean remoteRequestInProgress;
    private boolean familyEnding;
    private long lastAnnotationSentAtMs;
    private long lastFrameReceivedAtMs;
    private volatile long lastRtcFrameAtMs;
    private volatile long lastRtcSampleAtMs;
    private volatile boolean rtcFrameAnalysisInFlight;
    private volatile int rtcBlackSamples;
    private float screenTouchStartX;
    private float screenTouchStartY;
    private long screenTouchStartAtMs;
    private String lastFrameUpdatedAt = "";
    private final Runnable assistEndedUiLoop = new Runnable() {
        @Override
        public void run() {
            if (!appInForeground) {
                return;
            }
            maybeShowAssistEndedEvent();
            main.postDelayed(this, 700);
        }
    };

    private static final int COLOR_BG = 0xFFF7F9FC;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_MUTED = 0xFF64748B;
    private static final int COLOR_LINE = 0xFFDDE4EE;
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
        accountToken = prefs.getString("accountToken", "");
        accountPhone = prefs.getString("accountPhone", "");
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
        closeFamilyFullscreen();
        prefs.edit().putBoolean("appForeground", false).apply();
        main.removeCallbacks(assistEndedUiLoop);
        main.removeCallbacks(familyPollLoopRunnable);
        main.removeCallbacks(elderAnnotationLoopRunnable);
        main.removeCallbacks(elderBindLoopRunnable);
        main.removeCallbacks(familyBindPendingLoopRunnable);
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        stopFamilyWebRtc();
        statusIo.shutdownNow();
        mediaIo.shutdownNow();
        videoAnalysisIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInForeground = true;
        prefs.edit().putBoolean("appForeground", true).commit();
        main.removeCallbacks(assistEndedUiLoop);
        maybeShowAssistEndedEvent();
        main.postDelayed(assistEndedUiLoop, 700);
        String setupControl = prefs.getString("controlSetupRequestAt", "");
        if (!setupControl.isEmpty()) {
            prefs.edit().remove("controlSetupRequestAt").commit();
            if (isAccessibilityServiceEnabled()) {
                main.postDelayed(() -> showRemoteControlPrompt(setupControl), 250);
            } else {
                markControlRequestHandled(setupControl);
                allowRemoteControl(false, "accessibility_not_enabled");
                main.postDelayed(this::showAccessibilitySetupIncomplete, 250);
            }
        } else {
            String pendingControl = prefs.getString("pendingControlRequestAt", "");
            if (!pendingControl.isEmpty()) {
                prefs.edit().remove("pendingControlRequestAt").commit();
                main.postDelayed(() -> showRemoteControlPrompt(pendingControl), 250);
            }
        }
        if (refreshElderOnResume && elderScreenVisible) {
            refreshElderOnResume = false;
            main.postDelayed(this::showElder, 200);
        }
        if (resumeCaptureAfterNotificationSettings && areAssistNotificationsEnabled()) {
            resumeCaptureAfterNotificationSettings = false;
            main.postDelayed(this::requestHelpAndCapture, 250);
        }
    }

    @Override
    protected void onPause() {
        appInForeground = false;
        main.removeCallbacks(assistEndedUiLoop);
        prefs.edit().putBoolean("appForeground", false).commit();
        super.onPause();
    }

    @Override
    protected void onStop() {
        appInForeground = false;
        prefs.edit().putBoolean("appForeground", false).commit();
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (familyFullscreenDialog != null && familyFullscreenDialog.isShowing()) {
            applyFullscreenSystemUi(familyFullscreenDialog.getWindow());
        }
    }

    @Override
    public void onBackPressed() {
        if (familyFullscreenDialog != null && familyFullscreenDialog.isShowing()) {
            closeFamilyFullscreen();
        } else if ("home".equals(currentPage)) {
            super.onBackPressed();
        } else if ("settings".equals(currentPage) || "privacy".equals(currentPage) || "relatives".equals(currentPage) || "login".equals(currentPage)) {
            showProfile();
        } else if ("familyBindPending".equals(currentPage)) {
            showFamilyBind();
        } else if ("safety".equals(currentPage)) {
            showElder();
        } else {
            showSetup();
        }
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
            final Intent captureData = data;
            publishHelpRequest(() -> {
                startCaptureService(resultCode, captureData);
                showElder();
                setStatus("求助已发出，家人正在连接。需要结束时点“结束本次求助”。");
            });
        } else if (requestCode == REQUEST_CAPTURE) {
            prefs.edit().putBoolean("assistActive", false).apply();
            endHelpRequest();
            showElder();
            setStatus("你取消了屏幕共享授权。");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        boolean continueCapture = resumeCaptureAfterNotificationPermission;
        resumeCaptureAfterNotificationPermission = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (continueCapture) {
                requestHelpAndCapture();
            }
        } else {
            showNotificationPermissionDialog();
        }
    }

    private void showSetup() {
        currentPage = "home";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();

        root.addView(hero("亲情帮帮", "长辈发起求助，家人远程看屏幕帮忙"));
        status = notice(bindingStatusText());
        status.setVisibility(View.GONE);

        Button elderButton = primaryButton("我是长辈");
        elderButton.setTextSize(23);
        Button familyButton = secondaryButton("我是家属");
        familyButton.setTextSize(22);
        elderButton.setOnClickListener(v -> {
            if (ensureLoggedIn("elder")) showElder();
        });
        familyButton.setOnClickListener(v -> {
            if (ensureLoggedIn("family")) showFamily();
        });

        LinearLayout elderCard = card("长辈手机", "需要帮助时，向家人发起求助。");
        elderCard.addView(elderButton);
        root.addView(elderCard);

        LinearLayout familyCard = card("家属手机", "接收求助，查看屏幕并提供提示。");
        familyCard.addView(familyButton);
        root.addView(familyCard);

        if (!isLoggedIn()) {
            root.addView(card("账号保护", "请先用手机号登录。亲属绑定需要双方账号确认，避免别人只凭绑定码接入。"));
        }

        root.addView(status);
        root.addView(bottomNav("home"));
        setContentView(scroll(root));
    }

    private void showProfile() {
        currentPage = "profile";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();
        root.addView(hero("我的", "连接、安全与隐私"));
        status = notice("");
        status.setVisibility(View.GONE);

        Button connectionButton = secondaryButton("连接设置");
        Button accountButton = secondaryButton(isLoggedIn() ? "账号信息" : "手机号登录");
        Button relativesButton = secondaryButton("亲属管理");
        Button safetyButton = secondaryButton("安全与权限设置");
        Button privacyButton = secondaryButton("隐私政策");

        connectionButton.setOnClickListener(v -> showAdvancedSettings());
        accountButton.setOnClickListener(v -> {
            if (isLoggedIn()) {
                setStatus("已登录：" + accountPhone + "。如需换号，可在连接设置中清除应用数据后重新登录。");
            } else {
                showLogin("profile");
            }
        });
        relativesButton.setOnClickListener(v -> showRelativesManagement());
        safetyButton.setOnClickListener(v -> {
            if (isBoundAs("elder")) {
                showSafetySettings();
            } else {
                setStatus("安全与权限主要用于长辈手机。请在长辈手机上打开。");
            }
        });
        privacyButton.setOnClickListener(v -> showPrivacyPolicy());

        LinearLayout settingsCard = card("设置", "");
        settingsCard.addView(accountButton);
        settingsCard.addView(relativesButton);
        settingsCard.addView(connectionButton);
        settingsCard.addView(safetyButton);
        settingsCard.addView(privacyButton);
        root.addView(settingsCard);

        LinearLayout aboutCard = card("当前状态", "应用版本：" + appVersionText()
                + "\n" + bindingStatusText()
                + "\n实时模式：" + (isWebRtcEnabled() ? "已开启" : "已关闭")
                + "\n连接服务：已配置");
        root.addView(aboutCard);
        root.addView(status);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showLogin(String afterRole) {
        currentPage = "login";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(hero("手机号登录", "保护亲属绑定和远程协助"));
        status = notice("内测阶段请输入手机号和任意 4-6 位验证码。正式版会接入短信验证码。");

        EditText phoneInput = input("手机号", accountPhone);
        EditText codeInput = input("验证码", "");
        EditText nameInput = input("我的称呼，例如 妈妈 / 女儿", displayName);
        Button loginButton = primaryButton("登录并继续");
        Button backButton = secondaryButton("返回首页");

        loginButton.setOnClickListener(v -> loginAccount(
                phoneInput.getText().toString().trim(),
                codeInput.getText().toString().trim(),
                nameInput.getText().toString().trim(),
                afterRole,
                loginButton
        ));
        backButton.setOnClickListener(v -> showSetup());

        LinearLayout loginCard = card("账号登录", "");
        loginCard.addView(label("手机号"));
        loginCard.addView(phoneInput);
        loginCard.addView(label("验证码"));
        loginCard.addView(codeInput);
        loginCard.addView(label("称呼"));
        loginCard.addView(nameInput);
        loginCard.addView(loginButton);
        loginCard.addView(backButton);
        root.addView(loginCard);
        root.addView(status);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showAdvancedSettings() {
        currentPage = "settings";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();
        root.addView(pageHeader("连接设置", this::showProfile));
        status = notice("");
        status.setVisibility(View.GONE);

        EditText serverInput = input("服务地址", baseUrl);
        EditText nameInput = input("我的显示名称", displayName);
        Button saveButton = primaryButton("保存设置");
        Button testButton = secondaryButton("检查连接");
        Button webRtcButton = secondaryButton(webRtcButtonText());

        saveButton.setOnClickListener(v -> {
            saveSetup(serverInput, nameInput);
            setStatus("设置已保存。");
        });
        testButton.setOnClickListener(v -> {
            saveSetup(serverInput, nameInput);
            testRelayConnection(testButton);
        });
        webRtcButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("webRtcEnabled", false);
            prefs.edit().putBoolean("webRtcEnabled", next).apply();
            webRtcButton.setText(webRtcButtonText());
            setStatus(next
                    ? "实时画面已开启。连接异常时会自动使用备用画面。"
                    : "实时画面已关闭，当前使用备用画面。");
        });

        LinearLayout connectionCard = card("服务连接", "");
        connectionCard.addView(label("服务地址"));
        connectionCard.addView(serverInput);
        connectionCard.addView(label("显示名称"));
        connectionCard.addView(nameInput);
        connectionCard.addView(saveButton);
        connectionCard.addView(testButton);
        connectionCard.addView(webRtcButton);
        root.addView(connectionCard);
        root.addView(status);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showElder() {
        if (!ensureLoggedIn("elder")) {
            return;
        }
        currentPage = "elder";
        prefs.edit().putBoolean("elderPageVisible", true).apply();
        familyPolling = false;
        elderBindPolling = false;
        elderAnnotationPolling = true;
        elderScreenVisible = true;
        boolean assisting = prefs.getBoolean("assistActive", false);
        root = verticalRoot();
        root.addView(hero("长辈求助", assisting ? "家人正在查看你的屏幕" : "需要帮助时，向家人发起求助"));
        String pendingAssistMessage = prefs.getString("pendingAssistMessage", "");
        if (!pendingAssistMessage.isEmpty()) {
            prefs.edit().remove("pendingAssistMessage").apply();
        }
        status = notice(pendingAssistMessage);
        status.setVisibility(pendingAssistMessage.isEmpty() ? View.GONE : View.VISIBLE);

        if (!isBoundAs("elder")) {
            EditText nameInput = input("长辈名称，例如 妈妈", displayName);
            Button inviteButton = primaryButton("第 1 步：生成亲属绑定码");
            inviteButton.setOnClickListener(v -> {
                displayName = nameInput.getText().toString().trim();
                if (displayName.isEmpty()) {
                    displayName = "长辈";
                }
                prefs.edit().putString("displayName", displayName).apply();
                createInvite(inviteButton);
            });

            LinearLayout bindCard = card("先绑定家属", "只需要做一次。生成 6 位码后，把号码告诉家属。");
            bindCard.addView(label("我的称呼"));
            bindCard.addView(nameInput);
            bindCard.addView(inviteButton);
            root.addView(bindCard);
            root.addView(status);
            root.addView(bottomNav("elder"));
            setContentView(scroll(root));
            return;
        }

        if (!prefs.getBoolean("familyBound", false)) {
            showElderInvite(prefs.getString("pendingInviteCode", ""));
            return;
        }

        elderUiAssisting = assisting;
        Button helpButton = primaryButton(elderPrimaryButtonText());
        helpButton.setTextSize(24);
        Button stopButton = dangerButton("结束本次求助");
        Button safetyButton = secondaryButton("更多设置");

        helpButton.setOnClickListener(v -> {
            setButtonBusy(helpButton, needsOverlayPermission() ? "正在打开权限..." : "正在打开屏幕授权...");
            handleElderPrimaryAction();
        });
        stopButton.setOnClickListener(v -> stopAssistance("本次求助已结束。"));
        safetyButton.setOnClickListener(v -> showSafetySettings());

        LinearLayout stepsCard = card(assisting ? "家人正在帮你" : elderCurrentStepTitle(), elderAssistHintText());
        if (assisting) {
            root.addView(stepsCard);
            root.addView(stopButton);
        } else {
            root.addView(helpButton);
            root.addView(stepsCard);
            root.addView(status);
            root.addView(safetyButton);
        }
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
        pollElderAnnotationLoop();
    }

    private void showElderInvite(String inviteCode) {
        currentPage = "elderInvite";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        elderInviteBoundShown = false;
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = true;
        elderScreenVisible = true;
        root = verticalRoot();
        root.addView(hero("绑定家属", "请把下面号码告诉家属"));
        status = notice("等待家属输入绑定码。号码 10 分钟内有效，可随时停止等待。");

        TextView codeView = title(inviteCode == null || inviteCode.isEmpty() ? "------" : inviteCode);
        codeView.setTextSize(44);
        codeView.setTextColor(COLOR_BLUE_DARK);
        codeView.setPadding(0, dp(12), 0, dp(12));

        Button regenerateButton = secondaryButton("重新生成绑定码");
        Button cancelButton = dangerButton("停止等待绑定");
        regenerateButton.setOnClickListener(v -> createInvite(regenerateButton));
        cancelButton.setOnClickListener(v -> cancelInviteWait(cancelButton));

        LinearLayout bindCard = card("告诉家属这个号码", "请家属打开“我是家属”并输入。");
        bindCard.addView(codeView);
        bindCard.addView(regenerateButton);
        bindCard.addView(cancelButton);
        root.addView(bindCard);
        root.addView(status);
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
        pollElderBindLoop();
    }

    private void showElderBoundSuccess(int familyCount, boolean invitePending) {
        currentPage = "elderBound";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        elderBindPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("绑定成功", "家人已经可以帮助你"));
        status = notice("");
        status.setVisibility(View.GONE);

        LinearLayout successCard = card("亲属绑定已完成", "绑定只需要做一次。现在不会自动共享屏幕，也不会自动让家属操作手机。");
        Button prepareButton = primaryButton("知道了");
        prepareButton.setOnClickListener(v -> showElder());
        successCard.addView(prepareButton);
        if (invitePending) {
            Button continueButton = secondaryButton("继续绑定其他家属");
            continueButton.setOnClickListener(v -> showElderInvite(prefs.getString("pendingInviteCode", "")));
            successCard.addView(continueButton);
        }
        root.addView(status);
        root.addView(successCard);
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
    }

    private void showSafetySettings() {
        currentPage = "safety";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        familyPolling = false;
        elderBindPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("安全设置", this::showElder));
        status = notice("");
        status.setVisibility(View.GONE);

        Button privacyButton = secondaryButton(sensitiveButtonText());
        Button overlayButton = secondaryButton(annotationButtonText());
        Button controlButton = secondaryButton(remoteControlButtonText());
        Button accessibilityButton = secondaryButton(accessibilityButtonText());
        Button backButton = primaryButton("返回长辈页");

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
            openAccessibilityServiceSettings();
        });
        backButton.setOnClickListener(v -> showElder());

        LinearLayout safetyCard = card("权限与保护", "");
        safetyCard.addView(privacyButton);
        safetyCard.addView(overlayButton);
        safetyCard.addView(controlButton);
        safetyCard.addView(accessibilityButton);
        root.addView(safetyCard);
        root.addView(status);
        root.addView(backButton);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showPrivacyPolicy() {
        currentPage = "privacy";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("隐私政策", this::showProfile));

        LinearLayout summary = card("我们会处理哪些信息", "亲属绑定信息、协助会话状态、长辈主动授权后的屏幕画面、画圈提示、远程操作授权、操作审计和崩溃日志摘要。");
        root.addView(summary);
        root.addView(card("屏幕共享", "只有长辈点击求助并确认系统屏幕共享弹窗后，已绑定家属才能看到本次屏幕画面。协助结束后立即停止共享。"));
        root.addView(card("远程操作", "默认不能远程点击。家属每次申请后，必须由长辈在本次协助中明确同意；协助结束后授权自动失效。"));
        root.addView(card("敏感保护", "敏感页面保护默认开启。遇到验证码、支付、银行、密码等页面时，长辈可以开启遮罩，减少隐私暴露。"));
        root.addView(card("权限用途", "屏幕录制用于共享画面；显示在其他应用上层用于画圈提示；无障碍服务用于敏感页面保护和长辈授权后的远程操作；通知用于提醒长辈处理重要请求。"));
        root.addView(card("数据保留", "实时屏幕画面默认不保存。服务端会保留必要绑定关系、崩溃日志摘要和安全审计记录，用于保障账号安全、排查异常和防止滥用。"));
        root.addView(card("安全边界", "未绑定不能协助；长辈未发起不能看屏幕；长辈未授权不能远程操作；同一时间只允许一名家属协助。"));
        root.addView(card("用户权利", "你可以在应用内解绑家属，停止协助，关闭远程操作授权，并可通过应用市场页面联系开发者申请删除相关数据。"));
        root.addView(card("联系我们", "如需帮助、反馈安全问题或申请删除个人信息，请通过应用市场页面联系开发者。"));

        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showRelativesManagement() {
        currentPage = "relatives";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("亲属管理", this::showProfile));
        status = notice("");
        status.setVisibility(View.GONE);

        if (!isLoggedIn()) {
            LinearLayout loginCard = card("先登录账号", "登录后才能邀请、确认或管理亲属。");
            Button loginButton = primaryButton("手机号登录");
            loginButton.setOnClickListener(v -> showLogin("profile"));
            loginCard.addView(loginButton);
            root.addView(loginCard);
        } else {
            root.addView(card("当前账号", "手机号：" + accountPhone + "\n称呼：" + displayName));
            root.addView(card("当前绑定", bindingStatusText()));
            root.addView(card("绑定规则", "家属输入绑定码后不会直接绑定。长辈必须在本机确认，家属才能加入。绑定码 10 分钟有效且会记录审计。"));
            Button elderInviteButton = primaryButton("我是长辈，邀请家属");
            Button familyBindButton = secondaryButton("我是家属，添加长辈");
            elderInviteButton.setOnClickListener(v -> showElder());
            familyBindButton.setOnClickListener(v -> showFamilyBind());
            LinearLayout actionCard = card("添加亲属", "");
            actionCard.addView(elderInviteButton);
            actionCard.addView(familyBindButton);
            root.addView(actionCard);
        }
        root.addView(status);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showFamily() {
        closeFamilyFullscreen();
        if (!ensureLoggedIn("family")) {
            return;
        }
        currentPage = "family";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        if (!isBoundAs("family")) {
            if (!prefs.getString("pendingBindToken", "").isEmpty()) {
                showFamilyBindPending();
                return;
            }
            showFamilyBind();
            return;
        }
        stopFamilyWebRtc();
        familyPolling = true;
        elderAnnotationPolling = false;
        familyControlAllowed = false;
        rtcVideoReady = false;
        rtcTrackAttached = false;
        familyMediaReady = false;
        lastRtcFrameAtMs = 0;
        lastRtcSampleAtMs = 0;
        rtcBlackSamples = 0;
        lastFrameReceivedAtMs = 0;
        lastFrameUpdatedAt = "";
        root = verticalRoot();
        root.addView(compactPageTitle("协助长辈"));
        status = stableNotice("连接正常");
        status.setVisibility(View.GONE);
        boolean useWebRtc = isWebRtcEnabled();
        View screenView = buildFrameView();

        familyControlRequestButton = secondaryButton("请求远程操作授权");
        familyRemoteButton = secondaryButton("远程操作");
        familyEndButton = dangerButton("结束本次协助");
        familyChangeBindingButton = secondaryButton("绑定其他长辈");
        Button refreshButton = primaryButton("立即刷新");
        familyControlRequestButton.setOnClickListener(v -> requestRemoteControl(familyControlRequestButton));
        familyRemoteButton.setOnClickListener(v -> showRemoteControlPanel());
        familyEndButton.setOnClickListener(v -> endFamilyAssistView());
        familyChangeBindingButton.setOnClickListener(v -> confirmChangeFamilyBinding());
        refreshButton.setOnClickListener(v -> {
            if (!refreshButton.isEnabled()) {
                return;
            }
            temporarilyDisable(refreshButton, "刷新中...");
            pollFamilyOnce();
        });

        root.addView(status);
        familyWaitingView = card("等待求助", "长辈发起求助后，屏幕会自动显示。");
        familyWaitingTitle = (TextView) familyWaitingView.getChildAt(0);
        familyWaitingCaption = (TextView) familyWaitingView.getChildAt(1);
        familyScreenLabelView = screenLabel(useWebRtc ? "长辈实时屏幕" : "长辈屏幕");
        familyScreenSurface = screenSurface(screenView);
        familyFullscreenButton = fullscreenIconButton(R.drawable.ic_fullscreen, "全屏查看");
        familyFullscreenButton.setOnClickListener(v -> openFamilyFullscreen());
        FrameLayout.LayoutParams fullscreenParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        fullscreenParams.setMargins(0, dp(10), dp(10), 0);
        familyScreenSurface.addView(familyFullscreenButton, fullscreenParams);
        root.addView(familyWaitingView);
        root.addView(familyScreenLabelView);
        root.addView(familyScreenSurface);
        familyActionBar = buildFamilyActionBar();
        setFamilySessionActive(false);
        if (!useWebRtc) {
            refreshButton.setVisibility(View.GONE);
        }
        setContentView(familyPage(root, familyActionBar, bottomNav("family")));
        startFamilyPollLoop();
    }

    private View buildFrameView() {
        frameView = new ImageView(this);
        frameView.setAdjustViewBounds(true);
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frameView.setBackgroundColor(0xFFF3F6FA);
        frameView.setPadding(dp(8), dp(8), dp(8), dp(8));
        frameView.setMinimumHeight(dp(430));
        frameView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        frameView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                screenTouchStartX = event.getX();
                screenTouchStartY = event.getY();
                screenTouchStartAtMs = System.currentTimeMillis();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP && frameView.getDrawable() != null) {
                long age = System.currentTimeMillis() - lastFrameReceivedAtMs;
                if (lastFrameReceivedAtMs == 0 || age > FRESH_FRAME_MS) {
                    setStatus("屏幕画面正在刷新，请等画面稳定后再点。");
                    pollFamilyOnce();
                    return true;
                }
                if (familyControlAllowed) {
                    handleRemoteTouchOnImage(event.getX(), event.getY());
                } else {
                    float[] point = normalizedImagePoint(frameView, event.getX(), event.getY());
                    sendAnnotation(point[0], point[1]);
                }
                return true;
            }
            return true;
        });
        return frameView;
    }

    private LinearLayout buildRemoteControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(6), dp(18), dp(8));
        panel.addView(caption("点击画面可直接操作；也可以使用下面的快捷操作。"));

        Button homeButton = secondaryButton("主页");
        Button backButton = secondaryButton("返回");
        Button swipeUpButton = secondaryButton("上滑");
        Button swipeDownButton = secondaryButton("下滑");
        Button swipeLeftButton = secondaryButton("左滑");
        Button swipeRightButton = secondaryButton("右滑");

        homeButton.setOnClickListener(v -> sendRemoteGlobal("home"));
        backButton.setOnClickListener(v -> sendRemoteGlobal("back"));
        swipeUpButton.setOnClickListener(v -> sendRemoteSwipe(0.5f, 0.82f, 0.5f, 0.22f, 420));
        swipeDownButton.setOnClickListener(v -> sendRemoteSwipe(0.5f, 0.22f, 0.5f, 0.82f, 420));
        swipeLeftButton.setOnClickListener(v -> sendRemoteSwipe(0.82f, 0.5f, 0.18f, 0.5f, 420));
        swipeRightButton.setOnClickListener(v -> sendRemoteSwipe(0.18f, 0.5f, 0.82f, 0.5f, 420));

        LinearLayout row1 = horizontalRow();
        addControlButton(row1, homeButton);
        addControlButton(row1, backButton);
        LinearLayout row2 = horizontalRow();
        addControlButton(row2, swipeUpButton);
        addControlButton(row2, swipeDownButton);
        LinearLayout row3 = horizontalRow();
        addControlButton(row3, swipeLeftButton);
        addControlButton(row3, swipeRightButton);
        panel.addView(row1);
        panel.addView(row2);
        panel.addView(row3);
        return panel;
    }

    private void showRemoteControlPanel() {
        if (!familyControlAllowed) {
            setStatus("长辈同意后才能使用远程操作。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("远程操作")
                .setView(buildRemoteControlPanel())
                .setNegativeButton("关闭", null)
                .show();
    }

    private LinearLayout buildFamilyActionBar() {
        LinearLayout bar = horizontalRow();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackground(rounded(0xFFFFFFFF, 0, COLOR_LINE));
        addFamilyAction(bar, familyControlRequestButton);
        addFamilyAction(bar, familyRemoteButton);
        addFamilyAction(bar, familyEndButton);
        addFamilyAction(bar, familyChangeBindingButton);
        familyRemoteButton.setVisibility(View.GONE);
        return bar;
    }

    private void openFamilyFullscreen() {
        if (!familyLastActive || !familyMediaReady || frameView == null || frameView.getDrawable() == null
                || familyFullscreenDialog != null) {
            return;
        }
        detachFromParent(frameView);
        frameView.setPadding(0, 0, 0, 0);
        frameView.setMinimumHeight(0);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        familyFullscreenDialog = dialog;
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setBackgroundColor(0xFF05070B);

        LinearLayout topBar = fullscreenTopBar();
        stage.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(0xFF05070B);
        canvas.addView(frameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        stage.addView(canvas, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout bottomBar = fullscreenActionBar();
        stage.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        applyFullscreenInsets(stage, topBar, bottomBar);

        dialog.setContentView(stage);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> restoreFamilyScreenFromFullscreen());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            applyFullscreenSystemUi(window);
        }
        updateFullscreenControlState();
    }

    private LinearLayout fullscreenTopBar() {
        LinearLayout bar = horizontalRow();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(0xD9151A24);

        ImageButton close = fullscreenIconButton(R.drawable.ic_fullscreen_exit, "退出全屏");
        close.setOnClickListener(v -> closeFamilyFullscreen());
        bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText("长辈屏幕");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, titleParams);

        familyFullscreenModeView = new TextView(this);
        familyFullscreenModeView.setTextSize(13);
        familyFullscreenModeView.setTextColor(Color.WHITE);
        familyFullscreenModeView.setGravity(Gravity.CENTER);
        familyFullscreenModeView.setPadding(dp(10), dp(7), dp(10), dp(7));
        familyFullscreenModeView.setBackground(rounded(0xCC243044, dp(8), 0x004B5563));
        bar.addView(familyFullscreenModeView);
        return bar;
    }

    private LinearLayout fullscreenActionBar() {
        LinearLayout bar = horizontalRow();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(0xE6141821);

        familyFullscreenControlRequestButton = darkToolbarButton("申请远程操作");
        familyFullscreenRemoteButton = darkToolbarButton("操作菜单");
        familyFullscreenEndButton = darkToolbarDangerButton("结束协助");
        familyFullscreenControlRequestButton.setOnClickListener(v -> requestRemoteControl(familyFullscreenControlRequestButton));
        familyFullscreenRemoteButton.setOnClickListener(v -> showRemoteControlPanel());
        familyFullscreenEndButton.setOnClickListener(v -> {
            closeFamilyFullscreen();
            endFamilyAssistView();
        });
        addFamilyAction(bar, familyFullscreenControlRequestButton);
        addFamilyAction(bar, familyFullscreenRemoteButton);
        addFamilyAction(bar, familyFullscreenEndButton);
        return bar;
    }

    private Button darkToolbarButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(0xFF263246, dp(8), 0xFF40506A));
        return button;
    }

    private Button darkToolbarDangerButton(String text) {
        Button button = darkToolbarButton(text);
        button.setBackground(rounded(0xFFB4232C, dp(8), 0xFFD24751));
        return button;
    }

    private ImageButton fullscreenIconButton(int iconRes, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= 26) {
            button.setTooltipText(description);
        }
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(rounded(0xCC172033, dp(8), 0x6677849A));
        return button;
    }

    private void applyFullscreenInsets(View stage, View topBar, View bottomBar) {
        stage.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = stableTopInset(insets);
            int bottom = stableBottomInset(insets);
            topBar.setPadding(dp(10), dp(8) + top, dp(12), dp(8));
            int side = fullscreenActionSidePadding(view.getWidth());
            bottomBar.setPadding(side, dp(8), side, dp(8) + bottom);
            return insets;
        });
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int side = fullscreenActionSidePadding(right - left);
            int currentBottom = bottomBar.getPaddingBottom();
            bottomBar.setPadding(side, dp(8), side, currentBottom);
        });
    }

    private int fullscreenActionSidePadding(int width) {
        return Math.max(dp(10), (width - dp(520)) / 2);
    }

    private int stableTopInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        }
        return insets.getStableInsetTop();
    }

    private int stableBottomInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getStableInsetBottom();
    }

    private void applyFullscreenSystemUi(Window window) {
        if (window == null) return;
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void closeFamilyFullscreen() {
        Dialog dialog = familyFullscreenDialog;
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void restoreFamilyScreenFromFullscreen() {
        detachFromParent(frameView);
        familyFullscreenDialog = null;
        familyFullscreenModeView = null;
        familyFullscreenControlRequestButton = null;
        familyFullscreenRemoteButton = null;
        familyFullscreenEndButton = null;
        if (frameView != null) {
            frameView.setPadding(dp(8), dp(8), dp(8), dp(8));
            frameView.setMinimumHeight(dp(430));
        }
        if (familyScreenSurface != null && frameView != null && frameView.getParent() == null
                && "family".equals(currentPage)) {
            familyScreenSurface.addView(frameView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            if (familyFullscreenButton != null) familyFullscreenButton.bringToFront();
        }
        configureSystemBars();
    }

    private void detachFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private void addFamilyAction(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setMinHeight(0);
        button.setTextSize(15);
        button.setPadding(dp(8), 0, dp(8), 0);
        row.addView(button, params);
    }

    private View familyPage(View content, View actionBar, View nav) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);
        page.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        page.addView(actionBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
        ));
        page.addView(nav);
        return page;
    }

    private void showFamilyBind() {
        if (!ensureLoggedIn("family")) {
            return;
        }
        currentPage = "familyBind";
        familyPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("绑定长辈", "输入长辈手机上显示的 6 位码"));
        status = notice("输入长辈手机上的绑定码，完成后即可接收求助。");

        String familyName = displayName;
        if (authToken.isEmpty() && (familyName.isEmpty() || "妈妈".equals(familyName))) {
            familyName = "家人";
        }
        EditText nameInput = input("我的称呼，例如 女儿", familyName);
        EditText inviteInput = input("6 位绑定码", "");
        Button bindButton = primaryButton("绑定长辈");

        bindButton.setOnClickListener(v -> {
            displayName = nameInput.getText().toString().trim();
            if (displayName.isEmpty()) {
                displayName = "家属";
            }
            prefs.edit().putString("displayName", displayName).apply();
            bindFamily(inviteInput.getText().toString().trim(), bindButton);
        });

        LinearLayout bindCard = card("亲属绑定", "");
        bindCard.addView(label("我的称呼"));
        bindCard.addView(nameInput);
        bindCard.addView(label("绑定码"));
        bindCard.addView(inviteInput);
        bindCard.addView(bindButton);
        root.addView(bindCard);
        root.addView(status);
        root.addView(bottomNav("family"));
        setContentView(scroll(root));
    }

    private void showFamilyBindPending() {
        currentPage = "familyBindPending";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(hero("等待长辈确认", "长辈同意后才会完成绑定"));
        status = notice("已发送绑定申请。请让长辈在亲情帮帮里点“同意绑定”。");
        Button refreshButton = primaryButton("查看确认结果");
        Button cancelButton = secondaryButton("重新输入绑定码");
        refreshButton.setOnClickListener(v -> pollFamilyBindPendingOnce());
        cancelButton.setOnClickListener(v -> {
            prefs.edit().remove("pendingBindToken").remove("pendingBindPairCode").apply();
            main.removeCallbacks(familyBindPendingLoopRunnable);
            showFamilyBind();
        });
        LinearLayout waitCard = card("绑定申请已发送", "为了保护长辈，知道绑定码还不能直接绑定。需要长辈在自己手机上确认你的账号。");
        waitCard.addView(refreshButton);
        waitCard.addView(cancelButton);
        root.addView(waitCard);
        root.addView(status);
        root.addView(bottomNav("family"));
        setContentView(scroll(root));
        main.removeCallbacks(familyBindPendingLoopRunnable);
        main.post(familyBindPendingLoopRunnable);
    }

    private void pollFamilyBindPendingOnce() {
        String pendingToken = prefs.getString("pendingBindToken", "");
        String pendingPairCode = prefs.getString("pendingBindPairCode", pairCode);
        if (pendingToken.isEmpty()) {
            return;
        }
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/pending?pairCode=" + encoded(pendingPairCode)
                        + "&pendingToken=" + encoded(pendingToken)
                        + "&accountToken=" + encoded(accountToken));
                if (result.optBoolean("approved", false)) {
                    authToken = result.optString("authToken", "");
                    pairCode = result.optString("pairCode", pendingPairCode);
                    memberRole = "family";
                    prefs.edit()
                            .putString("authToken", authToken)
                            .putString("pairCode", pairCode)
                            .putString("memberRole", memberRole)
                            .remove("pendingBindToken")
                            .remove("pendingBindPairCode")
                            .apply();
                    main.removeCallbacks(familyBindPendingLoopRunnable);
                    main.post(() -> {
                        showFamily();
                        setStatus("长辈已同意绑定。正在等待长辈发起求助。");
                    });
                } else {
                    main.post(() -> setStatus("还在等待长辈确认。"));
                }
            } catch (Exception e) {
                main.removeCallbacks(familyBindPendingLoopRunnable);
                main.post(() -> setStatus("绑定申请未通过或已过期：" + friendlyError(e)));
            }
        });
    }

    private void confirmChangeFamilyBinding() {
        new AlertDialog.Builder(this)
                .setTitle("绑定其他长辈")
                .setMessage("当前绑定会解除。之后输入另一位长辈提供的绑定码即可。")
                .setPositiveButton("继续", (dialog, which) -> changeFamilyBinding())
                .setNegativeButton("取消", null)
                .show();
    }

    private void changeFamilyBinding() {
        if (!"family".equals(memberRole) || authToken.isEmpty()) {
            clearFamilyBindingLocal();
            showFamilyBind();
            return;
        }
        setButtonBusy(familyChangeBindingButton, "正在解除当前绑定...");
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/unbind", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
                main.post(() -> {
                    clearFamilyBindingLocal();
                    showFamilyBind();
                    setStatus("当前绑定已解除，请输入另一位长辈的绑定码。");
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(familyChangeBindingButton);
                    setStatus("解除绑定失败：" + friendlyError(e));
                });
            }
        });
    }

    private void clearFamilyBindingLocal() {
        familyPolling = false;
        stopFamilyWebRtc();
        authToken = "";
        memberRole = "";
        pairCode = DEFAULT_PAIR_CODE;
        prefs.edit()
                .remove("authToken")
                .remove("memberRole")
                .remove(PREF_ASSIST_SESSION_ID)
                .putString("pairCode", pairCode)
                .apply();
    }

    private void requestHelpAndCapture() {
        if (captureRequestInProgress) {
            setStatus("正在打开屏幕共享授权，请稍等。");
            return;
        }
        if (!ensureBound("长辈")) {
            return;
        }
        if (!areAssistNotificationsEnabled()) {
            requestAssistNotificationPermission();
            return;
        }
        if (!ensureOverlayReady()) {
            return;
        }
        captureRequestInProgress = true;
        setStatus("正在打开屏幕共享授权...");
        requestScreenCapturePermission();
    }

    private boolean areAssistNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel urgent = manager.getNotificationChannel(AssistNotifier.CHANNEL_URGENT);
            return urgent != null && urgent.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private void requestAssistNotificationPermission() {
        resumeCaptureAfterNotificationPermission = true;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notificationPermissionRequested", false)) {
            prefs.edit().putBoolean("notificationPermissionRequested", true).apply();
            setStatus("请允许通知提醒，家人在后台发来请求时才能及时看到。");
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        showNotificationPermissionDialog();
    }

    private void showNotificationPermissionDialog() {
        resumeCaptureAfterNotificationPermission = false;
        new AlertDialog.Builder(this)
                .setTitle("请开启协助提醒")
                .setMessage("开启后，即使正在使用其他应用，也能收到家人的操作请求和结束提醒。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    resumeCaptureAfterNotificationSettings = true;
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                })
                .setNegativeButton("暂不求助", null)
                .show();
    }

    private void publishHelpRequest(Runnable afterSuccess) {
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("masked", isPrivacyMasked());
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/help", payload);
                String sessionId = result.optString("sessionId", "");
                prefs.edit().putString(PREF_ASSIST_SESSION_ID, sessionId).apply();
                if (afterSuccess != null) {
                    main.post(afterSuccess);
                }
            } catch (Exception e) {
                main.post(() -> {
                    markAssistanceStoppedLocal();
                    stopCaptureServices();
                    showElder();
                    setStatus("发起求助失败：" + friendlyError(e) + "。请检查网络后重试。");
                });
            }
        });
    }

    private void loginAccount(String phone, String code, String name, String afterRole, Button sourceButton) {
        if (phone.isEmpty() || code.isEmpty()) {
            setStatus("请输入手机号和验证码。");
            return;
        }
        if (name.isEmpty()) {
            name = "亲友";
        }
        final String finalName = name;
        setButtonBusy(sourceButton, "登录中...");
        setStatus("正在登录...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/account/login", new JSONObject()
                        .put("phone", phone)
                        .put("code", code)
                        .put("name", finalName));
                accountToken = result.optString("accountToken", "");
                JSONObject user = result.optJSONObject("user");
                accountPhone = user != null ? user.optString("phone", phone) : phone;
                displayName = user != null ? user.optString("name", finalName) : finalName;
                prefs.edit()
                        .putString("accountToken", accountToken)
                        .putString("accountPhone", accountPhone)
                        .putString("displayName", displayName)
                        .apply();
                main.post(() -> {
                    if ("elder".equals(afterRole)) {
                        showElder();
                    } else if ("family".equals(afterRole)) {
                        showFamily();
                    } else {
                        showProfile();
                        setStatus("登录成功。");
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("登录失败：" + friendlyError(e));
                });
            }
        });
    }

    private void createInvite(Button sourceButton) {
        if (inviteInProgress) {
            return;
        }
        inviteInProgress = true;
        ensureElderPairCode();
        setButtonBusy(sourceButton, "生成中...");
        setStatus("正在生成亲属绑定码...");
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("elderName", displayName)
                        .put("accountToken", accountToken)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("deviceId", deviceId)
                        .put("authToken", "elder".equals(memberRole) ? authToken : "");
                JSONObject result = postJsonWithRelayFallback("/api/invite", payload);
                authToken = result.optString("authToken", "");
                memberRole = "elder";
                String inviteCode = result.optString("inviteCode", "");
                int familyMemberCount = result.optInt("familyMemberCount", 0);
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("memberRole", memberRole)
                        .putString("pendingInviteCode", inviteCode)
                        .remove("lastBindApprovalPromptId")
                        .putBoolean("familyBound", familyMemberCount > 0)
                        .apply();
                main.post(() -> showElderInvite(inviteCode));
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("生成失败：" + friendlyError(e));
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
                        .put("accountToken", accountToken)
                        .put("familyName", displayName)
                        .put("deviceId", deviceId);
                JSONObject result = postJsonWithRelayFallback("/api/bind", payload);
                if (result.optBoolean("pendingApproval", false)) {
                    String pendingToken = result.optString("pendingToken", "");
                    String pendingPairCode = result.optString("pairCode", pairCode);
                    prefs.edit()
                            .putString("pendingBindToken", pendingToken)
                            .putString("pendingBindPairCode", pendingPairCode)
                            .putString("pairCode", pendingPairCode)
                            .apply();
                    pairCode = pendingPairCode;
                    main.post(() -> showFamilyBindPending());
                    return;
                }
                authToken = result.optString("authToken", "");
                pairCode = result.optString("pairCode", pairCode);
                memberRole = "family";
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("pairCode", pairCode)
                        .putString("memberRole", memberRole)
                        .apply();
                main.post(() -> {
                    showFamily();
                    setStatus("绑定成功。正在等待长辈发起协助。");
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("绑定失败：" + friendlyError(e));
                });
            } finally {
                bindInProgress = false;
            }
        });
    }

    private void cancelInviteWait(Button sourceButton) {
        if (authToken.isEmpty()) {
            clearBindingAndShowSetup("已停止等待绑定。");
            return;
        }
        setButtonBusy(sourceButton, "正在停止...");
        elderBindPolling = false;
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/invite/cancel", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
                JSONObject family = result.optJSONObject("family");
                int familyCount = boundFamilyCount(family);
                prefs.edit().remove("pendingInviteCode").putBoolean("familyBound", familyCount > 0).apply();
                main.post(() -> {
                    if (familyCount > 0) {
                        showElder();
                        setStatus("已停止接受新的家属绑定。已绑定家属仍可正常协助。");
                    } else {
                        clearBindingAndShowSetup("已停止等待绑定，需要时可重新选择“我是长辈”。");
                    }
                });
            } catch (Exception e) {
                elderBindPolling = true;
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("停止等待失败：" + friendlyError(e));
                    pollElderBindLoop();
                });
            }
        });
    }

    private void ensureElderPairCode() {
        if ("elder".equals(memberRole) && !authToken.isEmpty()) {
            return;
        }
        String stableId = deviceId == null ? "" : deviceId.replaceAll("[^A-Za-z0-9]", "");
        if (stableId.length() > 20) {
            stableId = stableId.substring(0, 20);
        }
        if (stableId.isEmpty()) {
            stableId = Long.toHexString(System.currentTimeMillis());
        }
        pairCode = "family-" + stableId;
        prefs.edit().putString("pairCode", pairCode).apply();
    }

    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void startCaptureService(int resultCode, Intent data) {
        boolean useWebRtc = isWebRtcEnabled();
        Intent intent = useWebRtc ? new Intent(this, WebRtcScreenService.class) : new Intent(this, CaptureService.class);
        if (useWebRtc) {
            intent.putExtra(WebRtcScreenService.EXTRA_BASE_URL, baseUrl);
            intent.putExtra(WebRtcScreenService.EXTRA_PAIR_CODE, pairCode);
            intent.putExtra(WebRtcScreenService.EXTRA_AUTH_TOKEN, authToken);
            intent.putExtra(WebRtcScreenService.EXTRA_SESSION_ID, prefs.getString(PREF_ASSIST_SESSION_ID, ""));
            intent.putExtra(WebRtcScreenService.EXTRA_RESULT_DATA, data);
        } else {
            intent.putExtra(CaptureService.EXTRA_BASE_URL, baseUrl);
            intent.putExtra(CaptureService.EXTRA_PAIR_CODE, pairCode);
            intent.putExtra(CaptureService.EXTRA_AUTH_TOKEN, authToken);
            intent.putExtra(CaptureService.EXTRA_SESSION_ID, prefs.getString(PREF_ASSIST_SESSION_ID, ""));
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
        String endingSessionId = prefs.getString(PREF_ASSIST_SESSION_ID, "");
        prefs.edit().putBoolean("remoteControlAllowed", false).apply();
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("sessionId", endingSessionId));
            } catch (Exception ignored) {
            }
        });
    }

    private void startFamilyWebRtc() {
        if (familyRtcClient != null) {
            stopFamilyWebRtc();
        }
        final String rtcSessionId = familyLastSessionId;
        familyRtcClient = new WebRtcClient(this, baseUrl, pairCode, authToken, rtcSessionId, new WebRtcClient.Listener() {
            @Override
            public void onState(String text) {
                if (!rtcSessionId.equals(familyLastSessionId) || !familyLastActive) {
                    return;
                }
                if (isAuthFailureText(text)) {
                    clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。");
                    return;
                }
                if (text.contains("失败") || text.contains("FAILED") || text.contains("DISCONNECTED")) {
                    setStatus("实时连接较慢，正在使用备用画面");
                } else {
                    setStatus("正在连接长辈屏幕...");
                }
            }

            @Override
            public void onRemoteVideo(VideoTrack track) {
                if (!rtcSessionId.equals(familyLastSessionId) || !familyLastActive || rtcTrackAttached) {
                    return;
                }
                rtcTrackAttached = true;
                track.addSink(frame -> monitorRemoteRtcFrame(frame, rtcSessionId));
            }

            @Override
            public void onLocalVideoFrame(org.webrtc.VideoFrame frame) {
            }
        });
        familyRtcClient.startFamily();
    }

    private void stopFamilyWebRtc() {
        if (familyRtcClient != null) {
            familyRtcClient.stop();
            familyRtcClient = null;
        }
        rtcVideoReady = false;
        rtcTrackAttached = false;
        lastRtcFrameAtMs = 0;
        lastRtcSampleAtMs = 0;
        rtcBlackSamples = 0;
        rtcFrameAnalysisInFlight = false;
    }

    private void monitorRemoteRtcFrame(VideoFrame frame, String expectedSessionId) {
        long now = System.currentTimeMillis();
        lastRtcFrameAtMs = now;
        if (rtcFrameAnalysisInFlight || now - lastRtcSampleAtMs < RTC_FRAME_SAMPLE_MS) {
            return;
        }
        lastRtcSampleAtMs = now;
        rtcFrameAnalysisInFlight = true;
        frame.retain();
        videoAnalysisIo.execute(() -> {
            VideoFrame.I420Buffer buffer = null;
            try {
                buffer = frame.getBuffer().toI420();
                boolean black = isMostlyBlackFrame(buffer);
                Bitmap bitmap = black ? null : i420ToBitmap(buffer);
                rtcBlackSamples = black ? rtcBlackSamples + 1 : 0;
                main.post(() -> {
                    if (!expectedSessionId.equals(familyLastSessionId) || !familyLastActive) {
                        if (bitmap != null) bitmap.recycle();
                        return;
                    }
                    if (rtcBlackSamples >= 2) {
                        if (rtcVideoReady) {
                            rtcVideoReady = false;
                            setStatus("实时画面暂时不可用，已切换备用画面");
                        }
                        pollFamilyOnce();
                    } else if (!black && bitmap != null && frameView != null) {
                        lastFrameReceivedAtMs = System.currentTimeMillis();
                        Drawable previous = frameView.getDrawable();
                        frameView.setImageBitmap(bitmap);
                        if (previous instanceof BitmapDrawable) {
                            Bitmap oldBitmap = ((BitmapDrawable) previous).getBitmap();
                            if (oldBitmap != bitmap && !oldBitmap.isRecycled()) {
                                oldBitmap.recycle();
                            }
                        }
                        rtcVideoReady = true;
                        showFamilyMedia(frameView, "实时画面已连接");
                    }
                });
            } catch (Exception ignored) {
            } finally {
                if (buffer != null) buffer.release();
                frame.release();
                rtcFrameAnalysisInFlight = false;
            }
        });
    }

    private Bitmap i420ToBitmap(VideoFrame.I420Buffer buffer) {
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        int[] pixels = new int[width * height];
        ByteBuffer yPlane = buffer.getDataY();
        ByteBuffer uPlane = buffer.getDataU();
        ByteBuffer vPlane = buffer.getDataV();
        int yStride = buffer.getStrideY();
        int uStride = buffer.getStrideU();
        int vStride = buffer.getStrideV();
        for (int row = 0; row < height; row++) {
            int chromaRow = row / 2;
            for (int col = 0; col < width; col++) {
                int y = yPlane.get(row * yStride + col) & 0xFF;
                int u = (uPlane.get(chromaRow * uStride + col / 2) & 0xFF) - 128;
                int v = (vPlane.get(chromaRow * vStride + col / 2) & 0xFF) - 128;
                int r = clampColor(Math.round(y + 1.402f * v));
                int g = clampColor(Math.round(y - 0.344136f * u - 0.714136f * v));
                int b = clampColor(Math.round(y + 1.772f * u));
                pixels[row * width + col] = Color.rgb(r, g, b);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private boolean isMostlyBlackFrame(VideoFrame.I420Buffer buffer) {
        ByteBuffer y = buffer.getDataY();
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        int stride = buffer.getStrideY();
        int stepX = Math.max(1, width / 24);
        int stepY = Math.max(1, height / 40);
        long sum = 0;
        int bright = 0;
        int samples = 0;
        for (int row = stepY / 2; row < height; row += stepY) {
            for (int col = stepX / 2; col < width; col += stepX) {
                int value = y.get(row * stride + col) & 0xFF;
                sum += value;
                if (value > 32) bright++;
                samples++;
            }
        }
        if (samples == 0) return false;
        return sum / samples < 24 && bright * 100 < samples;
    }

    private void scheduleWebRtcFallback(String expectedSessionId) {
        main.postDelayed(() -> {
            if (!familyPolling || !isWebRtcEnabled() || rtcVideoReady || !expectedSessionId.equals(familyLastSessionId)) {
                return;
            }
            setStatus("实时连接较慢，正在使用备用画面");
        }, 8000);
    }

    private void endFamilyAssistView() {
        if (familyEnding) {
            return;
        }
        familyEnding = true;
        setButtonBusy(familyEndButton, "正在结束并通知长辈...");
        String sessionId = familyLastSessionId;
        familyLastActive = false;
        familyLastSessionId = "";
        familyControlAllowed = false;
        lastFrameReceivedAtMs = 0;
        lastFrameUpdatedAt = "";
        stopFamilyWebRtc();
        clearFamilyScreen();
        setFamilySessionActive(false);
        setStatus("正在结束本次协助并通知长辈...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/family/end", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("sessionId", sessionId));
                if (result.optBoolean("stale", false)) {
                    throw new IllegalStateException("会话已变化，请重新确认");
                }
                main.post(() -> {
                    familyEnding = false;
                    restoreButton(familyEndButton);
                    setStatus("本次协助已结束，长辈也会看到结束提示。再次发起时会自动连接。");
                });
            } catch (Exception e) {
                main.post(() -> {
                    familyEnding = false;
                    restoreButton(familyEndButton);
                    setStatus("结束同步失败，正在重新确认会话状态：" + friendlyError(e));
                    pollFamilyOnce();
                });
            }
        });
    }

    private void clearFamilyScreen() {
        if (frameView != null) {
            frameView.setImageDrawable(null);
        }
    }

    private void setFamilySessionActionsVisible(boolean visible) {
        if (familyEndButton != null) familyEndButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (familyControlRequestButton != null) familyControlRequestButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (familyRemoteButton != null) familyRemoteButton.setVisibility(View.GONE);
    }

    private void setFamilySessionActive(boolean active) {
        if (active) {
            updateFamilyWaiting("正在连接屏幕", "画面出现后即可帮助长辈。");
            if (!familyMediaReady && familyScreenSurface != null) {
                resizeFamilyScreenSurface(dp(2));
            }
        } else {
            closeFamilyFullscreen();
            familyMediaReady = false;
            updateFamilyWaiting("等待求助", "长辈发起求助后，屏幕会自动显示。");
            updateFamilyControlButton(false, false);
            resizeFamilyScreenSurface(dp(500));
        }
        if (familyWaitingView != null) familyWaitingView.setVisibility(active && familyMediaReady ? View.GONE : View.VISIBLE);
        if (familyScreenLabelView != null) familyScreenLabelView.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyScreenSurface != null) familyScreenSurface.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyFullscreenButton != null) familyFullscreenButton.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyChangeBindingButton != null) familyChangeBindingButton.setVisibility(active ? View.GONE : View.VISIBLE);
        setFamilySessionActionsVisible(active);
    }

    private void updateFamilyWaiting(String titleText, String captionText) {
        if (familyWaitingTitle != null) familyWaitingTitle.setText(titleText);
        if (familyWaitingCaption != null) familyWaitingCaption.setText(captionText);
    }

    private void showFamilyMedia(View mediaView, String stateText) {
        if (!familyLastActive || mediaView == null || familyScreenSurface == null) {
            return;
        }
        familyMediaReady = true;
        resizeFamilyScreenSurface(dp(500));
        if (frameView != null && frameView.getParent() == null && mediaView == frameView) {
            familyScreenSurface.addView(frameView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }
        if (frameView != null) {
            frameView.setVisibility(mediaView == frameView ? View.VISIBLE : View.GONE);
            if (mediaView == frameView) {
                frameView.bringToFront();
            }
        }
        if (familyFullscreenButton != null && familyFullscreenDialog == null) {
            familyFullscreenButton.setVisibility(View.VISIBLE);
            familyFullscreenButton.bringToFront();
        }
        familyWaitingView.setVisibility(View.GONE);
        familyScreenLabelView.setVisibility(View.VISIBLE);
        familyScreenSurface.setVisibility(View.VISIBLE);
        setStatus(stateText);
    }

    private void resizeFamilyScreenSurface(int height) {
        if (familyScreenSurface == null) {
            return;
        }
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) familyScreenSurface.getLayoutParams();
        if (params == null) {
            params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
            params.setMargins(0, 0, 0, dp(10));
        } else {
            params.height = familyLastActive && familyMediaReady ? 0 : height;
            params.weight = familyLastActive && familyMediaReady ? 1f : 0f;
        }
        familyScreenSurface.setLayoutParams(params);
    }

    private void startFamilyPollLoop() {
        main.removeCallbacks(familyPollLoopRunnable);
        main.post(familyPollLoopRunnable);
    }

    private void pollFamilyOnce() {
        if (familyPollInFlight || familyEnding) {
            return;
        }
        familyPollInFlight = true;
        if (rtcVideoReady && lastRtcFrameAtMs > 0
                && System.currentTimeMillis() - lastRtcFrameAtMs > RTC_STALE_FRAME_MS) {
            rtcVideoReady = false;
        }
        statusIo.execute(() -> {
            try {
                String encoded = encoded(pairCode);
                String token = encoded(authToken);
                JSONObject help = NetworkClient.getJson(baseUrl, "/api/help?pairCode=" + encoded + "&authToken=" + token);
                boolean active = help.optBoolean("active", false);
                boolean wasActive = familyLastActive || !familyLastSessionId.isEmpty();
                familyLastActive = active;
                familyControlAllowed = help.optBoolean("controlAllowed", false);
                boolean controlRequested = help.optBoolean("controlRequested", false);
                String controlDecision = help.optString("controlDecision", controlRequested ? "pending" : "idle");
                String controlReason = help.optString("controlReason", "");
                String controlUpdatedAt = help.optString("controlUpdatedAt", "");
                if (!active) {
                    main.post(() -> {
                        familyLastSessionId = "";
                        stopFamilyWebRtc();
                        clearFamilyScreen();
                        setFamilySessionActive(false);
                        setStatus(wasActive
                                ? "本次协助已结束"
                                : "连接正常");
                    });
                    return;
                }
                boolean helperIsCurrent = !help.has("helperIsCurrent") || help.optBoolean("helperIsCurrent", false);
                String helperName = help.optString("helperName", "家人");
                if (!helperIsCurrent) {
                    familyControlAllowed = false;
                    main.post(() -> {
                        familyLastSessionId = "";
                        stopFamilyWebRtc();
                        clearFamilyScreen();
                        setFamilySessionActive(false);
                        setStatus(helperName + " 已在协助长辈。本次只能由一位家属操作，结束后你可以继续接入。");
                    });
                    return;
                }
                String elderName = help.optString("elderName", "长辈");
                String frameUpdatedAt = help.optString("frameUpdatedAt", "");
                String sessionId = help.optString("sessionId", "");
                main.post(() -> {
                    setFamilySessionActive(true);
                    updateFamilyControlState(controlRequested, familyControlAllowed,
                            controlDecision, controlReason, controlUpdatedAt);
                    if (!sessionId.equals(familyLastSessionId)
                            || (isWebRtcEnabled() && familyRtcClient == null)) {
                        familyLastSessionId = sessionId;
                        lastFrameReceivedAtMs = 0;
                        lastFrameUpdatedAt = "";
                        rtcVideoReady = false;
                        if (isWebRtcEnabled()) {
                            startFamilyWebRtc();
                            scheduleWebRtcFallback(sessionId);
                        }
                    }
                    if (lastFrameReceivedAtMs == 0) {
                        setStatus("正在连接 " + elderName + " 的屏幕...");
                    } else {
                        setStatus(familyControlAllowed
                                ? "正在协助 " + elderName + " · 远程操作已授权"
                                : "正在协助 " + elderName + " · 点画面可提示");
                    }
                });
                if (!isWebRtcEnabled() || !rtcVideoReady) {
                    requestLatestFrame(encoded, token, frameUpdatedAt);
                }
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新输入长辈给你的绑定码。"));
                    return;
                }
                familyLastActive = false;
                main.post(() -> setStatus("连接服务暂时不可用，正在重试"));
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
                    if (bitmap != null) {
                        lastFrameUpdatedAt = frameUpdatedAt;
                        lastFrameReceivedAtMs = System.currentTimeMillis();
                        if (frameView == null) {
                            buildFrameView();
                        }
                        frameView.setImageBitmap(bitmap);
                    }
                    if (bitmap != null && familyScreenSurface != null) {
                        if (!isWebRtcEnabled()) {
                            showFamilyMedia(frameView, "屏幕画面已连接");
                        } else if (!rtcVideoReady) {
                            showFamilyMedia(frameView, "实时连接中，当前显示备用画面");
                        }
                    }
                });
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
                    return;
                }
                main.post(() -> setStatus("画面接收较慢，正在重试"));
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
                        .put("sessionId", familyLastSessionId)
                        .put("frameUpdatedAt", lastFrameUpdatedAt);
                NetworkClient.postJson(baseUrl, "/api/annotation", payload);
                main.post(() -> setStatus("画圈提示已发送，几秒后会自动消失。"));
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
                    return;
                }
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
                main.post(() -> {
                    updateFamilyControlState(true, false, "pending", "", "");
                    setStatus("已请求长辈授权。对方同意后，这里会自动显示“已授权”。");
                    pollFamilyOnce();
                });
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
                    return;
                }
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("请求远程点击失败：" + e.getMessage());
                });
            } finally {
                remoteRequestInProgress = false;
            }
        });
    }

    private void updateFamilyControlButton(boolean requested, boolean allowed) {
        updateFamilyControlState(requested, allowed, requested ? "pending" : (allowed ? "allowed" : "idle"), "", "");
    }

    private void updateFamilyControlState(boolean requested, boolean allowed, String decision,
                                          String reason, String updatedAt) {
        if (familyControlRequestButton == null) {
            return;
        }
        applyControlButtonState(familyControlRequestButton, familyRemoteButton, requested, allowed, decision);
        applyControlButtonState(familyFullscreenControlRequestButton, familyFullscreenRemoteButton,
                requested, allowed, decision);
        updateFullscreenControlState();
        maybeShowFamilyControlResult(decision, updatedAt);
    }

    private void applyControlButtonState(Button requestButton, Button remoteButton,
                                         boolean requested, boolean allowed, String decision) {
        if (requestButton == null) return;
        requestButton.setTag(null);
        if (allowed) {
            requestButton.setVisibility(View.GONE);
            if (remoteButton != null) remoteButton.setVisibility(View.VISIBLE);
        } else if (requested) {
            requestButton.setVisibility(View.VISIBLE);
            requestButton.setText("等待长辈授权");
            requestButton.setEnabled(false);
            requestButton.setAlpha(0.72f);
            if (remoteButton != null) remoteButton.setVisibility(View.GONE);
        } else {
            requestButton.setVisibility(View.VISIBLE);
            requestButton.setText("denied".equals(decision) || "setup_required".equals(decision)
                    ? "再次申请远程操作" : "申请远程操作");
            requestButton.setEnabled(true);
            requestButton.setAlpha(1f);
            if (remoteButton != null) remoteButton.setVisibility(View.GONE);
        }
    }

    private void updateFullscreenControlState() {
        if (familyFullscreenModeView == null) return;
        familyFullscreenModeView.setText(familyControlAllowed ? "远程操作" : "画圈提示");
        familyFullscreenModeView.setBackground(rounded(
                familyControlAllowed ? 0xCC166534 : 0xCC243044,
                dp(8),
                0x004B5563
        ));
    }

    private void maybeShowFamilyControlResult(String decision, String updatedAt) {
        String seenAt = prefs.getString("familyControlResultSeenAt", "");
        if (!("denied".equals(decision) || "setup_required".equals(decision))
                || updatedAt.isEmpty() || updatedAt.equals(seenAt)
                || !appInForeground || !"family".equals(currentPage) || isFinishing()) {
            return;
        }
        prefs.edit().putString("familyControlResultSeenAt", updatedAt).apply();
        boolean setupRequired = "setup_required".equals(decision);
        new AlertDialog.Builder(this)
                .setTitle(setupRequired ? "长辈尚未完成设置" : "长辈本次未允许")
                .setMessage(setupRequired
                        ? "长辈返回应用时没有开启辅助服务，本次远程操作申请已结束。你仍可继续画圈提示，稍后再申请。"
                        : "本次不能直接操作长辈手机。你仍可继续查看屏幕并发送画圈提示。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void sendRemoteTap(float x, float y) {
        if (!ensureFamilyControlReady()) {
            return;
        }
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
                handleRemoteControlSendError(e);
            }
        });
    }

    private void sendRemoteSwipe(float startX, float startY, float endX, float endY, long durationMs) {
        if (!ensureFamilyControlReady()) {
            return;
        }
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("startX", startX)
                        .put("startY", startY)
                        .put("endX", endX)
                        .put("endY", endY)
                        .put("durationMs", durationMs);
                NetworkClient.postJson(baseUrl, "/api/control/swipe", payload);
                main.post(() -> setStatus("已发送远程滑动。"));
            } catch (Exception e) {
                handleRemoteControlSendError(e);
            }
        });
    }

    private void sendRemoteGlobal(String action) {
        if (!ensureFamilyControlReady()) {
            return;
        }
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("action", action);
                NetworkClient.postJson(baseUrl, "/api/control/global", payload);
                main.post(() -> setStatus("已发送远程系统操作。"));
            } catch (Exception e) {
                handleRemoteControlSendError(e);
            }
        });
    }

    private void handleRemoteControlSendError(Exception e) {
        if (isAuthFailure(e)) {
            main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
            return;
        }
        if (isControlNotAllowed(e)) {
            familyControlAllowed = false;
            main.post(() -> setStatus("长辈尚未授权远程操作，请先请求授权。"));
            return;
        }
        main.post(() -> setStatus("远程操作失败：" + friendlyError(e)));
    }

    private boolean ensureFamilyControlReady() {
        if (familyControlAllowed) {
            return true;
        }
        setStatus("长辈同意后才能远程操作。你也可以先点画面给长辈画圈。");
        pollFamilyOnce();
        return false;
    }

    private boolean isControlNotAllowed(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("control is not allowed");
    }

    private void handleRemoteTouchOnImage(float endX, float endY) {
        float distance = (float) Math.hypot(endX - screenTouchStartX, endY - screenTouchStartY);
        float[] start = normalizedImagePoint(frameView, screenTouchStartX, screenTouchStartY);
        float[] end = normalizedImagePoint(frameView, endX, endY);
        if (distance > dp(42)) {
            sendRemoteSwipe(start[0], start[1], end[0], end[1], gestureDuration());
        } else {
            sendRemoteTap(end[0], end[1]);
        }
    }

    private long gestureDuration() {
        long elapsed = System.currentTimeMillis() - screenTouchStartAtMs;
        return Math.max(180, Math.min(700, elapsed));
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
        main.removeCallbacks(elderAnnotationLoopRunnable);
        main.post(elderAnnotationLoopRunnable);
    }

    private void pollElderBindLoop() {
        main.removeCallbacks(elderBindLoopRunnable);
        main.post(elderBindLoopRunnable);
    }

    private void pollElderBindOnce() {
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl, "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                int familyCount = boundFamilyCount(family);
                boolean invitePending = family != null && family.optBoolean("invitePending", false);
                boolean supportsMultipleFamily = family != null && family.has("familyMemberCount");
                org.json.JSONArray pendingRequests = family != null ? family.optJSONArray("pendingBindRequests") : null;
                if (pendingRequests != null && pendingRequests.length() > 0) {
                    JSONObject pending = pendingRequests.optJSONObject(0);
                    if (pending != null) {
                        String requestId = pending.optString("id", "");
                        String lastPrompted = prefs.getString("lastBindApprovalPromptId", "");
                        if (!requestId.isEmpty() && !requestId.equals(lastPrompted)) {
                            prefs.edit().putString("lastBindApprovalPromptId", requestId).apply();
                            main.post(() -> showPendingBindApproval(pending));
                        }
                    }
                }
                if (familyCount > 0) {
                    prefs.edit()
                            .putBoolean("familyBound", true)
                            .apply();
                    if (!elderInviteBoundShown) {
                        elderInviteBoundShown = true;
                        elderBindPolling = false;
                        main.post(() -> showElderBoundSuccess(familyCount, supportsMultipleFamily && invitePending));
                    }
                } else if (!invitePending) {
                    prefs.edit().remove("pendingInviteCode").apply();
                    main.post(() -> {
                        elderBindPolling = false;
                        setStatus("绑定码已过期。点“重新生成绑定码”可继续。");
                    });
                    return;
                } else {
                    main.post(() -> setStatus("正在等待家属输入绑定码..."));
                }
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
                    return;
                }
                main.post(() -> setStatus("正在等待家属绑定。网络检查失败：" + e.getMessage()));
            }
        });
    }

    private void showPendingBindApproval(JSONObject pending) {
        String requestId = pending.optString("id", "");
        String name = pending.optString("requesterName", "家属");
        String phone = pending.optString("requesterPhone", "");
        new AlertDialog.Builder(this)
                .setTitle("确认添加家属")
                .setMessage(name + (phone.isEmpty() ? "" : "（" + phone + "）") + " 想绑定为你的家属。确认后，对方可以在你求助时接收请求。")
                .setPositiveButton("同意绑定", (dialog, which) -> confirmPendingBind(requestId, true))
                .setNegativeButton("不同意", (dialog, which) -> confirmPendingBind(requestId, false))
                .show();
    }

    private void confirmPendingBind(String requestId, boolean approved) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/bind/confirm", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("requestId", requestId)
                        .put("approved", approved));
                JSONObject family = result.optJSONObject("family");
                int familyCount = boundFamilyCount(family);
                prefs.edit().putBoolean("familyBound", familyCount > 0).apply();
                main.post(() -> setStatus(approved ? "已同意绑定。家属现在可以接收你的求助。" : "已拒绝本次绑定申请。"));
            } catch (Exception e) {
                main.post(() -> setStatus("处理绑定申请失败：" + friendlyError(e)));
            }
        });
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
                    main.post(() -> setStatus("家人提示：" + label));
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
                boolean remoteActive = family.optBoolean("active", false);
                if (elderUiAssisting && !remoteActive) {
                    prefs.edit()
                            .putString("pendingAssistMessage", "家人已结束本次协助。需要帮助时，可以再次发起求助。")
                            .putBoolean("pendingAssistEndedEvent", true)
                            .apply();
                    main.post(this::maybeShowAssistEndedEvent);
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
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    main.post(() -> clearBindingAndShowSetup("绑定已失效，请重新完成亲属绑定。"));
                }
            } finally {
                elderStatusInFlight = false;
            }
        });
    }

    private boolean isAuthFailure(Exception e) {
        return isAuthFailureText(e == null ? "" : e.getMessage());
    }

    private boolean isAuthFailureText(String message) {
        return message != null && (message.contains("HTTP 403") || message.contains("not bound"));
    }

    private void clearBindingAndShowSetup(String message) {
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        stopFamilyWebRtc();
        authToken = "";
        memberRole = "";
        prefs.edit()
                .remove("authToken")
                .remove("memberRole")
                .remove(PREF_ASSIST_SESSION_ID)
                .remove("pendingInviteCode")
                .remove("pendingControlRequestAt")
                .remove("controlSetupRequestAt")
                .remove("handledControlRequestAt")
                .remove("notifiedControlRequestAt")
                .putBoolean("familyBound", false)
                .putBoolean("assistActive", false)
                .apply();
        showSetup();
        setStatus(message);
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
            main.post(() -> stopAssistance("家人长时间没有连接，本次求助已结束。需要帮助时可以重新发起。"));
        }
    }

    private void stopAssistance(String message) {
        endHelpRequest();
        stopCaptureServices();
        markAssistanceStoppedLocal();
        showElder();
        setStatus(message);
    }

    private void stopCaptureServices() {
        stopService(new Intent(this, CaptureService.class));
        stopService(new Intent(this, WebRtcScreenService.class));
        stopService(new Intent(this, AnnotationOverlayService.class));
    }

    private void markAssistanceStoppedLocal() {
        prefs.edit()
                .putBoolean("remoteControlAllowed", false)
                .putBoolean("assistActive", false)
                .remove("assistStartedAtMs")
                .remove(PREF_ASSIST_SESSION_ID)
                .remove("pendingControlRequestAt")
                .remove("controlSetupRequestAt")
                .apply();
    }

    private void maybeShowAssistEndedEvent() {
        if (!appInForeground || assistEndPromptShowing || isFinishing()
                || !prefs.getBoolean("pendingAssistEndedEvent", false)) {
            return;
        }
        assistEndPromptShowing = true;
        prefs.edit()
                .putBoolean("pendingAssistEndedEvent", false)
                .remove("pendingAssistMessage")
                .remove("pendingControlRequestAt")
                .remove("controlSetupRequestAt")
                .apply();
        AssistNotifier.cancelAssistEndedNotification(this);
        stopCaptureServices();
        markAssistanceStoppedLocal();
        if ("elder".equals(currentPage)) {
            showElder();
        }
        new AlertDialog.Builder(this)
                .setTitle("本次协助已结束")
                .setMessage("家人已结束本次协助，屏幕共享已经停止。需要帮助时，可以再次发起求助。")
                .setPositiveButton("知道了", (dialog, which) -> assistEndPromptShowing = false)
                .setOnCancelListener(dialog -> assistEndPromptShowing = false)
                .show();
    }

    private void handleRemoteControlRequest(String updatedAt) {
        if (!appInForeground) {
            AssistNotifier.handleControlRequest(this, updatedAt);
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
                        : "远程点击需要先开启“亲情帮帮”辅助服务。点下面按钮后会尽量直接打开亲情帮帮的开关页。")
                .setPositiveButton(accessibilityReady ? "允许本次协助" : "去开启辅助服务", (dialog, which) -> {
                    remotePromptShowing = false;
                    if (accessibilityReady) {
                        markControlRequestHandled(updatedAt);
                        allowRemoteControl(true);
                        showElder();
                    } else {
                        prefs.edit()
                                .putString("controlSetupRequestAt", updatedAt)
                                .putString("handledControlRequestAt", updatedAt)
                                .remove("pendingControlRequestAt")
                                .commit();
                        AssistNotifier.cancelControlRequestNotification(this);
                        refreshElderOnResume = true;
                        elderScreenVisible = true;
                        openAccessibilityServiceSettings();
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
        AssistNotifier.cancelControlRequestNotification(this);
        prefs.edit()
                .putString("handledControlRequestAt", updatedAt)
                .remove("pendingControlRequestAt")
                .commit();
    }

    private void showAccessibilitySetupIncomplete() {
        if (isFinishing()) return;
        setStatus("未开启辅助服务，本次没有授权远程操作。家属仍可用画圈提示。" );
        new AlertDialog.Builder(this)
                .setTitle("本次未开启远程操作")
                .setMessage("刚才没有开启“亲情帮帮”辅助服务，本次申请已结束，并已告知家属。需要时，家属可以重新申请。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void saveSetup(EditText serverInput, EditText nameInput) {
        baseUrl = sanitizeRelayUrl(serverInput.getText().toString());
        displayName = nameInput.getText().toString().trim();
        if (displayName.isEmpty()) {
            displayName = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        prefs.edit()
                .putString("baseUrl", baseUrl)
                .putString("displayName", displayName)
                .apply();
    }

    private void testRelayConnection(Button sourceButton) {
        setButtonBusy(sourceButton, "检查中...");
        setStatus("正在检查连接...");
        statusIo.execute(() -> {
            try {
                NetworkClient.getJson(baseUrl, "/health");
                main.post(() -> setStatus("连接服务正常"));
            } catch (Exception e) {
                main.post(() -> setStatus("连接检查失败：" + friendlyError(e)));
            } finally {
                main.post(() -> restoreButton(sourceButton));
            }
        });
    }

    private String friendlyError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        if (message.contains("invalid or expired invite code")) {
            return "绑定码无效或已过期，请让长辈重新生成";
        }
        if (message.contains("family member limit reached")) {
            return "已达到 5 位家属上限";
        }
        if (message.contains("assist session is active")) {
            return "当前正在协助，请结束后再操作";
        }
        if (message.contains("no family member is bound")) {
            return "还没有家属完成绑定";
        }
        if (message.contains("another family member is assisting")) {
            return "已有其他家属正在协助";
        }
        if (message.length() > 180) {
            return message.substring(0, 180) + "...";
        }
        return message;
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
        if (prefs.getBoolean("assistActive", false)) {
            stopCaptureServices();
            endHelpRequest();
            markAssistanceStoppedLocal();
            setStatus("正在重新发起求助...");
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
        openAccessibilityServiceSettings();
        setStatus("请开启“亲情帮帮”辅助服务，返回后再点“请家人帮忙”。");
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

    private boolean isLoggedIn() {
        return accountToken != null && !accountToken.isEmpty();
    }

    private boolean ensureLoggedIn(String afterRole) {
        if (isLoggedIn()) {
            return true;
        }
        showLogin(afterRole);
        return false;
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
        if (!prefs.getBoolean("webRtcAutoDefaultV2", false)) {
            editor.putBoolean("webRtcEnabled", true);
            editor.putBoolean("webRtcAutoDefaultV2", true);
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
        allowRemoteControl(allowed, allowed ? "" : "declined");
    }

    private void allowRemoteControl(boolean allowed, String reason) {
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
                        .put("allowed", allowed)
                        .put("reason", reason);
                NetworkClient.postJson(baseUrl, "/api/control/allow", payload);
            } catch (Exception e) {
                prefs.edit()
                        .putBoolean("remoteControlAllowed", false)
                        .apply();
                main.post(() -> setStatus("授权同步失败，请保持网络畅通后让家属重新请求：" + friendlyError(e)));
            }
        });
    }

    private void showAccessibilityGuide() {
        new AlertDialog.Builder(this)
                .setTitle("需要开启辅助服务")
                .setMessage(accessibilityGuideText())
                .setPositiveButton("去开启", (dialog, which) -> {
                    refreshElderOnResume = true;
                    elderScreenVisible = true;
                    openAccessibilityServiceSettings();
                })
                .setNegativeButton("先不用", null)
                .show();
    }

    private String accessibilityGuideText() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        String common = "接下来会打开系统设置。请找到“亲情帮帮”，打开开关，然后按返回键回到这里。";
        if (brand.contains("vivo")) {
            return common + "\n\nvivo 常见位置：无障碍 > 已下载的服务 > 亲情帮帮 > 开启。";
        }
        if (brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")) {
            return common + "\n\nOPPO/realme 常见位置：无障碍 > 已下载的应用 > 亲情帮帮 > 开启。";
        }
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            return common + "\n\n小米/Redmi 常见位置：更多设置 > 无障碍 > 已下载的应用 > 亲情帮帮 > 开启。";
        }
        if (brand.contains("huawei") || brand.contains("honor")) {
            return common + "\n\n华为/荣耀常见位置：辅助功能 > 无障碍 > 已安装的服务 > 亲情帮帮 > 开启。";
        }
        if (brand.contains("samsung")) {
            return common + "\n\n三星常见位置：辅助功能 > 已安装的应用程序 > 亲情帮帮 > 开启。";
        }
        return common + "\n\n如果列表较长，可以让家属用画圈提示帮你找到“亲情帮帮”。";
    }

    private void openAccessibilityServiceSettings() {
        ComponentName componentName = new ComponentName(this, SensitiveAccessibilityService.class);
        Intent detailIntent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .setData(Uri.parse("package:" + getPackageName()));
        detailIntent.putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME", componentName.flattenToString());
        detailIntent.putExtra("android.intent.extra.COMPONENT_NAME", componentName.flattenToString());
        try {
            startActivity(detailIntent);
            setStatus("已尝试直接打开“亲情帮帮”辅助服务开关页。打开开关后按返回键。");
        } catch (Exception ignored) {
            Intent fallback = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
            setStatus(accessibilityListFallbackText());
        }
    }

    private String accessibilityListFallbackText() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        if (brand.contains("vivo")) {
            return "系统没有开放直达开关页。请点“亲情帮帮”，打开开关后按返回键。";
        }
        if (brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")) {
            return "系统没有开放直达开关页。请进入“已下载的应用/已安装服务”，点“亲情帮帮”并开启。";
        }
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            return "系统没有开放直达开关页。请进入“已下载的应用”，点“亲情帮帮”并开启。";
        }
        return "系统没有开放直达开关页。请在列表里点“亲情帮帮”，打开开关后按返回键。";
    }

    private String migrateRelayUrl(String value) {
        String normalized = sanitizeRelayUrl(value);
        prefs.edit().putString("baseUrl", normalized).apply();
        return normalized;
    }

    private String sanitizeRelayUrl(String value) {
        String normalized = NetworkClient.normalizeBaseUrl(value);
        normalized = stripRelayPath(normalized);
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && (normalized.contains("127.0.0.1") || normalized.contains("10.0.2.2"))) {
            return normalized;
        }
        if (normalized.isEmpty()
                || normalized.contains("192.168.")
                || normalized.contains("10.0.2.2")
                || normalized.contains("127.0.0.1")
                || normalized.contains("localhost")
                || (normalized.contains(".github.dev") && !normalized.contains(".app.github.dev"))) {
            normalized = DEFAULT_RELAY_URL;
        }
        return normalized;
    }

    private String stripRelayPath(String value) {
        try {
            URL url = new URL(value);
            return url.getProtocol() + "://" + url.getHost() + (url.getPort() >= 0 ? ":" + url.getPort() : "");
        } catch (Exception ignored) {
            return value;
        }
    }

    private JSONObject postJsonWithRelayFallback(String path, JSONObject payload) throws Exception {
        try {
            return NetworkClient.postJson(baseUrl, path, payload);
        } catch (Exception e) {
            if (isRelayRouteNotFound(e) && resetRelayToDefaultIfNeeded()) {
                return NetworkClient.postJson(baseUrl, path, payload);
            }
            throw e;
        }
    }

    private boolean isRelayRouteNotFound(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("HTTP 404");
    }

    private boolean resetRelayToDefaultIfNeeded() {
        if (DEFAULT_RELAY_URL.equals(baseUrl)) {
            return false;
        }
        baseUrl = DEFAULT_RELAY_URL;
        prefs.edit().putString("baseUrl", baseUrl).apply();
        return true;
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

    private boolean isWebRtcEnabled() {
        return prefs.getBoolean("webRtcEnabled", false);
    }

    private String webRtcButtonText() {
        return isWebRtcEnabled() ? "实时画面：已开启" : "实时画面：已关闭";
    }

    private String elderPrimaryButtonText() {
        if (needsOverlayPermission()) {
            return "允许画圈提示";
        }
        return "请家人帮忙";
    }

    private String elderCurrentStepTitle() {
        if (needsOverlayPermission()) {
            return "先完成一次权限设置";
        }
        return "准备发起求助";
    }

    private String elderAssistHintText() {
        if (prefs.getBoolean("assistActive", false)) {
            return "请打开需要帮助的应用。家人可以查看屏幕并给出提示；结束时回到这里点“结束本次求助”。";
        }
        if (needsOverlayPermission()) {
            return "只需要设置一次。点蓝色按钮后，在系统页允许“显示在其他应用上层”，再按返回键回来。";
        }
        if (!isAccessibilityServiceEnabled()) {
            return "点“请家人帮忙”，再在系统弹窗中点“立即开始”。";
        }
        return "点“请家人帮忙”，确认屏幕共享后，家人就能看到画面。";
    }

    private String bindingStatusText() {
        String login = isLoggedIn() ? "账号：" + accountPhone + "。" : "账号：未登录。";
        if (authToken.isEmpty()) {
            return login + "\n绑定状态：未绑定。";
        }
        String role = "elder".equals(memberRole) ? "长辈" : "家属";
        return login + "\n绑定状态：已绑定为" + role + "。";
    }

    private String appVersionText() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + "（" + info.versionCode + "）";
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private int boundFamilyCount(JSONObject family) {
        if (family == null) {
            return 0;
        }
        if (family.has("familyMemberCount")) {
            return Math.max(0, family.optInt("familyMemberCount", 0));
        }
        // Legacy relay only exposes a total that includes the elder device.
        return Math.max(0, family.optInt("memberCount", 0) - 1);
    }

    private String encoded(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
            boolean empty = text == null || text.trim().isEmpty();
            boolean quietFamilyUpdate = "family".equals(currentPage) && !isImportantStatus(text);
            status.setVisibility(empty || quietFamilyUpdate ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isImportantStatus(String text) {
        if (text == null) return false;
        return text.contains("失败")
                || text.contains("不可用")
                || text.contains("已失效")
                || text.contains("被占用")
                || text.contains("请重新")
                || text.contains("请检查")
                || text.contains("未完成")
                || text.contains("无法")
                || text.contains("异常");
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
        AssistNotifier.createControlChannel(this);
    }

    private LinearLayout verticalRoot() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(8), dp(14), dp(16));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(COLOR_BG);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            if (Build.VERSION.SDK_INT >= 30) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            view.setPadding(dp(14), dp(8) + topInset, dp(14), dp(16));
            return insets;
        });
        return layout;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(fullWidthParams());
        return row;
    }

    private LinearLayout bottomNav(String current) {
        LinearLayout nav = horizontalRow();
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(5), dp(4), dp(5));
        nav.setBackground(rounded(0xFFFFFFFF, 0, COLOR_LINE));
        nav.setTag("bottomNav");
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, dp(8), 0, 0);
        nav.setLayoutParams(params);
        addNavItem(nav, R.drawable.ic_nav_home, "首页", "home".equals(current), v -> showSetup());
        addNavItem(nav, R.drawable.ic_nav_elder, "长辈", "elder".equals(current), v -> showElder());
        addNavItem(nav, R.drawable.ic_nav_family, "家属", "family".equals(current), v -> showFamily());
        addNavItem(nav, R.drawable.ic_nav_profile, "我的", "profile".equals(current), v -> showProfile());
        return nav;
    }

    private void addNavItem(LinearLayout row, int iconRes, String text, boolean selected, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setOnClickListener(listener);
        item.setPadding(0, dp(2), 0, dp(1));
        item.setBackgroundColor(0x00FFFFFF);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(selected ? COLOR_BLUE_DARK : COLOR_MUTED);
        iconView.setContentDescription(text);

        TextView labelView = new TextView(this);
        labelView.setText(text);
        labelView.setTextSize(12);
        labelView.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextColor(selected ? COLOR_BLUE_DARK : COLOR_MUTED);
        labelView.setIncludeFontPadding(false);
        labelView.setPadding(0, dp(3), 0, dp(3));

        View indicator = new View(this);
        indicator.setBackgroundColor(selected ? COLOR_BLUE_DARK : 0x00FFFFFF);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(dp(18), dp(3));
        indicatorParams.setMargins(0, dp(1), 0, 0);

        item.addView(iconView, new LinearLayout.LayoutParams(dp(23), dp(23)));
        item.addView(labelView);
        item.addView(indicator, indicatorParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(item, params);
    }

    private LinearLayout pageHeader(String heading, Runnable onBack) {
        LinearLayout bar = horizontalRow();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(2), 0, dp(8));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(COLOR_TEXT);
        back.setBackground(rounded(0xFFFFFFFF, dp(8), COLOR_LINE));
        back.setContentDescription("返回");
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> onBack.run());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        titleParams.setMargins(dp(10), 0, dp(40), 0);
        bar.addView(title, titleParams);
        return bar;
    }

    private void addControlButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(4), dp(6), dp(4), dp(2));
        button.setLayoutParams(params);
        row.addView(button);
    }

    private View scroll(View child) {
        View nav = detachBottomNav(child);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.addView(child);
        if (nav == null) {
            return scroll;
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        page.addView(nav);
        return page;
    }

    private View detachBottomNav(View child) {
        if (!(child instanceof LinearLayout)) {
            return null;
        }
        LinearLayout layout = (LinearLayout) child;
        int last = layout.getChildCount() - 1;
        if (last < 0) {
            return null;
        }
        View nav = layout.getChildAt(last);
        if (!"bottomNav".equals(nav.getTag())) {
            return null;
        }
        layout.removeViewAt(last);
        return nav;
    }

    private LinearLayout hero(String heading, String subheading) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.START);
        layout.setPadding(dp(2), dp(6), dp(2), dp(10));
        layout.setBackgroundColor(COLOR_BG);
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(4));
        layout.setLayoutParams(params);

        TextView title = sectionTitle(heading);
        title.setTextSize(24);
        title.setTextColor(COLOR_TEXT);
        title.setPadding(0, 0, 0, dp(3));
        layout.addView(title);

        TextView body = caption(subheading);
        body.setTextColor(COLOR_MUTED);
        body.setPadding(0, 0, 0, 0);
        layout.addView(body);

        View accent = new View(this);
        accent.setBackgroundColor(COLOR_BLUE);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(36), dp(3));
        accentParams.setMargins(0, dp(8), 0, 0);
        layout.addView(accent, accentParams);
        return layout;
    }

    private TextView compactPageTitle(String heading) {
        TextView title = sectionTitle(heading);
        title.setTextSize(22);
        title.setPadding(dp(2), dp(4), 0, dp(10));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(2));
        title.setLayoutParams(params);
        return title;
    }

    private LinearLayout card(String heading, String subheading) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(rounded(COLOR_SURFACE, dp(8), COLOR_LINE));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(10));
        layout.setLayoutParams(params);

        TextView title = sectionTitle(heading);
        layout.addView(title);
        if (subheading != null && !subheading.trim().isEmpty()) {
            layout.addView(caption(subheading));
        }
        return layout;
    }

    private TextView screenLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_TEXT);
        view.setPadding(dp(2), dp(2), 0, dp(6));
        view.setGravity(Gravity.START);
        view.setLayoutParams(fullWidthParams());
        return view;
    }

    private FrameLayout screenSurface(View child) {
        FrameLayout surface = new FrameLayout(this);
        surface.setPadding(dp(3), dp(3), dp(3), dp(3));
        surface.setBackground(rounded(0xFF0F172A, dp(8), COLOR_LINE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(500)
        );
        params.setMargins(0, 0, 0, dp(10));
        surface.setLayoutParams(params);
        surface.addView(child, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return surface;
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
        view.setTextSize(14);
        view.setTextColor(0xFF4B5563);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(rounded(0xFFF8FAFC, dp(8), COLOR_LINE));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView stableNotice(String text) {
        TextView view = notice(text);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMaxLines(2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        params.setMargins(0, 0, 0, dp(8));
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
        view.setBackground(rounded(0xFFF8FAFC, dp(8), COLOR_LINE));
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
        button.setBackground(rounded(COLOR_BLUE, dp(8), COLOR_BLUE));
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setLayoutParams(buttonParams());
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(COLOR_BLUE_DARK);
        button.setBackground(rounded(0xFFF4F7FB, dp(8), 0xFFCAD5E3));
        return button;
    }

    private Button dangerButton(String text) {
        Button button = primaryButton(text);
        button.setBackground(rounded(COLOR_RED, dp(8), COLOR_RED));
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
