package com.qinqing.bangbang;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.net.URL;
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
    private static final String PREF_ASSIST_SESSION_ID = "assistSessionId";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService statusIo = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaIo = Executors.newSingleThreadExecutor();
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
    private boolean elderUiAssisting;
    private boolean refreshElderOnResume;
    private boolean remotePromptShowing;
    private boolean appInForeground;
    private boolean familyPollInFlight;
    private boolean familyFrameInFlight;
    private boolean elderStatusInFlight;
    private boolean familyControlAllowed;
    private Button familyControlRequestButton;
    private Button familyEndButton;
    private LinearLayout familyRemotePanel;
    private TextView familyScreenLabelView;
    private LinearLayout familyScreenSurface;
    private LinearLayout familyWaitingView;
    private TextView familyWaitingTitle;
    private TextView familyWaitingCaption;
    private Button familyChangeBindingButton;
    private boolean rtcVideoReady;
    private boolean rtcTrackAttached;
    private boolean familyMediaReady;
    private int rtcFrameWidth;
    private int rtcFrameHeight;
    private int rtcFrameRotation;
    private boolean assistEndPromptShowing;
    private boolean familyLastActive;
    private boolean elderInviteBoundShown;
    private String familyLastSessionId = "";
    private String currentPage = "home";
    private boolean captureRequestInProgress;
    private boolean inviteInProgress;
    private boolean bindInProgress;
    private boolean remoteRequestInProgress;
    private boolean familyEnding;
    private long lastAnnotationSentAtMs;
    private long lastFrameReceivedAtMs;
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
        prefs.edit().putBoolean("appForeground", false).apply();
        main.removeCallbacks(assistEndedUiLoop);
        main.removeCallbacks(familyPollLoopRunnable);
        main.removeCallbacks(elderAnnotationLoopRunnable);
        main.removeCallbacks(elderBindLoopRunnable);
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
        prefs.edit().putBoolean("appForeground", true).apply();
        main.removeCallbacks(assistEndedUiLoop);
        maybeShowAssistEndedEvent();
        main.postDelayed(assistEndedUiLoop, 700);
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
        main.removeCallbacks(assistEndedUiLoop);
        prefs.edit().putBoolean("appForeground", false).apply();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if ("home".equals(currentPage)) {
            super.onBackPressed();
        } else if ("settings".equals(currentPage) || "privacy".equals(currentPage)) {
            showProfile();
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
                setStatus(isWebRtcEnabled()
                        ? "实时协助已开始，家属正在连接。需要结束时点“停止协助”。"
                        : "协助已开始，家属正在连接。需要结束时点“停止协助”。");
            });
        } else if (requestCode == REQUEST_CAPTURE) {
            prefs.edit().putBoolean("assistActive", false).apply();
            endHelpRequest();
            showElder();
            setStatus("你取消了屏幕共享授权。");
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

        root.addView(hero("亲情帮帮", "爸妈点一下，家人看屏幕帮忙"));
        status = notice(bindingStatusText());

        Button elderButton = primaryButton("我是长辈");
        elderButton.setTextSize(23);
        Button familyButton = secondaryButton("我是家属");
        familyButton.setTextSize(22);
        elderButton.setOnClickListener(v -> showElder());
        familyButton.setOnClickListener(v -> showFamily());

        LinearLayout elderCard = card("长辈手机", "用于发起求助。需要帮忙时，只点一个大按钮。");
        elderCard.addView(elderButton);
        root.addView(elderCard);

        LinearLayout familyCard = card("家属手机", "用于接收求助、查看屏幕，并给长辈画圈提示。");
        familyCard.addView(familyButton);
        root.addView(familyCard);

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
        root.addView(hero("我的", "连接、安全和隐私"));
        status = notice("低频设置集中在这里。");

        Button connectionButton = secondaryButton("连接设置");
        Button safetyButton = secondaryButton("安全与权限设置");
        Button privacyButton = secondaryButton("隐私政策");

        connectionButton.setOnClickListener(v -> showAdvancedSettings());
        safetyButton.setOnClickListener(v -> {
            if (isBoundAs("elder")) {
                showSafetySettings();
            } else {
                setStatus("安全与权限主要用于长辈手机。请在长辈手机上打开。");
            }
        });
        privacyButton.setOnClickListener(v -> showPrivacyPolicy());

        LinearLayout settingsCard = card("设置", "");
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

    private void showAdvancedSettings() {
        currentPage = "settings";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();
        root.addView(pageHeader("连接设置", this::showProfile));
        status = notice("连接服务已自动配置，通常无需修改。");

        EditText serverInput = input("服务地址", baseUrl);
        EditText codeInput = input("家庭码", pairCode);
        EditText nameInput = input("我的显示名称", displayName);
        Button saveButton = primaryButton("保存设置");
        Button testButton = secondaryButton("测试连接");
        Button webRtcButton = secondaryButton(webRtcButtonText());

        saveButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            setStatus("设置已保存。");
        });
        testButton.setOnClickListener(v -> {
            saveSetup(serverInput, codeInput, nameInput);
            testRelayConnection(testButton);
        });
        webRtcButton.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("webRtcEnabled", false);
            prefs.edit().putBoolean("webRtcEnabled", next).apply();
            webRtcButton.setText(webRtcButtonText());
            setStatus(next
                    ? "实时模式已开启。请在两台手机都开启后再测试；如发生异常，关闭后会回到稳定模式。"
                    : "实时模式已关闭，当前使用稳定的截图协助模式。");
        });

        LinearLayout connectionCard = card("服务连接", "");
        connectionCard.addView(label("服务地址"));
        connectionCard.addView(serverInput);
        connectionCard.addView(label("家庭码"));
        connectionCard.addView(codeInput);
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
        currentPage = "elder";
        prefs.edit().putBoolean("elderPageVisible", true).apply();
        familyPolling = false;
        elderBindPolling = false;
        elderAnnotationPolling = true;
        elderScreenVisible = true;
        boolean assisting = prefs.getBoolean("assistActive", false);
        root = verticalRoot();
        root.addView(hero("长辈模式", assisting ? "屏幕正在共享给家属" : "需要帮助时，点“开始协助”"));
        String pendingAssistMessage = prefs.getString("pendingAssistMessage", "");
        if (!pendingAssistMessage.isEmpty()) {
            prefs.edit().remove("pendingAssistMessage").apply();
        }
        status = notice(pendingAssistMessage.isEmpty()
                ? bindingStatusText() + " 只有你主动开始后，家属才能看到屏幕。"
                : pendingAssistMessage);

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
        Button stopButton = dangerButton("停止协助");
        Button safetyButton = secondaryButton("更多设置");

        helpButton.setOnClickListener(v -> {
            setButtonBusy(helpButton, needsOverlayPermission() ? "正在打开权限..." : "正在打开屏幕授权...");
            handleElderPrimaryAction();
        });
        stopButton.setOnClickListener(v -> stopAssistance("已停止协助。"));
        safetyButton.setOnClickListener(v -> showSafetySettings());

        LinearLayout stepsCard = card(assisting ? "协助进行中" : elderCurrentStepTitle(), elderAssistHintText());
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
        status = notice("已绑定 " + Math.max(1, familyCount) + " 位家属。需要帮助时，再由你主动开始协助。");

        LinearLayout successCard = card("亲属绑定已完成", "绑定只需要做一次。现在不会自动共享屏幕，也不会自动让家属操作手机。");
        Button prepareButton = primaryButton("知道了，准备协助");
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
        status = notice("在这里管理敏感保护、画圈和远程操作权限。");

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

        LinearLayout summary = card("我们会处理哪些信息", "亲属绑定信息、协助会话状态、长辈主动授权后的屏幕画面、远程点击授权和操作审计。屏幕内容仅用于本次亲属协助。");
        root.addView(summary);
        root.addView(card("权限用途", "屏幕录制用于共享画面；显示在其他应用上层用于画圈提示；无障碍服务用于敏感页面保护和长辈授权后的远程点击；通知用于提醒长辈处理授权请求。"));
        root.addView(card("安全边界", "未绑定不能查看屏幕；长辈不主动发起不能查看屏幕；长辈未授权不能远程点击；协助结束后远程点击会自动关闭。"));
        root.addView(card("数据保留", "协助画面默认不保存；会话结束后停止传输。必要的安全审计记录仅用于保障账号与操作安全。"));
        root.addView(card("联系我们", "如需帮助或申请删除个人信息，请通过应用市场页面联系开发者。"));

        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showFamily() {
        currentPage = "family";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        if (!isBoundAs("family")) {
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
        lastFrameReceivedAtMs = 0;
        lastFrameUpdatedAt = "";
        root = verticalRoot();
        root.addView(hero("家属模式", "查看屏幕并协助长辈"));
        status = stableNotice("等待长辈开始协助");
        boolean useWebRtc = isWebRtcEnabled();
        View screenView = useWebRtc ? buildRtcView() : buildFrameView();

        familyControlRequestButton = secondaryButton("请求远程操作授权");
        familyEndButton = dangerButton("结束本次协助");
        familyChangeBindingButton = secondaryButton("绑定其他长辈");
        Button refreshButton = primaryButton("立即刷新");
        familyControlRequestButton.setOnClickListener(v -> requestRemoteControl(familyControlRequestButton));
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
        familyWaitingView = card("等待长辈开始", "长辈开始协助后，屏幕会自动显示。");
        familyWaitingTitle = (TextView) familyWaitingView.getChildAt(0);
        familyWaitingCaption = (TextView) familyWaitingView.getChildAt(1);
        familyScreenLabelView = screenLabel(useWebRtc ? "长辈实时屏幕 · 点画面可提示" : "长辈屏幕 · 点画面可提示");
        familyScreenSurface = screenSurface(screenView);
        root.addView(familyWaitingView);
        root.addView(familyScreenLabelView);
        root.addView(familyScreenSurface);
        familyRemotePanel = buildRemoteControlPanel();
        setFamilySessionActive(false);
        root.addView(familyControlRequestButton);
        root.addView(familyRemotePanel);
        root.addView(familyEndButton);
        root.addView(familyChangeBindingButton);
        if (!useWebRtc) {
            root.addView(refreshButton);
        }
        root.addView(bottomNav("family"));
        setContentView(scroll(root));
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

    private View buildRtcView() {
        rtcView = new SurfaceViewRenderer(this);
        rtcView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        rtcView.setMirror(false);
        rtcView.setEnableHardwareScaler(true);
        rtcView.setBackgroundColor(0xFF0F172A);
        rtcView.setMinimumHeight(dp(500));
        rtcView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(540)
        ));
        rtcView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                screenTouchStartX = event.getX();
                screenTouchStartY = event.getY();
                screenTouchStartAtMs = System.currentTimeMillis();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!rtcVideoReady) {
                    setStatus("实时画面正在连接，请等画面出现后再点。");
                    return true;
                }
                if (familyControlAllowed) {
                    handleRemoteTouchOnView(view, event.getX(), event.getY());
                } else {
                    float[] point = normalizedRtcPoint(view, event.getX(), event.getY());
                    if (point == null) {
                        setStatus("请点击屏幕画面内的内容区域");
                    } else {
                        sendAnnotation(point[0], point[1]);
                    }
                }
                return true;
            }
            return true;
        });
        return rtcView;
    }

    private LinearLayout buildRemoteControlPanel() {
        LinearLayout panel = card("远程操作", "长辈同意后可点击或滑动；快捷按钮用于返回、主页和常用滑动。");

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

    private void showFamilyBind() {
        currentPage = "familyBind";
        familyPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(hero("绑定长辈", "输入长辈手机上显示的 6 位码"));
        status = notice("输入长辈手机上的绑定码，完成后即可接收求助。");

        EditText nameInput = input("我的称呼，例如 女儿", displayName);
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
                    setStatus("发起协助失败：" + friendlyError(e) + "。请检查网络后重试。");
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
                        .put("familyName", displayName)
                        .put("deviceId", deviceId);
                JSONObject result = postJsonWithRelayFallback("/api/bind", payload);
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
        if (rtcView == null && familyScreenSurface != null) {
            familyScreenSurface.removeAllViews();
            familyScreenSurface.addView(buildRtcView(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            ));
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
                if (rtcView != null) {
                    track.addSink(rtcView);
                }
            }

            @Override
            public void onLocalVideoFrame(org.webrtc.VideoFrame frame) {
            }
        });
        if (rtcView != null) {
            rtcView.init(familyRtcClient.eglContext(), new RendererCommon.RendererEvents() {
                @Override
                public void onFirstFrameRendered() {
                    main.post(() -> {
                        if (!rtcSessionId.equals(familyLastSessionId) || !familyLastActive || rtcView == null) {
                            return;
                        }
                        rtcVideoReady = true;
                        lastFrameReceivedAtMs = System.currentTimeMillis();
                        showFamilyMedia(rtcView, "实时画面已连接");
                    });
                }

                @Override
                public void onFrameResolutionChanged(int width, int height, int rotation) {
                    main.post(() -> {
                        rtcFrameWidth = width;
                        rtcFrameHeight = height;
                        rtcFrameRotation = rotation;
                    });
                }
            });
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
        rtcTrackAttached = false;
        rtcFrameWidth = 0;
        rtcFrameHeight = 0;
        rtcFrameRotation = 0;
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
        int value = visible ? View.VISIBLE : View.GONE;
        if (familyControlRequestButton != null) familyControlRequestButton.setVisibility(value);
        if (familyEndButton != null) familyEndButton.setVisibility(value);
        if (familyRemotePanel != null) familyRemotePanel.setVisibility(value);
    }

    private void setFamilySessionActive(boolean active) {
        if (active) {
            updateFamilyWaiting("正在连接屏幕", "画面出现后即可开始协助。");
            if (!familyMediaReady && familyScreenSurface != null) {
                resizeFamilyScreenSurface(dp(2));
            }
        } else {
            familyMediaReady = false;
            updateFamilyWaiting("等待长辈开始", "长辈开始协助后，屏幕会自动显示。");
            updateFamilyControlButton(false, false);
            resizeFamilyScreenSurface(dp(500));
        }
        if (familyWaitingView != null) familyWaitingView.setVisibility(active && familyMediaReady ? View.GONE : View.VISIBLE);
        if (familyScreenLabelView != null) familyScreenLabelView.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyScreenSurface != null) familyScreenSurface.setVisibility(active ? View.VISIBLE : View.GONE);
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
        familyScreenSurface.removeAllViews();
        resizeFamilyScreenSurface(dp(500));
        if (isWebRtcEnabled() && mediaView == frameView && rtcView != null) {
            familyScreenSurface.addView(rtcView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2)
            ));
            familyScreenSurface.addView(frameView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
            ));
        } else {
            familyScreenSurface.addView(mediaView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            ));
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
            params.height = height;
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
                if (!active) {
                    main.post(() -> {
                        familyLastSessionId = "";
                        stopFamilyWebRtc();
                        clearFamilyScreen();
                        setFamilySessionActive(false);
                        setStatus(wasActive
                                ? "本次协助已结束。可以等待长辈再次发起，或绑定其他长辈。"
                                : "等待长辈开始协助");
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
                    updateFamilyControlButton(controlRequested, familyControlAllowed);
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
                    updateFamilyControlButton(true, false);
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
        if (familyControlRequestButton == null) {
            return;
        }
        familyControlRequestButton.setTag(null);
        if (allowed) {
            familyControlRequestButton.setText("远程操作已授权");
            familyControlRequestButton.setEnabled(false);
            familyControlRequestButton.setAlpha(0.72f);
        } else if (requested) {
            familyControlRequestButton.setText("等待长辈授权...");
            familyControlRequestButton.setEnabled(false);
            familyControlRequestButton.setAlpha(0.72f);
        } else {
            familyControlRequestButton.setText("请求远程操作授权");
            familyControlRequestButton.setEnabled(true);
            familyControlRequestButton.setAlpha(1f);
        }
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

    private void handleRemoteTouchOnView(View view, float endX, float endY) {
        float distance = (float) Math.hypot(endX - screenTouchStartX, endY - screenTouchStartY);
        float[] start = normalizedRtcPoint(view, screenTouchStartX, screenTouchStartY);
        float[] end = normalizedRtcPoint(view, endX, endY);
        if (start == null || end == null) {
            setStatus("请在屏幕画面内完成操作");
            return;
        }
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

    private float[] normalizedViewPoint(View view, float touchX, float touchY) {
        if (view == null) {
            return new float[]{0.5f, 0.5f};
        }
        int width = Math.max(1, view.getWidth());
        int height = Math.max(1, view.getHeight());
        float x = touchX / width;
        float y = touchY / height;
        return new float[]{
                Math.max(0f, Math.min(1f, x)),
                Math.max(0f, Math.min(1f, y))
        };
    }

    private float[] normalizedRtcPoint(View view, float touchX, float touchY) {
        if (view == null || rtcFrameWidth <= 0 || rtcFrameHeight <= 0) {
            return normalizedViewPoint(view, touchX, touchY);
        }
        int sourceWidth = rtcFrameWidth;
        int sourceHeight = rtcFrameHeight;
        if (Math.abs(rtcFrameRotation) % 180 != 0) {
            int swapped = sourceWidth;
            sourceWidth = sourceHeight;
            sourceHeight = swapped;
        }
        int viewWidth = Math.max(1, view.getWidth());
        int viewHeight = Math.max(1, view.getHeight());
        float scale = Math.min(viewWidth / (float) sourceWidth, viewHeight / (float) sourceHeight);
        float displayedWidth = sourceWidth * scale;
        float displayedHeight = sourceHeight * scale;
        float left = (viewWidth - displayedWidth) / 2f;
        float top = (viewHeight - displayedHeight) / 2f;
        if (touchX < left || touchX > left + displayedWidth
                || touchY < top || touchY > top + displayedHeight) {
            return null;
        }
        return new float[]{
                (touchX - left) / Math.max(1f, displayedWidth),
                (touchY - top) / Math.max(1f, displayedHeight)
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
                boolean remoteActive = family.optBoolean("active", false);
                if (elderUiAssisting && !remoteActive) {
                    prefs.edit()
                            .putString("pendingAssistMessage", "家属已结束本次协助。需要帮助时可以再次发起。")
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
            main.post(() -> stopAssistance("家属长时间没有连接。本次协助已结束，需要时请重新点“开始协助”。"));
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
                .apply();
        AssistNotifier.cancelAssistEndedNotification(this);
        stopCaptureServices();
        markAssistanceStoppedLocal();
        if ("elder".equals(currentPage)) {
            showElder();
        }
        new AlertDialog.Builder(this)
                .setTitle("本次协助已结束")
                .setMessage("家属已结束本次协助。需要帮助时，你可以再次发起。")
                .setPositiveButton("知道了", (dialog, which) -> assistEndPromptShowing = false)
                .setOnCancelListener(dialog -> assistEndPromptShowing = false)
                .show();
    }

    private void handleRemoteControlRequest(String updatedAt) {
        if (!appInForeground) {
            prefs.edit()
                    .putString("pendingControlRequestAt", updatedAt)
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
                        : "远程点击需要先开启“亲情帮帮”辅助服务。点下面按钮后会尽量直接打开亲情帮帮的开关页。")
                .setPositiveButton(accessibilityReady ? "允许本次协助" : "去开启辅助服务", (dialog, which) -> {
                    remotePromptShowing = false;
                    if (accessibilityReady) {
                        markControlRequestHandled(updatedAt);
                        allowRemoteControl(true);
                        showElder();
                    } else {
                        prefs.edit().putString("pendingControlRequestAt", updatedAt).apply();
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
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_CONTROL_REQUEST);
        }
        prefs.edit()
                .putString("handledControlRequestAt", updatedAt)
                .remove("pendingControlRequestAt")
                .apply();
    }

    private void saveSetup(EditText serverInput, EditText codeInput, EditText nameInput) {
        baseUrl = sanitizeRelayUrl(serverInput.getText().toString());
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

    private void testRelayConnection(Button sourceButton) {
        setButtonBusy(sourceButton, "测试中...");
        setStatus("正在测试连接...");
        statusIo.execute(() -> {
            try {
                NetworkClient.getJson(baseUrl, "/health");
                main.post(() -> setStatus("连接服务正常"));
            } catch (Exception e) {
                main.post(() -> setStatus("连接测试失败：" + friendlyError(e)));
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
            setStatus("正在重新发起协助...");
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
                prefs.edit()
                        .putBoolean("remoteControlAllowed", false)
                        .remove("handledControlRequestAt")
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
        return isWebRtcEnabled() ? "实时模式(WebRTC)：已开启" : "实时模式(WebRTC)：关闭";
    }

    private String elderPrimaryButtonText() {
        if (needsOverlayPermission()) {
            return "允许画圈提示";
        }
        return "开始协助";
    }

    private String elderCurrentStepTitle() {
        if (needsOverlayPermission()) {
            return "先完成一次权限设置";
        }
        return "准备开始协助";
    }

    private String elderAssistHintText() {
        if (prefs.getBoolean("assistActive", false)) {
            return "请切到需要帮助的应用。家属可以查看屏幕并给出提示；结束时回到这里点“停止协助”。";
        }
        if (needsOverlayPermission()) {
            return "只需要设置一次。点蓝色按钮后，在系统页允许“显示在其他应用上层”，再按返回键回来。";
        }
        if (!isAccessibilityServiceEnabled()) {
            return "点“开始协助”，再在系统弹窗中点“立即开始”。";
        }
        return "点“开始协助”，确认屏幕共享后即可寻求帮助。";
    }

    private String bindingStatusText() {
        if (authToken.isEmpty()) {
            return "绑定状态：未绑定。";
        }
        String role = "elder".equals(memberRole) ? "长辈" : "家属";
        return "绑定状态：已绑定为" + role + "。";
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
        layout.setPadding(dp(14), dp(8), dp(14), dp(16));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(COLOR_BG);
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
        nav.setBackground(rounded(0xFFFFFFFF, dp(8), COLOR_LINE));
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
        item.setBackground(rounded(selected ? 0xFFEFF6FF : 0x00FFFFFF, dp(8), 0x00FFFFFF));

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
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(14), dp(12), dp(14), dp(12));
        layout.setBackground(gradientHero());
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(10));
        layout.setLayoutParams(params);

        TextView logo = new TextView(this);
        logo.setText("亲");
        logo.setGravity(Gravity.CENTER);
        logo.setTextSize(20);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(COLOR_BLUE_DARK);
        logo.setBackground(rounded(0xFFFFFFFF, dp(8), 0x00FFFFFF));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        logoParams.setMargins(0, 0, dp(12), 0);
        layout.addView(logo, logoParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle(heading);
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(1));
        copy.addView(title);

        TextView body = caption(subheading);
        body.setTextColor(0xEFFFFFFF);
        body.setPadding(0, 0, 0, 0);
        copy.addView(body);
        layout.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return layout;
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

    private LinearLayout screenSurface(View child) {
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(3), dp(3), dp(3), dp(3));
        surface.setBackground(rounded(0xFF0F172A, dp(8), COLOR_LINE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(500)
        );
        params.setMargins(0, 0, 0, dp(10));
        surface.setLayoutParams(params);
        surface.addView(child, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
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
