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
import android.text.SpannableString;
import android.text.InputType;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.VideoFrame;
import org.webrtc.VideoTrack;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 2001;
    private static final int REQUEST_NOTIFICATIONS = 2002;
    private static final int REQUEST_FAMILY_NOTIFICATIONS = 2003;
    private static final String PREFS = "family-assist";
    private static final String DEFAULT_RELAY_URL = "https://47.238.240.30";
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
    private String accountMembershipsJson;
    private String authToken;
    private String memberRole;
    private String selectedAppRole;
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
    private ImageButton familyMoreButton;
    private LinearLayout familyActionBar;
    private TextView familyPageTitleView;
    private View familyBottomNav;
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
    private Button familyWaitingActionButton;
    private Button familyChangeBindingButton;
    private volatile boolean rtcVideoReady;
    private boolean rtcTrackAttached;
    private boolean familyMediaReady;
    private boolean assistEndPromptShowing;
    private boolean familyLastActive;
    private boolean elderInviteBoundShown;
    private int elderInviteBaselineFamilyCount = -1;
    private Button elderAddFamilyButton;
    private String familyLastSessionId = "";
    private String currentPage = "home";
    private String pendingTargetHelperRef = "";
    private String pendingTargetHelperName = "";
    private String pendingHelpInvitationId = "";
    private String pendingFamilyAssistRequestId = "";
    private long pendingFamilyAssistRequestExpiresAt;
    private Button selectedFamilyHelpButton;
    private boolean familyHelpInvitePromptShowing;
    private String respondingFamilyHelpInvitationId = "";
    private boolean elderFamilyAssistPromptShowing;
    private String respondingElderFamilyAssistRequestId = "";
    private boolean captureRequestInProgress;
    private boolean resumeCaptureAfterNotificationPermission;
    private boolean resumeCaptureAfterNotificationSettings;
    private boolean inviteInProgress;
    private boolean bindInProgress;
    private volatile boolean accountRequestInProgress;
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
            maybeShowFamilyHelpInvitation();
            maybeShowElderFamilyAssistRequest();
            main.postDelayed(this, 700);
        }
    };
    private final Runnable selectedHelpInvitePollLoop = new Runnable() {
        @Override
        public void run() {
            if (pendingHelpInvitationId.isEmpty()) return;
            pollSelectedHelpInvitation();
            main.postDelayed(this, 1000);
        }
    };

    private static final int COLOR_BG = 0xFFFFF8F9;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF2B2528;
    private static final int COLOR_MUTED = 0xFF70666A;
    private static final int COLOR_LINE = 0xFFE9DDE0;
    private static final int COLOR_BRAND = 0xFFF0526E;
    private static final int COLOR_BLUE = 0xFFD93E5F;
    private static final int COLOR_BLUE_DARK = 0xFFB72E4D;
    private static final int COLOR_FAMILY = 0xFFD93E5F;
    private static final int COLOR_CONTROL = 0xFF9F3650;
    private static final int COLOR_SUCCESS = 0xFF278A65;
    private static final int COLOR_WARNING = 0xFFE58A28;
    private static final int COLOR_RED = 0xFFD83F4B;
    private static final int COLOR_WARM = 0xFFFFEDF2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrl = migrateRelayUrl(prefs.getString("baseUrl", DEFAULT_RELAY_URL));
        pairCode = prefs.getString("pairCode", "");
        displayName = prefs.getString("displayName", "妈妈");
        accountToken = prefs.getString("accountToken", "");
        accountPhone = prefs.getString("accountPhone", "");
        accountMembershipsJson = prefs.getString("accountMemberships", "[]");
        authToken = prefs.getString("authToken", "");
        memberRole = prefs.getString("memberRole", "");
        selectedAppRole = prefs.getString("selectedAppRole", "");
        if (accountToken.isEmpty()) {
            selectedAppRole = "";
            prefs.edit().remove("selectedAppRole").apply();
        }
        deviceId = prefs.getString("deviceId", "");
        if (deviceId.isEmpty()) {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        seedDefaultSafetyPrefs();
        createControlNotificationChannel();
        boolean openFamilyHelpInvite = getIntent().getBooleanExtra("openFamilyHelpInvite", false);
        if (isLoggedIn()) {
            refreshAccountBeforeRouting(openFamilyHelpInvite);
        } else {
            showSetup();
        }
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
        main.removeCallbacks(selectedHelpInvitePollLoop);
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
        maybeShowElderFamilyAssistRequest();
        main.postDelayed(assistEndedUiLoop, 700);
        String setupControl = prefs.getString("controlSetupRequestAt", "");
        if (!setupControl.isEmpty()) {
            prefs.edit().remove("controlSetupRequestAt").commit();
            if (isAccessibilityServiceEnabled()) {
                markControlRequestHandled(setupControl);
                allowRemoteControl(true);
                main.postDelayed(() -> {
                    if ("elder".equals(currentPage)) showElder();
                    setStatus("已允许家属在本次协助中远程操作。");
                }, 250);
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
        if (refreshElderOnResume) {
            refreshElderOnResume = false;
            if (!pendingHelpInvitationId.isEmpty() && !needsOverlayPermission()) {
                main.postDelayed(() -> {
                    showElder();
                    requestHelpAndCapture();
                }, 250);
            } else if (elderScreenVisible) {
                main.postDelayed(this::showElder, 200);
            }
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
        } else if ("login".equals(currentPage)) {
            super.onBackPressed();
        } else if ("register".equals(currentPage) || "forgotPassword".equals(currentPage)) {
            showLogin("auth");
        } else if ("settings".equals(currentPage) || "privacy".equals(currentPage) || "relatives".equals(currentPage)) {
            showProfile();
        } else if ("elderFamily".equals(currentPage)) {
            showElder();
        } else if ("familyBindPending".equals(currentPage)) {
            showFamilyBind();
        } else if ("safety".equals(currentPage)) {
            showElder();
        } else {
            showSetup();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("openFamilyHelpInvite", false)) {
            showFamily();
            main.postDelayed(this::maybeShowFamilyHelpInvitation, 250);
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
            String cancelledFamilyRequestId = pendingHelpInvitationId;
            pendingTargetHelperRef = "";
            pendingTargetHelperName = "";
            pendingHelpInvitationId = "";
            selectedFamilyHelpButton = null;
            prefs.edit().putBoolean("assistActive", false).apply();
            if (cancelledFamilyRequestId != null && !cancelledFamilyRequestId.isEmpty()) {
                withdrawAcceptedFamilyAssistRequest(cancelledFamilyRequestId);
            } else {
                endHelpRequest();
            }
            showElder();
            setStatus("你取消了屏幕共享授权。");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FAMILY_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startFamilyInviteMonitor();
                setStatus("家人求助提醒已开启。 ");
            } else {
                setStatus("通知未开启，家人求助时可能无法及时提醒你。可在系统通知设置中重新开启。 ");
            }
            return;
        }
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
        if (!isLoggedIn()) {
            showLogin("auth");
            return;
        }
        if (hasSelectedAppRole()) {
            showRoleHome();
            return;
        }
        currentPage = "home";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();

        root.addView(appBrandHeader("选择你的使用身份"));
        status = notice(bindingStatusText());
        status.setVisibility(View.GONE);

        Button elderButton = primaryButton("我是长辈");
        elderButton.setTextSize(23);
        Button familyButton = familyPrimaryButton("我是家属");
        familyButton.setTextSize(22);
        elderButton.setOnClickListener(v -> confirmInitialRole("elder"));
        familyButton.setOnClickListener(v -> confirmInitialRole("family"));

        LinearLayout elderCard = actionCard("我是长辈", "需要帮助时，让家人看屏幕并协助", COLOR_WARM);
        elderCard.addView(elderButton);
        root.addView(elderCard);

        LinearLayout familyCard = actionCard("我是家属", "接收求助，查看屏幕并给长辈提示", 0xFFFFF1F4);
        familyCard.addView(familyButton);
        root.addView(familyCard);

        root.addView(status);
        setContentView(scroll(root));
    }

    private void refreshAccountBeforeRouting(boolean openFamilyHelpInvite) {
        currentPage = "authLoading";
        root = verticalRoot();
        root.addView(appBrandHeader("正在打开亲情帮帮"));
        status = notice("正在同步账号信息...");
        root.addView(status);
        setContentView(scroll(root));
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl,
                        "/api/account/me?accountToken=" + encoded(accountToken));
                JSONObject user = result.optJSONObject("user");
                String serverRole = user == null ? "" : user.optString("appRole", "");
                if (!"elder".equals(serverRole) && !"family".equals(serverRole) && hasSelectedAppRole()) {
                    result = NetworkClient.postJson(baseUrl, "/api/account/role", new JSONObject()
                            .put("accountToken", accountToken)
                            .put("appRole", selectedAppRole));
                }
                applyAccountSnapshot(result);
                main.post(() -> {
                    if (openFamilyHelpInvite && "family".equals(selectedAppRole)) {
                        showFamily();
                        main.postDelayed(this::maybeShowFamilyHelpInvitation, 250);
                    } else {
                        showSetup();
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isAuthFailure(error)) {
                        logoutLocal();
                        setStatus("登录已过期，请重新登录。");
                    } else {
                        showLogin("auth");
                        setStatus("暂时无法连接服务，请检查网络后重试。");
                    }
                });
            }
        });
    }

    private void applyAccountSnapshot(JSONObject result) {
        JSONObject user = result.optJSONObject("user");
        if (user != null) {
            accountPhone = user.optString("phone", accountPhone);
            displayName = user.optString("name", displayName);
            selectedAppRole = user.optString("appRole", "");
        }
        if (!"elder".equals(selectedAppRole) && !"family".equals(selectedAppRole)) {
            selectedAppRole = "";
        }
        JSONArray memberships = result.optJSONArray("memberships");
        accountMembershipsJson = memberships == null ? "[]" : memberships.toString();
        clearLocalMembershipForAccountChange();
        prefs.edit()
                .putString("accountPhone", accountPhone)
                .putString("displayName", displayName)
                .putString("selectedAppRole", selectedAppRole)
                .putString("accountMemberships", accountMembershipsJson)
                .apply();
        restoreMembershipForRole(selectedAppRole);
    }

    private void confirmInitialRole(String role) {
        boolean elder = "elder".equals(role);
        new AlertDialog.Builder(this)
                .setTitle(elder ? "确认使用长辈模式？" : "确认使用家属模式？")
                .setMessage(elder
                        ? "确认后，此账号只显示长辈相关功能。换手机登录仍会保持该身份，以后可在“我的 > 使用身份”中切换。"
                        : "确认后，此账号只显示家属相关功能。换手机登录仍会保持该身份，以后可在“我的 > 使用身份”中切换。")
                .setPositiveButton("确认", (dialog, which) -> updateAccountRole(role, true))
                .setNegativeButton("再想想", null)
                .show();
    }

    private boolean hasSelectedAppRole() {
        return "elder".equals(selectedAppRole) || "family".equals(selectedAppRole);
    }

    private void showRoleHome() {
        if ("elder".equals(selectedAppRole)) {
            showElder();
        } else if ("family".equals(selectedAppRole)) {
            showFamily();
        }
    }

    private void applySelectedRole(String role) {
        selectedAppRole = role;
        authToken = "";
        memberRole = "";
        pairCode = "";
        prefs.edit()
                .putString("selectedAppRole", role)
                .remove("authToken")
                .remove("memberRole")
                .remove("pairCode")
                .remove(PREF_ASSIST_SESSION_ID)
                .apply();
        restoreMembershipForRole(role);
        showRoleHome();
    }

    private void showProfile() {
        if (!isLoggedIn()) {
            showLogin("auth");
            return;
        }
        currentPage = "profile";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        elderScreenVisible = false;
        root = verticalRoot();
        root.addView(compactPageTitle("我的"));
        status = notice("");
        status.setVisibility(View.GONE);

        TextView accountButton = settingsRow("退出登录", "退出当前设备，不会删除亲属关系");
        boolean elderMode = "elder".equals(selectedAppRole);
        TextView relativesButton = settingsRow("家人管理",
                elderMode ? "查看和邀请可信家人" : "查看和管理已绑定长辈");
        TextView roleButton = settingsRow("使用身份", "当前为" + roleDisplayName(selectedAppRole) + "，身份跟随账号保存");
        TextView safetyButton = settingsRow("安全与权限", "管理画圈、敏感保护和远程操作");
        TextView privacyButton = settingsRow("隐私政策", "了解信息如何被使用和保护");
        TextView deleteButton = settingsRow("注销账号", "永久删除账号和相关绑定");

        accountButton.setOnClickListener(v -> confirmLogout());
        relativesButton.setOnClickListener(v -> {
            if (elderMode) showElderFamilyMembers();
            else showRelativesManagement();
        });
        roleButton.setOnClickListener(v -> showRoleSwitchGuide());
        safetyButton.setOnClickListener(v -> {
            if (isBoundAs("elder")) {
                showSafetySettings();
            } else {
                setStatus("安全与权限主要用于长辈手机。请在长辈手机上打开。");
            }
        });
        privacyButton.setOnClickListener(v -> showPrivacyPolicy());
        deleteButton.setOnClickListener(v -> showDeleteAccount());

        if (isLoggedIn()) {
            root.addView(profileSummary());
        }

        LinearLayout familySettings = card("家庭与身份", "");
        familySettings.addView(roleButton);
        familySettings.addView(relativesButton);
        root.addView(familySettings);

        LinearLayout privacySettings = card("安全与隐私", "");
        if ("elder".equals(selectedAppRole)) privacySettings.addView(safetyButton);
        privacySettings.addView(privacyButton);
        root.addView(privacySettings);

        if (isLoggedIn()) {
            LinearLayout accountSettings = card("账号", "");
            accountSettings.addView(accountButton);
            accountSettings.addView(deleteButton);
            root.addView(accountSettings);
        }

        LinearLayout aboutCard = card("亲情帮帮", "版本 " + appVersionText());
        root.addView(aboutCard);
        root.addView(status);
        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private String roleDisplayName(String role) {
        return "elder".equals(role) ? "长辈模式" : "family".equals(role) ? "家属模式" : "未选择";
    }

    private void showRoleSwitchGuide() {
        if (prefs.getBoolean("assistActive", false) || familyLastActive) {
            setStatus("请先结束当前协助，再切换使用身份。 ");
            return;
        }
        String nextRole = "elder".equals(selectedAppRole) ? "family" : "elder";
        String nextName = roleDisplayName(nextRole);
        new AlertDialog.Builder(this)
                .setTitle("切换到" + nextName)
                .setMessage("切换后，首页、底部菜单和功能会改为" + nextName + "。亲属绑定不会被删除，需要时可以再次从这里切回。")
                .setPositiveButton("继续切换", (dialog, which) -> refreshAccountAndSwitchRole(nextRole))
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAccountAndSwitchRole(String role) {
        updateAccountRole(role, false);
    }

    private void updateAccountRole(String role, boolean initialSelection) {
        if (!isLoggedIn()) {
            showLogin("auth");
            return;
        }
        setStatus(initialSelection ? "正在保存身份..." : "正在切换身份...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/account/role", new JSONObject()
                        .put("accountToken", accountToken)
                        .put("appRole", role));
                JSONArray memberships = result.optJSONArray("memberships");
                accountMembershipsJson = memberships == null ? "[]" : memberships.toString();
                prefs.edit().putString("accountMemberships", accountMembershipsJson).apply();
                main.post(() -> applySelectedRole(role));
            } catch (Exception e) {
                main.post(() -> setStatus(friendlyRoleError(e, initialSelection)));
            }
        });
    }

    private String friendlyRoleError(Exception error, boolean initialSelection) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("assist session is active") || message.contains("HTTP 409")) {
            return "当前协助尚未结束，请结束后再切换身份。";
        }
        return initialSelection ? "身份保存失败，请检查网络后重试。" : "身份切换失败，请检查网络后重试。";
    }

    private void showLogin(String afterRole) {
        currentPage = "login";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(appBrandHeader("让家人远程看屏幕并协助操作"));
        root.addView(compactPageTitle("登录"));
        status = notice("");
        status.setVisibility(View.GONE);

        EditText phoneInput = input("手机号", accountPhone);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        EditText passwordInput = input("密码", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Button loginButton = primaryButton("登录");
        Button forgotButton = textButton("忘记密码");
        Button registerButton = textButton("还没有账号？立即注册");
        clearStatusOnFocus(phoneInput, passwordInput);

        loginButton.setOnClickListener(v -> loginAccount(
                phoneInput.getText().toString().trim(),
                passwordInput.getText().toString(),
                displayName,
                afterRole,
                false,
                loginButton
        ));
        passwordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                loginButton.performClick();
                return true;
            }
            return false;
        });
        registerButton.setOnClickListener(v -> showRegister(afterRole));
        forgotButton.setOnClickListener(v -> showForgotPassword(afterRole, phoneInput.getText().toString().trim()));

        LinearLayout loginCard = card("", "");
        loginCard.addView(label("手机号"));
        loginCard.addView(phoneInput);
        loginCard.addView(label("密码"));
        loginCard.addView(passwordInput);
        loginCard.addView(forgotButton);
        loginCard.addView(status);
        loginCard.addView(loginButton);
        loginCard.addView(registerButton);
        root.addView(loginCard);
        setContentView(scroll(root));
    }

    private void showRegister(String afterRole) {
        currentPage = "register";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("注册账号", () -> showLogin("auth")));
        status = notice("");
        status.setVisibility(View.GONE);

        EditText phoneInput = input("手机号", accountPhone);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        EditText passwordInput = input("设置密码", "");
        EditText confirmInput = input("再次输入密码", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText nameInput = input("例如：妈妈、女儿", "");
        Button registerButton = primaryButton("注册并登录");
        Button loginButton = textButton("已有账号？登录");
        CheckBox privacyCheck = new CheckBox(this);
        privacyCheck.setText("我已阅读并同意隐私政策");
        privacyCheck.setTextSize(14);
        privacyCheck.setTextColor(COLOR_TEXT);
        privacyCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_BLUE));
        privacyCheck.setPadding(0, dp(6), 0, 0);
        Button privacyButton = textButton("查看隐私政策");
        privacyButton.setTextSize(14);
        privacyButton.setOnClickListener(v -> showRegistrationPrivacyDialog());
        clearStatusOnFocus(phoneInput, passwordInput, confirmInput, nameInput);

        registerButton.setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            if (password.length() < 8) {
                setStatus("密码至少需要 8 位。");
                return;
            }
            if (!password.equals(confirmInput.getText().toString())) {
                setStatus("两次输入的密码不一致。");
                return;
            }
            if (nameInput.getText().toString().trim().isEmpty()) {
                setStatus("请输入家人容易认出的称呼。");
                return;
            }
            if (!privacyCheck.isChecked()) {
                setStatus("请先阅读并同意隐私政策。");
                return;
            }
            loginAccount(
                    phoneInput.getText().toString().trim(),
                    password,
                    nameInput.getText().toString().trim(),
                    afterRole,
                    true,
                    registerButton
            );
        });
        loginButton.setOnClickListener(v -> showLogin(afterRole));

        LinearLayout registerCard = card("创建家庭账号", "注册后，可以添加长辈或家属。");
        registerCard.addView(label("手机号"));
        registerCard.addView(phoneInput);
        registerCard.addView(label("密码"));
        registerCard.addView(passwordInput);
        registerCard.addView(label("确认密码"));
        registerCard.addView(confirmInput);
        registerCard.addView(label("称呼"));
        registerCard.addView(nameInput);
        registerCard.addView(caption("请设置 8–64 位密码，建议包含字母和数字。"));
        registerCard.addView(privacyCheck);
        registerCard.addView(privacyButton);
        registerCard.addView(status);
        registerCard.addView(registerButton);
        registerCard.addView(loginButton);
        root.addView(registerCard);
        setContentView(scroll(root));
    }

    private void showForgotPassword(String afterRole, String initialPhone) {
        currentPage = "forgotPassword";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("找回密码", () -> showLogin(afterRole)));
        status = notice("");
        status.setVisibility(View.GONE);

        EditText phoneInput = input("注册手机号", initialPhone == null ? "" : initialPhone);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText codeInput = input("6 位验证码", "");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText passwordInput = input("新密码", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirmInput = input("再次输入新密码", "");
        confirmInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button codeButton = secondaryButton("获取验证码");
        Button resetButton = primaryButton("设置新密码");
        clearStatusOnFocus(phoneInput, codeInput, passwordInput, confirmInput);

        codeButton.setOnClickListener(v -> requestPasswordResetCode(phoneInput.getText().toString().trim(), codeButton));
        resetButton.setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            if (!password.equals(confirmInput.getText().toString())) {
                setStatus("两次输入的新密码不一致。");
                return;
            }
            confirmPasswordReset(
                    phoneInput.getText().toString().trim(),
                    codeInput.getText().toString().trim(),
                    password,
                    afterRole,
                    resetButton
            );
        });

        LinearLayout form = card("重新设置密码", "验证码会发送到注册手机号，10 分钟内有效。");
        form.addView(label("手机号"));
        form.addView(phoneInput);
        form.addView(codeButton);
        form.addView(label("验证码"));
        form.addView(codeInput);
        form.addView(label("新密码"));
        form.addView(passwordInput);
        form.addView(label("确认新密码"));
        form.addView(confirmInput);
        form.addView(status);
        form.addView(resetButton);
        root.addView(form);
        setContentView(scroll(root));
    }

    private void requestPasswordResetCode(String phone, Button sourceButton) {
        if (!phone.matches("^\\+?[0-9]{6,18}$")) {
            setStatus("请输入正确的注册手机号。");
            return;
        }
        setButtonBusy(sourceButton, "正在发送...");
        setStatus("");
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/account/password/reset/request", new JSONObject().put("phone", phone));
                main.post(() -> {
                    setStatus("验证码已发送，请查看手机短信。");
                    startCodeCountdown(sourceButton, 60);
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus(friendlyError(e));
                });
            }
        });
    }

    private void startCodeCountdown(Button button, int seconds) {
        button.setTag(null);
        button.setEnabled(false);
        button.setAlpha(0.7f);
        final int[] remaining = {seconds};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (remaining[0] <= 0 || !"forgotPassword".equals(currentPage)) {
                    button.setText("重新获取验证码");
                    button.setEnabled(true);
                    button.setAlpha(1f);
                    return;
                }
                button.setText(remaining[0] + " 秒后可重新获取");
                remaining[0] -= 1;
                main.postDelayed(this, 1000);
            }
        };
        tick.run();
    }

    private void confirmPasswordReset(String phone, String code, String password, String afterRole, Button sourceButton) {
        if (!phone.matches("^\\+?[0-9]{6,18}$")) {
            setStatus("请输入正确的注册手机号。");
            return;
        }
        if (!code.matches("^[0-9]{6}$")) {
            setStatus("请输入短信中的 6 位验证码。");
            return;
        }
        if (password.length() < 8 || password.length() > 64) {
            setStatus("新密码需要 8–64 位。");
            return;
        }
        setButtonBusy(sourceButton, "正在设置...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/account/password/reset/confirm", new JSONObject()
                        .put("phone", phone)
                        .put("code", code)
                        .put("password", password));
                saveAccountResult(result, phone, displayName);
                main.post(() -> {
                    showSetup();
                    setStatus("密码已更新，你已安全登录。");
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus(friendlyError(e));
                });
            }
        });
    }

    private void showRegistrationPrivacyDialog() {
        TextView content = body("我们会处理账号、亲属绑定和安全审计信息。\n\n"
                + "只有长辈主动发起并确认系统授权后，已绑定家属才能查看本次屏幕。\n\n"
                + "远程操作需要长辈每次明确同意，协助结束后授权自动失效。\n\n"
                + "完整隐私政策可在“我的”页查看。");
        content.setTextColor(COLOR_TEXT);
        content.setPadding(dp(8), 0, dp(8), 0);
        new AlertDialog.Builder(this)
                .setTitle("隐私政策")
                .setView(content)
                .setPositiveButton("我知道了", null)
                .show();
    }

    private void showDeleteAccount() {
        if (!isLoggedIn()) {
            showLogin("auth");
            return;
        }
        currentPage = "deleteAccount";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(pageHeader("注销账号", this::showProfile));
        status = notice("");
        status.setVisibility(View.GONE);

        EditText passwordInput = input("请输入当前密码", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("我已了解账号、亲属绑定和协助记录将被永久删除");
        confirm.setTextColor(COLOR_TEXT);
        confirm.setTextSize(15);
        confirm.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_RED));
        Button deleteButton = dangerButton("永久注销账号");
        deleteButton.setOnClickListener(v -> {
            if (!confirm.isChecked()) {
                setStatus("请先确认你已了解注销影响。");
                return;
            }
            if (passwordInput.getText().toString().isEmpty()) {
                setStatus("请输入当前密码确认身份。");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("确认永久注销？")
                    .setMessage("注销后无法恢复。长辈账号创建的家庭关系也会同时删除。")
                    .setPositiveButton("确认注销", (dialog, which) -> deleteAccount(passwordInput.getText().toString(), deleteButton))
                    .setNegativeButton("取消", null)
                    .show();
        });

        LinearLayout warning = card("注销后会发生什么", "账号资料、亲属绑定和服务端协助记录将被删除；当前协助也会立即结束。此操作无法撤销。");
        warning.addView(label("当前密码"));
        warning.addView(passwordInput);
        warning.addView(confirm);
        warning.addView(status);
        warning.addView(deleteButton);
        root.addView(warning);
        setContentView(scroll(root));
    }

    private void deleteAccount(String password, Button sourceButton) {
        setButtonBusy(sourceButton, "正在注销...");
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/account/delete", new JSONObject()
                        .put("accountToken", accountToken)
                        .put("password", password));
                main.post(() -> {
                    logoutLocal();
                    setStatus("账号已注销，相关数据已删除。");
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus(friendlyError(e));
                });
            }
        });
    }

    private void showElder() {
        if ("family".equals(selectedAppRole)) {
            showFamily();
            return;
        }
        if (!ensureLoggedIn("elder")) {
            return;
        }
        restoreMembershipForRole("elder");
        currentPage = "elder";
        prefs.edit().putBoolean("elderPageVisible", true).apply();
        familyPolling = false;
        elderBindPolling = false;
        elderAnnotationPolling = true;
        elderScreenVisible = true;
        boolean assisting = prefs.getBoolean("assistActive", false);
        root = verticalRoot();
        root.addView(hero("找家人帮忙", assisting ? "家人正在帮助你" : "需要帮助时，只需点一次"));
        String pendingAssistMessage = prefs.getString("pendingAssistMessage", "");
        if (!pendingAssistMessage.isEmpty()) {
            prefs.edit().remove("pendingAssistMessage").apply();
        }
        status = notice(pendingAssistMessage);
        status.setVisibility(pendingAssistMessage.isEmpty() ? View.GONE : View.VISIBLE);

        if (!isBoundAs("elder")) {
            EditText nameInput = input("长辈名称，例如 妈妈", displayName);
            Button inviteButton = primaryButton("生成邀请绑定码");
            inviteButton.setOnClickListener(v -> {
                displayName = nameInput.getText().toString().trim();
                if (displayName.isEmpty()) {
                    displayName = "长辈";
                }
                prefs.edit().putString("displayName", displayName).apply();
                createInvite(inviteButton);
            });

            LinearLayout bindCard = card("邀请家人", "生成 6 位绑定码，让家人在手机上申请绑定。");
            bindCard.addView(label("我的称呼"));
            bindCard.addView(nameInput);
            bindCard.addView(inviteButton);
            root.addView(bindCard);
            root.addView(status);
            root.addView(bottomNav("elder"));
            setContentView(scroll(root));
            return;
        }

        String pendingInviteCode = prefs.getString("pendingInviteCode", "");
        if (!pendingInviteCode.isEmpty() || !prefs.getBoolean("familyBound", false)) {
            showElderInvite(pendingInviteCode);
            return;
        }

        ensureFamilyInviteNotifications();

        elderUiAssisting = assisting;
        Button helpButton = primaryButton(elderPrimaryButtonText());
        helpButton.setTextSize(24);
        Button stopButton = dangerButton("结束本次求助");

        helpButton.setOnClickListener(v -> {
            pendingTargetHelperRef = "";
            pendingTargetHelperName = "";
            pendingHelpInvitationId = "";
            setButtonBusy(helpButton, needsOverlayPermission() ? "正在打开权限..." : "正在打开屏幕授权...");
            handleElderPrimaryAction();
        });
        stopButton.setOnClickListener(v -> stopAssistance("本次求助已结束。"));
        LinearLayout stepsCard = card(assisting ? "协助进行中" : elderCurrentStepTitle(), elderAssistHintText());
        if (assisting) {
            root.addView(stepsCard);
            root.addView(stopButton);
        } else {
            root.addView(helpButton);
            root.addView(stepsCard);
            root.addView(status);
        }
        LinearLayout safetySummary = card("当前状态", "正在确认家人连接状态...");
        TextView safetySummaryText = (TextView) safetySummary.getChildAt(1);
        root.addView(safetySummary);
        loadElderHomeSummary(safetySummaryText, assisting);
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
        pollElderAnnotationLoop();
    }

    private void showElderInvite(String inviteCode) {
        currentPage = "elderInvite";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        elderInviteBoundShown = false;
        elderInviteBaselineFamilyCount = prefs.contains("pendingInviteFamilyCount")
                ? prefs.getInt("pendingInviteFamilyCount", 0)
                : Math.max(0, elderInviteBaselineFamilyCount);
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = true;
        elderScreenVisible = true;
        root = verticalRoot();
        root.addView(compactPageTitle("邀请家人"));
        status = notice("");
        status.setVisibility(View.GONE);

        TextView codeView = title(inviteCode == null || inviteCode.isEmpty() ? "------" : inviteCode);
        codeView.setTextSize(44);
        codeView.setTextColor(COLOR_BLUE_DARK);
        codeView.setPadding(0, dp(12), 0, dp(12));

        Button regenerateButton = secondaryButton("重新生成绑定码");
        Button cancelButton = dangerButton("停止添加");
        regenerateButton.setOnClickListener(v -> createInvite(regenerateButton));
        cancelButton.setOnClickListener(v -> cancelInviteWait(cancelButton));

        LinearLayout bindCard = card("邀请绑定码", "10 分钟内有效，把这个绑定码告诉家人。");
        bindCard.addView(codeView);
        bindCard.addView(regenerateButton);
        bindCard.addView(cancelButton);
        root.addView(bindCard);
        root.addView(status);
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
        pollElderBindLoop();
    }

    private void loadElderHomeSummary(TextView target, boolean assisting) {
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl,
                        "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                JSONArray members = family == null ? null : family.optJSONArray("familyMembers");
                StringBuilder names = new StringBuilder();
                if (members != null) {
                    for (int index = 0; index < members.length(); index++) {
                        JSONObject member = members.optJSONObject(index);
                        if (member == null) continue;
                        if (names.length() > 0) names.append("、");
                        names.append(member.optString("name", "家人"));
                    }
                }
                String connected = names.length() == 0 ? "暂无" : names.toString();
                boolean remoteAllowed = family != null && family.optBoolean("controlAllowed", false);
                String summary = "已连接家人：" + connected
                        + "\n屏幕共享：" + (assisting ? "已开启" : "未开始")
                        + "\n远程点击：" + (assisting && remoteAllowed ? "本次已允许" : "未允许");
                main.post(() -> {
                    if ("elder".equals(currentPage) && target != null) target.setText(summary);
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if ("elder".equals(currentPage) && target != null) {
                        target.setText("已连接家人：待刷新\n屏幕共享："
                                + (assisting ? "已开启" : "未开始") + "\n远程点击：未允许");
                    }
                });
            }
        });
    }

    private void showElderBoundSuccess(int familyCount, boolean invitePending, JSONObject addedMember) {
        currentPage = "elderBound";
        prefs.edit().putBoolean("elderPageVisible", false).apply();
        elderBindPolling = false;
        elderAnnotationPolling = false;
        root = verticalRoot();
        root.addView(compactPageTitle("已添加家人"));
        status = notice("");
        status.setVisibility(View.GONE);

        elderInviteBaselineFamilyCount = familyCount;
        prefs.edit().putInt("pendingInviteFamilyCount", familyCount).apply();
        String addedName = addedMember == null ? "新家人" : addedMember.optString("name", "新家人");
        String addedPhone = addedMember == null ? "" : addedMember.optString("phone", "");
        String addedLabel = addedPhone.isEmpty() ? addedName : addedName + "（" + addedPhone + "）";
        LinearLayout successCard = card("已添加 " + addedLabel, "需要帮助时，可以直接请这位家人接入。");
        Button prepareButton = primaryButton("返回家人列表");
        prepareButton.setOnClickListener(v -> finishAddingFamily(prepareButton));
        successCard.addView(prepareButton);
        if (invitePending) {
            Button continueButton = secondaryButton("继续添加其他家人");
            continueButton.setOnClickListener(v -> showElderInvite(prefs.getString("pendingInviteCode", "")));
            successCard.addView(continueButton);
        }
        root.addView(status);
        root.addView(successCard);
        root.addView(bottomNav("elder"));
        setContentView(scroll(root));
    }

    private void showSafetySettings() {
        if (!"elder".equals(selectedAppRole)) {
            showProfile();
            return;
        }
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
        LinearLayout safetyCard = card("权限与保护", "");
        safetyCard.addView(privacyButton);
        safetyCard.addView(overlayButton);
        safetyCard.addView(controlButton);
        safetyCard.addView(accessibilityButton);
        root.addView(safetyCard);
        root.addView(status);
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

        TextView policy = body(
                "更新日期：2026 年 8 月 11 日\n"
                        + "生效日期：2026 年 8 月 11 日\n\n"
                        + "亲情帮帮隐私政策\n\n"
                        + "亲情帮帮用于长辈与家属之间的手机协助。我们重视你的个人信息和操作安全，并遵循最少必要原则处理数据。\n\n"
                        + "一、我们处理的信息\n"
                        + "为提供账号、身份和亲属绑定服务，我们处理手机号、账号称呼、加密后的密码、使用身份、亲属关系和登录状态。密码不会以明文保存。\n\n"
                        + "为提供协助服务，我们处理本次会话状态、画圈坐标、远程操作授权状态和必要的安全审计记录。屏幕画面仅在长辈主动发起并确认系统授权后实时传输，默认不保存。\n\n"
                        + "为排查故障，我们可能处理设备型号、应用版本和崩溃日志摘要。日志不应包含密码或完整屏幕画面。\n\n"
                        + "二、权限用途\n"
                        + "屏幕录制权限用于共享长辈屏幕；悬浮窗权限用于显示家属发送的画圈提示；无障碍服务仅用于敏感页面保护和长辈当次明确同意后的远程操作；通知权限用于显示远程操作请求和协助结束提醒。\n\n"
                        + "三、协助安全规则\n"
                        + "未完成亲属绑定不能协助。长辈未主动发起时，家属不能查看屏幕。远程操作需要长辈每次明确授权，同一时间只允许一名家属协助，协助结束后授权立即失效。\n\n"
                        + "四、数据保存与保护\n"
                        + "账号密码使用带盐的单向算法保存，网络请求通过 HTTPS 加密传输。我们仅在实现服务、安全审计和故障排查所需期限内保存必要信息，并采取访问控制、限流和备份保护措施。\n\n"
                        + "五、你的权利\n"
                        + "你可以停止协助、撤销远程操作授权、解绑亲属、退出登录或在“我的 > 注销账号”中永久删除账号。注销后相关账号资料、亲属绑定和服务端记录将被删除且无法恢复。\n\n"
                        + "六、联系我们\n"
                        + "如需反馈隐私或安全问题，请通过应用市场中的开发者联系方式与我们联系。"
        );
        policy.setTextColor(COLOR_TEXT);
        policy.setTextSize(16);
        policy.setLineSpacing(dp(5), 1.0f);
        policy.setPadding(dp(4), dp(4), dp(4), dp(20));
        root.addView(policy);

        root.addView(bottomNav("profile"));
        setContentView(scroll(root));
    }

    private void showRelativesManagement() {
        if (!isLoggedIn()) {
            showLogin("auth");
            return;
        }
        currentPage = "relatives";
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        if ("elder".equals(selectedAppRole)) {
            showElderFamilyMembers();
            return;
        }
        root.addView(compactPageTitle("我的家人"));
        status = notice("");
        status.setVisibility(View.GONE);

        LinearLayout elderList = new LinearLayout(this);
        elderList.setOrientation(LinearLayout.VERTICAL);
        elderList.setLayoutParams(fullWidthParams());
        elderList.addView(card("正在加载", "正在获取已添加的长辈..."));
        root.addView(elderList);
        Button familyBindButton = familySecondaryButton("添加长辈");
        familyBindButton.setOnClickListener(v -> showFamilyBind());
        root.addView(familyBindButton);
        loadFamilyElderMemberships(elderList);
        root.addView(status);
        root.addView(bottomNav("relatives"));
        setContentView(scroll(root));
    }

    private void loadFamilyElderMemberships(LinearLayout list) {
        statusIo.execute(() -> {
            try {
                JSONObject account = NetworkClient.getJson(baseUrl,
                        "/api/account/me?accountToken=" + encoded(accountToken));
                JSONArray all = account.optJSONArray("memberships");
                JSONArray elders = new JSONArray();
                if (all != null) {
                    accountMembershipsJson = all.toString();
                    prefs.edit().putString("accountMemberships", accountMembershipsJson).apply();
                    for (int index = 0; index < all.length(); index++) {
                        JSONObject membership = all.optJSONObject(index);
                        if (membership == null || !"family".equals(membership.optString("role"))) continue;
                        try {
                            JSONObject state = NetworkClient.getJson(baseUrl,
                                    "/api/bind/status?pairCode=" + encoded(membership.optString("pairCode"))
                                            + "&authToken=" + encoded(membership.optString("authToken")));
                            membership.put("familyState", state.optJSONObject("family"));
                        } catch (Exception ignored) {
                            // The account snapshot still lets the user see the relative when a status refresh is slow.
                        }
                        elders.put(membership);
                    }
                }
                main.post(() -> renderFamilyElderMemberships(list, elders));
            } catch (Exception error) {
                main.post(() -> {
                    list.removeAllViews();
                    list.addView(card("暂时无法加载", "请检查网络后重新进入此页面。"));
                });
            }
        });
    }

    private void renderFamilyElderMemberships(LinearLayout list, JSONArray elders) {
        if (!"relatives".equals(currentPage) || !"family".equals(selectedAppRole)) return;
        list.removeAllViews();
        if (elders == null || elders.length() == 0) {
            list.addView(card("还没有添加长辈", "添加后，可以在这里快速请求为长辈提供协助。"));
            return;
        }
        list.addView(card("已添加的长辈", "发起请求后，需要长辈明确同意才会开始共享屏幕。"));
        for (int index = 0; index < elders.length(); index++) {
            JSONObject membership = elders.optJSONObject(index);
            if (membership == null) continue;
            String elderName = membership.optString("elderName", "长辈");
            JSONObject familyState = membership.optJSONObject("familyState");
            boolean active = familyState != null
                    ? familyState.optBoolean("active", false)
                    : membership.optBoolean("active", false);
            boolean helperIsCurrent = familyState != null && familyState.optBoolean("helperIsCurrent", false);
            JSONObject request = familyState == null ? null : familyState.optJSONObject("familyAssistRequest");
            String requestState = request == null ? "" : request.optString("status", "");
            LinearLayout elderCard = card(elderName, active ? "协助进行中" : "已安全绑定");
            Button assistButton = familyPrimaryButton("请求协助");
            if (active && helperIsCurrent) {
                assistButton.setText("继续协助");
                assistButton.setOnClickListener(v -> openFamilyMembership(membership));
            } else if (active) {
                setButtonDisabled(assistButton, "其他家属正在协助");
            } else if ("pending".equals(requestState)) {
                setButtonDisabled(assistButton, "等待" + elderName + "确认");
            } else if ("accepted".equals(requestState)) {
                setButtonDisabled(assistButton, elderName + "正在开启屏幕共享");
            } else {
                assistButton.setOnClickListener(v -> requestAssistFromElder(membership, elderName, assistButton));
            }
            elderCard.addView(assistButton);
            list.addView(elderCard);
        }
    }

    private void requestAssistFromElder(JSONObject membership, String elderName, Button sourceButton) {
        String selectedPair = membership.optString("pairCode", "");
        String selectedToken = membership.optString("authToken", "");
        if (selectedPair.isEmpty() || selectedToken.isEmpty() || remoteRequestInProgress) return;
        remoteRequestInProgress = true;
        setButtonBusy(sourceButton, "正在询问" + elderName + "...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/help/family-request", new JSONObject()
                        .put("pairCode", selectedPair)
                        .put("authToken", selectedToken));
                JSONObject request = result.optJSONObject("request");
                pendingFamilyAssistRequestId = request == null ? "" : request.optString("id", "");
                pendingFamilyAssistRequestExpiresAt = request == null ? 0L : request.optLong("expiresAt", 0L);
                main.post(() -> {
                    selectFamilyMembership(membership);
                    showFamily();
                    updateFamilyWaiting("等待" + elderName + "确认", "请求 10 分钟内有效。对方同意并共享屏幕后，画面会自动显示。");
                    showFamilyWaitingCancelAction();
                });
            } catch (Exception error) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus(friendlyFamilyAssistRequestError(error));
                });
            } finally {
                remoteRequestInProgress = false;
            }
        });
    }

    private void openFamilyMembership(JSONObject membership) {
        selectFamilyMembership(membership);
        showFamily();
    }

    private void selectFamilyMembership(JSONObject membership) {
        pairCode = membership.optString("pairCode", "");
        authToken = membership.optString("authToken", "");
        memberRole = "family";
        prefs.edit()
                .putString("pairCode", pairCode)
                .putString("authToken", authToken)
                .putString("memberRole", memberRole)
                .apply();
    }

    private String friendlyFamilyAssistRequestError(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("assist session is active")) return "已有家属正在协助这位长辈。";
        if (message.contains("another family assist request is pending")) return "另一位家属已经发出请求，请稍后再试。";
        return "请求没有发送成功，请检查网络后重试。";
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("只会退出这台手机的账号，不会删除已经建立的亲属关系。")
                .setPositiveButton("退出", (dialog, which) -> logoutLocal())
                .setNegativeButton("取消", null)
                .show();
    }

    private void logoutLocal() {
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        main.removeCallbacks(familyBindPendingLoopRunnable);
        stopService(new Intent(this, FamilyInviteMonitorService.class));
        stopFamilyWebRtc();
        accountToken = "";
        accountPhone = "";
        accountMembershipsJson = "[]";
        authToken = "";
        memberRole = "";
        selectedAppRole = "";
        prefs.edit()
                .remove("accountToken")
                .remove("accountPhone")
                .remove("accountMemberships")
                .remove("authToken")
                .remove("memberRole")
                .remove("selectedAppRole")
                .remove(PREF_ASSIST_SESSION_ID)
                .remove("pendingInviteCode")
                .remove("pendingInviteFamilyCount")
                .remove("pendingInviteMembers")
                .remove("pendingBindToken")
                .remove("pendingBindPairCode")
                .putBoolean("familyBound", false)
                .putBoolean("assistActive", false)
                .apply();
        showLogin("auth");
        setStatus("已退出登录。");
    }

    private void showFamily() {
        closeFamilyFullscreen();
        if ("elder".equals(selectedAppRole)) {
            showElder();
            return;
        }
        if (!ensureLoggedIn("family")) {
            return;
        }
        restoreMembershipForRole("family");
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
        familyPageTitleView = compactPageTitle("协助长辈");
        root.addView(familyPageTitleView);
        status = stableNotice("连接正常");
        status.setVisibility(View.GONE);
        View screenView = buildFrameView();

        familyControlRequestButton = familySecondaryButton("申请远程操作");
        familyRemoteButton = controlPrimaryButton("远程操作");
        familyEndButton = dangerButton("结束本次协助");
        familyChangeBindingButton = familySecondaryButton("选择家人");
        familyControlRequestButton.setOnClickListener(v -> requestRemoteControl(familyControlRequestButton));
        familyRemoteButton.setOnClickListener(v -> showRemoteControlPanel());
        familyEndButton.setOnClickListener(v -> endFamilyAssistView());
        familyChangeBindingButton.setOnClickListener(v -> showRelativesManagement());

        root.addView(status);
        familyWaitingView = card("暂时没有新的求助", "收到长辈求助后，屏幕会自动显示。");
        familyWaitingTitle = (TextView) familyWaitingView.getChildAt(0);
        familyWaitingCaption = (TextView) familyWaitingView.getChildAt(1);
        familyWaitingActionButton = familySecondaryButton("查看家人");
        familyWaitingActionButton.setOnClickListener(v -> showRelativesManagement());
        familyWaitingView.addView(familyWaitingActionButton);
        familyScreenLabelView = screenLabel("长辈的屏幕");
        familyScreenLabelView.setTextColor(COLOR_FAMILY);
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
        familyBottomNav = bottomNav("family");
        setContentView(familyPage(root, familyActionBar, familyBottomNav));
        ensureFamilyInviteNotifications();
        startFamilyPollLoop();
        main.postDelayed(this::maybeShowFamilyHelpInvitation, 250);
    }

    private void showElderFamilyMembers() {
        if (!"elder".equals(selectedAppRole)) {
            showRelativesManagement();
            return;
        }
        currentPage = "elderFamily";
        prefs.edit().putBoolean("elderPageVisible", true).apply();
        familyPolling = false;
        elderAnnotationPolling = false;
        elderBindPolling = false;
        root = verticalRoot();
        root.addView(compactPageTitle("我的家人"));
        status = notice("");
        status.setVisibility(View.GONE);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutParams(fullWidthParams());
        list.addView(card("正在加载", "正在获取已绑定的家人..."));
        root.addView(list);
        root.addView(status);

        elderAddFamilyButton = secondaryButton("邀请新家人");
        elderAddFamilyButton.setOnClickListener(v -> createInvite(elderAddFamilyButton));
        if (prefs.getBoolean("assistActive", false)) {
            setButtonDisabled(elderAddFamilyButton, "结束求助后可邀请");
        }
        root.addView(elderAddFamilyButton);
        root.addView(bottomNav("family"));
        setContentView(scroll(root));
        loadElderFamilyMembers(list);
    }

    private void loadElderFamilyMembers(LinearLayout list) {
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl,
                        "/api/bind/status?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject family = result.optJSONObject("family");
                JSONArray members = family == null ? null : family.optJSONArray("familyMembers");
                main.post(() -> renderElderFamilyMembers(list, family, members));
            } catch (Exception e) {
                main.post(() -> {
                    list.removeAllViews();
                    list.addView(card("暂时无法加载", "请检查网络后再试。"));
                    Button retryButton = secondaryButton("重新加载");
                    retryButton.setOnClickListener(v -> {
                        setButtonBusy(retryButton, "加载中...");
                        loadElderFamilyMembers(list);
                    });
                    list.addView(retryButton);
                });
            }
        });
    }

    private void renderElderFamilyMembers(LinearLayout list, JSONObject family, JSONArray members) {
        if (!"elderFamily".equals(currentPage)) {
            return;
        }
        list.removeAllViews();
        if (members == null || members.length() == 0) {
            list.addView(card("还没有家人", "邀请家人完成绑定后，就能在这里快速求助。"));
            return;
        }
        boolean active = family != null && family.optBoolean("active", false);
        if (elderAddFamilyButton != null) {
            elderAddFamilyButton.setVisibility(View.VISIBLE);
            if (active) {
                setButtonDisabled(elderAddFamilyButton, "结束求助后可邀请");
            } else {
                elderAddFamilyButton.setText("邀请新家人");
                elderAddFamilyButton.setEnabled(true);
                elderAddFamilyButton.setAlpha(1f);
            }
        }
        String activeHelperRef = family == null ? "" : family.optString("activeHelperRef", "");
        String targetHelperRef = family == null ? "" : family.optString("targetHelperRef", "");
        JSONObject invitation = family == null ? null : family.optJSONObject("helpInvitation");
        boolean invitationWaiting = invitation != null
                && ("pending".equals(invitation.optString("status"))
                || "accepted".equals(invitation.optString("status")));
        String invitationTargetRef = invitation == null ? "" : invitation.optString("targetHelperRef", "");
        list.addView(active
                ? card("正在接受帮助", "为避免中断当前连接，结束本次求助后才能添加新家人。")
                : card("选择一位家人", "需要帮助时，直接请熟悉的家人接入。"));
        for (int index = 0; index < members.length(); index++) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) continue;
            String ref = member.optString("ref", "");
            String name = member.optString("name", "家属");
            String phone = member.optString("phone", "");
            LinearLayout memberCard = card(name, phone.isEmpty() ? "已绑定" : phone + " · 已绑定");
            Button helpButton = primaryButton("请" + name + "帮忙");
            boolean thisHelperActive = active && (ref.equals(activeHelperRef) || ref.equals(targetHelperRef));
            boolean anotherHelperActive = active && !thisHelperActive;
            boolean thisInvitationWaiting = invitationWaiting && ref.equals(invitationTargetRef);
            boolean thisInvitationAccepted = thisInvitationWaiting
                    && "accepted".equals(invitation.optString("status"));
            if (thisHelperActive) {
                setButtonDisabled(helpButton, name + "正在协助");
            } else if (anotherHelperActive) {
                setButtonDisabled(helpButton, "已有家属正在协助");
            } else if (thisInvitationAccepted) {
                helpButton.setText("继续开启屏幕共享");
                helpButton.setOnClickListener(v -> resumeAcceptedFamilyHelp(
                        invitation.optString("id", ""), ref, name, helpButton));
            } else if (thisInvitationWaiting) {
                helpButton.setText("取消邀请");
                helpButton.setOnClickListener(v -> cancelSelectedFamilyHelp(invitation.optString("id", ""), name, helpButton));
            } else if (invitationWaiting) {
                setButtonDisabled(helpButton, "已邀请其他家属");
            } else {
                helpButton.setOnClickListener(v -> requestSelectedFamilyHelp(ref, name, helpButton));
            }
            memberCard.addView(helpButton);
            list.addView(memberCard);
        }
    }

    private void resumeAcceptedFamilyHelp(String invitationId, String memberRef,
                                          String memberName, Button sourceButton) {
        if (invitationId.isEmpty() || memberRef.isEmpty() || captureRequestInProgress) {
            return;
        }
        pendingHelpInvitationId = invitationId;
        pendingTargetHelperRef = memberRef;
        pendingTargetHelperName = memberName;
        selectedFamilyHelpButton = sourceButton;
        setButtonBusy(sourceButton, "正在打开屏幕共享...");
        requestHelpAndCapture();
    }

    private void requestSelectedFamilyHelp(String memberRef, String memberName, Button sourceButton) {
        if (memberRef.isEmpty() || captureRequestInProgress || !pendingHelpInvitationId.isEmpty()) {
            return;
        }
        pendingTargetHelperRef = memberRef;
        pendingTargetHelperName = memberName;
        selectedFamilyHelpButton = sourceButton;
        setButtonBusy(sourceButton, "正在通知" + memberName + "...");
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/help/invite", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("elderName", displayName)
                        .put("targetHelperRef", memberRef));
                JSONObject invitation = result.optJSONObject("invitation");
                pendingHelpInvitationId = invitation == null ? "" : invitation.optString("id", "");
                main.post(() -> {
                    setStatus("已提醒" + memberName + "，等待对方接受。");
                    if (selectedFamilyHelpButton != null) {
                        selectedFamilyHelpButton.setEnabled(true);
                        selectedFamilyHelpButton.setAlpha(1f);
                        selectedFamilyHelpButton.setText("取消邀请");
                        selectedFamilyHelpButton.setOnClickListener(v -> cancelSelectedFamilyHelp(
                                pendingHelpInvitationId, memberName, selectedFamilyHelpButton));
                    }
                    main.removeCallbacks(selectedHelpInvitePollLoop);
                    main.post(selectedHelpInvitePollLoop);
                });
            } catch (Exception e) {
                main.post(() -> {
                    String raw = String.valueOf(e.getMessage());
                    if (raw.contains("assist session is active")) {
                        finishSelectedHelpInvitation("已有家人正在协助，请先结束当前求助。");
                    } else if (raw.contains("another help invitation is pending")) {
                        finishSelectedHelpInvitation("已经邀请了另一位家人，请先取消后再选择。");
                    } else if (raw.contains("family member not found")) {
                        finishSelectedHelpInvitation("这位家人的绑定已失效，请刷新家人列表。");
                    } else {
                        finishSelectedHelpInvitation("网络暂时不可用，请检查后重试。");
                    }
                });
            }
        });
    }

    private void pollSelectedHelpInvitation() {
        final String expectedId = pendingHelpInvitationId;
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.getJson(baseUrl,
                        "/api/help/invite?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
                JSONObject invitation = result.optJSONObject("invitation");
                if (invitation == null || !expectedId.equals(invitation.optString("id", ""))) {
                    main.post(() -> finishSelectedHelpInvitation("邀请已经失效，请重新选择家人。"));
                    return;
                }
                String state = invitation.optString("status", "pending");
                if ("accepted".equals(state)) {
                    main.post(() -> {
                        main.removeCallbacks(selectedHelpInvitePollLoop);
                        setStatus(pendingTargetHelperName + "已接受，接下来请确认屏幕共享。 ");
                        requestHelpAndCapture();
                    });
                } else if ("declined".equals(state)) {
                    main.post(() -> finishSelectedHelpInvitation(pendingTargetHelperName + "暂时无法协助，可以选择其他家人。"));
                } else if ("expired".equals(state)) {
                    main.post(() -> finishSelectedHelpInvitation("对方暂未回应，可以重新邀请或选择其他家人。"));
                }
            } catch (Exception ignored) {
                // Keep waiting through short network interruptions until the server expires the invitation.
            }
        });
    }

    private void finishSelectedHelpInvitation(String message) {
        main.removeCallbacks(selectedHelpInvitePollLoop);
        pendingHelpInvitationId = "";
        pendingTargetHelperRef = "";
        pendingTargetHelperName = "";
        restoreButton(selectedFamilyHelpButton);
        selectedFamilyHelpButton = null;
        setStatus(message);
    }

    private void cancelSelectedFamilyHelp(String invitationId, String memberName, Button sourceButton) {
        if (invitationId == null || invitationId.isEmpty()) {
            finishSelectedHelpInvitation("这次邀请已结束，可以重新选择家人。");
            showElderFamilyMembers();
            return;
        }
        setButtonBusy(sourceButton, "正在取消...");
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/help/invite/cancel", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("invitationId", invitationId));
                main.post(() -> {
                    finishSelectedHelpInvitation("已取消对" + memberName + "的邀请。");
                    showElderFamilyMembers();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isExpiredOrHandledRequest(error)) {
                        finishSelectedHelpInvitation("这次邀请已经结束，可以重新选择家人。");
                        showElderFamilyMembers();
                    } else {
                        restoreButton(sourceButton);
                        setStatus("暂时无法取消，请检查网络后重试。");
                    }
                });
            }
        });
    }

    private void startFamilyInviteMonitor() {
        Intent intent = new Intent(this, FamilyInviteMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void ensureFamilyInviteNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_FAMILY_NOTIFICATIONS);
            return;
        }
        startFamilyInviteMonitor();
    }

    private void maybeShowFamilyHelpInvitation() {
        if (!appInForeground || familyHelpInvitePromptShowing || !"family".equals(memberRole)) {
            return;
        }
        String raw = prefs.getString("pendingFamilyHelpInvitation", "");
        if (raw.isEmpty()) return;
        try {
            JSONObject invitation = new JSONObject(raw);
            String invitationId = invitation.optString("id", "");
            String elderName = invitation.optString("elderName", "长辈");
            if (invitationId.isEmpty() || !"pending".equals(invitation.optString("status", "pending"))) {
                prefs.edit().remove("pendingFamilyHelpInvitation").apply();
                return;
            }
            if (invitationId.equals(respondingFamilyHelpInvitationId)
                    || isFamilyHelpInvitationResponseFresh(invitationId)
                    || invitationId.equals(prefs.getString("handledFamilyHelpInvitationId", ""))) {
                return;
            }
            familyHelpInvitePromptShowing = true;
            new AlertDialog.Builder(this)
                    .setTitle(elderName + "需要你帮忙")
                    .setMessage("接受后，长辈才会开始共享屏幕。请确认现在方便协助。")
                    .setPositiveButton("接受并准备协助", (dialog, which) -> {
                        markFamilyHelpInvitationResponding(invitationId);
                        respondToFamilyHelpInvitation(invitationId, elderName, true);
                    })
                    .setNegativeButton("暂时无法协助", (dialog, which) -> {
                        markFamilyHelpInvitationResponding(invitationId);
                        respondToFamilyHelpInvitation(invitationId, elderName, false);
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception ignored) {
            prefs.edit().remove("pendingFamilyHelpInvitation").apply();
        }
    }

    private void markFamilyHelpInvitationResponding(String invitationId) {
        respondingFamilyHelpInvitationId = invitationId;
        prefs.edit()
                .putString("respondingFamilyHelpInvitationId", invitationId)
                .putLong("respondingFamilyHelpInvitationAtMs", System.currentTimeMillis())
                .remove("pendingFamilyHelpInvitation")
                .commit();
        AssistNotifier.cancelHelpInviteNotification(this);
    }

    private boolean isFamilyHelpInvitationResponseFresh(String invitationId) {
        if (!invitationId.equals(prefs.getString("respondingFamilyHelpInvitationId", ""))) {
            return false;
        }
        long startedAt = prefs.getLong("respondingFamilyHelpInvitationAtMs", 0L);
        if (startedAt > 0 && System.currentTimeMillis() - startedAt < 15_000L) {
            return true;
        }
        prefs.edit()
                .remove("respondingFamilyHelpInvitationId")
                .remove("respondingFamilyHelpInvitationAtMs")
                .apply();
        return false;
    }

    private void respondToFamilyHelpInvitation(String invitationId, String elderName, boolean accepted) {
        prefs.edit().remove("pendingFamilyHelpInvitation").apply();
        AssistNotifier.cancelHelpInviteNotification(this);
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/help/invite/respond", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("invitationId", invitationId)
                        .put("accepted", accepted));
                main.post(() -> {
                    respondingFamilyHelpInvitationId = "";
                    familyHelpInvitePromptShowing = false;
                    prefs.edit()
                            .remove("respondingFamilyHelpInvitationId")
                            .remove("respondingFamilyHelpInvitationAtMs")
                            .putString("handledFamilyHelpInvitationId", invitationId)
                            .remove("pendingFamilyHelpInvitation")
                            .apply();
                    if (!"family".equals(currentPage)) showFamily();
                    setStatus(accepted
                            ? "已接受" + elderName + "的请求，等待长辈确认屏幕共享。"
                            : "已告诉" + elderName + "你暂时无法协助。 ");
                });
            } catch (Exception e) {
                main.post(() -> {
                    respondingFamilyHelpInvitationId = "";
                    familyHelpInvitePromptShowing = false;
                    prefs.edit()
                            .remove("respondingFamilyHelpInvitationId")
                            .remove("respondingFamilyHelpInvitationAtMs")
                            .apply();
                    if (isExpiredOrHandledRequest(e)) {
                        prefs.edit()
                                .putString("handledFamilyHelpInvitationId", invitationId)
                                .remove("pendingFamilyHelpInvitation")
                                .apply();
                        new AlertDialog.Builder(this)
                                .setTitle("请求已结束")
                                .setMessage("这次求助已过期或已被处理，无需重复操作。")
                                .setPositiveButton("知道了", (dialog, which) -> showFamily())
                                .show();
                    } else {
                        setStatus("网络暂时不可用，请检查后重试。");
                    }
                });
            }
        });
    }

    private void maybeShowElderFamilyAssistRequest() {
        if (!appInForeground || elderFamilyAssistPromptShowing
                || !"elder".equals(selectedAppRole) || !"elder".equals(memberRole)) {
            return;
        }
        String raw = prefs.getString("pendingFamilyAssistRequest", "");
        if (raw.isEmpty()) return;
        try {
            JSONObject request = new JSONObject(raw);
            String requestId = request.optString("id", "");
            String helperName = request.optString("helperName", "家属");
            if (requestId.isEmpty() || !"pending".equals(request.optString("status", "pending"))) {
                prefs.edit().remove("pendingFamilyAssistRequest").apply();
                return;
            }
            if (requestId.equals(respondingElderFamilyAssistRequestId)
                    || requestId.equals(prefs.getString("handledElderFamilyAssistRequestId", ""))) {
                return;
            }
            elderFamilyAssistPromptShowing = true;
            new AlertDialog.Builder(this)
                    .setTitle("第 1/2 步 · " + helperName + " 请求协助")
                    .setMessage("同意后将进入第 2 步，由系统确认是否共享屏幕。远程点击不会自动开启，需要时会另行征得你的同意。")
                    .setPositiveButton("下一步：确认屏幕共享", (dialog, which) -> {
                        elderFamilyAssistPromptShowing = false;
                        markElderFamilyAssistRequestResponding(requestId);
                        respondToElderFamilyAssistRequest(requestId, helperName, true);
                    })
                    .setNegativeButton("现在不需要", (dialog, which) -> {
                        elderFamilyAssistPromptShowing = false;
                        markElderFamilyAssistRequestResponding(requestId);
                        respondToElderFamilyAssistRequest(requestId, helperName, false);
                    })
                    .setOnCancelListener(dialog -> elderFamilyAssistPromptShowing = false)
                    .show();
        } catch (Exception ignored) {
            prefs.edit().remove("pendingFamilyAssistRequest").apply();
        }
    }

    private void markElderFamilyAssistRequestResponding(String requestId) {
        respondingElderFamilyAssistRequestId = requestId;
        prefs.edit().remove("pendingFamilyAssistRequest").commit();
        AssistNotifier.cancelFamilyAssistRequestNotification(this);
    }

    private void respondToElderFamilyAssistRequest(String requestId, String helperName, boolean accepted) {
        prefs.edit().remove("pendingFamilyAssistRequest").apply();
        AssistNotifier.cancelFamilyAssistRequestNotification(this);
        statusIo.execute(() -> {
            try {
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/help/family-request/respond", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("requestId", requestId)
                        .put("accepted", accepted));
                respondingElderFamilyAssistRequestId = "";
                prefs.edit().putString("handledElderFamilyAssistRequestId", requestId).apply();
                if (!accepted) {
                    main.post(() -> {
                        if (!"elder".equals(currentPage)) showElder();
                        setStatus("已告诉" + helperName + "你现在不需要协助。 ");
                    });
                    return;
                }
                JSONObject invitation = result.optJSONObject("helpInvitation");
                pendingHelpInvitationId = invitation == null ? requestId : invitation.optString("id", requestId);
                pendingTargetHelperRef = invitation == null ? "" : invitation.optString("targetHelperRef", "");
                pendingTargetHelperName = helperName;
                main.post(() -> {
                    if (!"elder".equals(currentPage)) showElder();
                    setStatus("已同意" + helperName + "的请求，请继续确认屏幕共享。 ");
                    requestHelpAndCapture();
                });
            } catch (Exception error) {
                main.post(() -> handleElderAssistResponseError(error, helperName));
            }
        });
    }

    private void handleElderAssistResponseError(Exception error, String helperName) {
        respondingElderFamilyAssistRequestId = "";
        prefs.edit().remove("pendingFamilyAssistRequest").apply();
        AssistNotifier.cancelFamilyAssistRequestNotification(this);
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        String title;
        String message;
        if (raw.contains("expired") || raw.contains("not found") || raw.contains("already handled")) {
            title = "请求已结束";
            message = "这次协助请求已过期或已处理，请让" + helperName + "重新发起。";
        } else if (raw.contains("assist session is active")) {
            title = "已有家人正在协助";
            message = "请先结束当前求助，再接受新的请求。";
        } else {
            prefs.edit().remove("handledElderFamilyAssistRequestId").apply();
            setStatus("网络暂时不可用，请检查后重试。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", (dialog, which) -> showElder())
                .show();
    }

    private boolean isExpiredOrHandledRequest(Exception error) {
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        return raw.contains("expired") || raw.contains("not found") || raw.contains("already handled");
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

        Button homeButton = familySecondaryButton("主页");
        Button backButton = familySecondaryButton("返回");
        Button swipeUpButton = familySecondaryButton("上滑");
        Button swipeDownButton = familySecondaryButton("下滑");
        Button swipeLeftButton = familySecondaryButton("左滑");
        Button swipeRightButton = familySecondaryButton("右滑");

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
        addFamilyAction(bar, familyChangeBindingButton);
        familyMoreButton = new ImageButton(this);
        familyMoreButton.setImageResource(R.drawable.ic_more_vert);
        familyMoreButton.setColorFilter(COLOR_TEXT);
        familyMoreButton.setContentDescription("更多操作");
        familyMoreButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        familyMoreButton.setBackground(rounded(0xFFFFF4F6, dp(8), COLOR_LINE));
        familyMoreButton.setOnClickListener(this::showFamilyMoreMenu);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        moreParams.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(familyMoreButton, moreParams);
        familyRemoteButton.setVisibility(View.GONE);
        familyMoreButton.setVisibility(View.GONE);
        return bar;
    }

    private void showFamilyMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("查看我的家人");
        if (familyLastActive) {
            menu.getMenu().add("结束本次协助");
        }
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if ("结束本次协助".equals(title)) {
                new AlertDialog.Builder(this)
                        .setTitle("结束本次协助？")
                        .setMessage("结束后，长辈的屏幕共享和远程操作授权会立即关闭。")
                        .setPositiveButton("结束协助", (dialog, which) -> endFamilyAssistView())
                        .setNegativeButton("继续协助", null)
                        .show();
            } else {
                showRelativesManagement();
            }
            return true;
        });
        menu.show();
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
        root.addView(compactPageTitle("绑定长辈"));
        status = notice("");
        status.setVisibility(View.GONE);

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

        LinearLayout bindCard = card("输入长辈手机上的 6 位码", "");
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
        root.addView(compactPageTitle("等待长辈确认"));
        status = notice("");
        status.setVisibility(View.GONE);
        Button refreshButton = primaryButton("查看确认结果");
        Button cancelButton = secondaryButton("重新输入绑定码");
        refreshButton.setOnClickListener(v -> pollFamilyBindPendingOnce());
        cancelButton.setOnClickListener(v -> {
            prefs.edit().remove("pendingBindToken").remove("pendingBindPairCode").apply();
            main.removeCallbacks(familyBindPendingLoopRunnable);
            showFamilyBind();
        });
        LinearLayout waitCard = card("申请已发送", "请让长辈在自己手机上点“同意绑定”。");
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
        pairCode = "";
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
        final String targetHelperRef = pendingTargetHelperRef;
        final String targetHelperName = pendingTargetHelperName;
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("elderName", displayName)
                        .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("masked", isPrivacyMasked());
                if (!targetHelperRef.isEmpty()) {
                    payload.put("targetHelperRef", targetHelperRef);
                    payload.put("helpInvitationId", pendingHelpInvitationId);
                }
                JSONObject result = NetworkClient.postJson(baseUrl, "/api/help", payload);
                String sessionId = result.optString("sessionId", "");
                prefs.edit().putString(PREF_ASSIST_SESSION_ID, sessionId).apply();
                if (afterSuccess != null) {
                    main.post(() -> {
                        pendingTargetHelperRef = "";
                        pendingTargetHelperName = "";
                        pendingHelpInvitationId = "";
                        selectedFamilyHelpButton = null;
                        afterSuccess.run();
                        if (!targetHelperName.isEmpty()) {
                            setStatus("已通知" + targetHelperName + "，正在等待接入。需要结束时点“结束本次求助”。");
                        }
                    });
                }
            } catch (Exception e) {
                main.post(() -> {
                    pendingTargetHelperRef = "";
                    pendingTargetHelperName = "";
                    pendingHelpInvitationId = "";
                    restoreButton(selectedFamilyHelpButton);
                    selectedFamilyHelpButton = null;
                    markAssistanceStoppedLocal();
                    stopCaptureServices();
                    showElder();
                    setStatus("发起求助失败：" + friendlyError(e) + "。请检查网络后重试。");
                });
            }
        });
    }

    private void loginAccount(String phone, String password, String name, String afterRole, boolean register, Button sourceButton) {
        if (accountRequestInProgress) {
            return;
        }
        if (phone.isEmpty()) {
            setStatus("请输入手机号。");
            return;
        }
        if (!phone.matches("^\\+?[0-9]{6,18}$")) {
            setStatus("请输入正确的手机号。");
            return;
        }
        if (password.isEmpty()) {
            setStatus("请输入密码。");
            return;
        }
        if (password.length() < 6) {
            setStatus("密码至少 6 位。");
            return;
        }
        if (name.isEmpty()) {
            name = "亲友";
        }
        final String finalName = name;
        accountRequestInProgress = true;
        setButtonBusy(sourceButton, register ? "注册中..." : "登录中...");
        setStatus(register ? "正在注册..." : "正在登录...");
        statusIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("phone", phone)
                        .put("password", password);
                if (register) {
                    payload.put("name", finalName);
                }
                JSONObject result = NetworkClient.postJson(baseUrl, register ? "/api/account/register" : "/api/account/login", payload);
                saveAccountResult(result, phone, finalName);
                main.post(() -> {
                    restoreMembershipForRole(selectedAppRole);
                    showSetup();
                    if (!hasSelectedAppRole()) {
                        setStatus(register ? "注册成功，请选择你的使用身份。" : "请选择你的使用身份。");
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus(friendlyError(e));
                });
            } finally {
                accountRequestInProgress = false;
            }
        });
    }

    private void saveAccountResult(JSONObject result, String fallbackPhone, String fallbackName) {
        accountToken = result.optString("accountToken", "");
        JSONObject user = result.optJSONObject("user");
        accountPhone = user != null ? user.optString("phone", fallbackPhone) : fallbackPhone;
        displayName = user != null ? user.optString("name", fallbackName) : fallbackName;
        selectedAppRole = user != null ? user.optString("appRole", "") : "";
        if (!"elder".equals(selectedAppRole) && !"family".equals(selectedAppRole)) {
            selectedAppRole = "";
        }
        JSONArray memberships = result.optJSONArray("memberships");
        accountMembershipsJson = memberships == null ? "[]" : memberships.toString();
        clearLocalMembershipForAccountChange();
        prefs.edit()
                .putString("accountToken", accountToken)
                .putString("accountPhone", accountPhone)
                .putString("displayName", displayName)
                .putString("accountMemberships", accountMembershipsJson)
                .putString("selectedAppRole", selectedAppRole)
                .apply();
    }

    private void clearLocalMembershipForAccountChange() {
        authToken = "";
        memberRole = "";
        pairCode = "";
        prefs.edit()
                .remove("authToken")
                .remove("memberRole")
                .remove("pairCode")
                .remove(PREF_ASSIST_SESSION_ID)
                .remove("pendingInviteCode")
                .remove("pendingInviteFamilyCount")
                .remove("pendingInviteMembers")
                .remove("pendingBindToken")
                .remove("pendingBindPairCode")
                .putBoolean("familyBound", false)
                .putBoolean("assistActive", false)
                .apply();
    }

    private boolean restoreMembershipForRole(String role) {
        if (role == null || role.isEmpty() || (!"elder".equals(role) && !"family".equals(role))) {
            return false;
        }
        if (role.equals(memberRole) && authToken != null && !authToken.isEmpty()) {
            return true;
        }
        try {
            JSONArray memberships = new JSONArray(accountMembershipsJson == null ? "[]" : accountMembershipsJson);
            for (int index = 0; index < memberships.length(); index++) {
                JSONObject membership = memberships.optJSONObject(index);
                if (membership == null || !role.equals(membership.optString("role"))) continue;
                String restoredToken = membership.optString("authToken", "");
                String restoredPairCode = membership.optString("pairCode", "");
                if (restoredToken.isEmpty() || restoredPairCode.isEmpty()) continue;
                authToken = restoredToken;
                pairCode = restoredPairCode;
                memberRole = role;
                SharedPreferences.Editor editor = prefs.edit()
                        .putString("authToken", authToken)
                        .putString("pairCode", pairCode)
                        .putString("memberRole", memberRole);
                if ("elder".equals(role)) {
                    editor.putBoolean("familyBound", membership.optInt("familyMemberCount", 0) > 0);
                }
                editor.apply();
                return true;
            }
        } catch (Exception ignored) {
            accountMembershipsJson = "[]";
            prefs.edit().putString("accountMemberships", accountMembershipsJson).apply();
        }
        return false;
    }

    private void clearStatusOnFocus(EditText... fields) {
        for (EditText field : fields) {
            field.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) setStatus("");
            });
        }
    }

    private void createInvite(Button sourceButton) {
        if (inviteInProgress) {
            return;
        }
        if (prefs.getBoolean("assistActive", false)) {
            setStatus("当前正在接受家人帮助，结束本次求助后才能添加新家人。");
            return;
        }
        inviteInProgress = true;
        ensureElderPairCode();
        setButtonBusy(sourceButton, "生成中...");
        setStatus("正在生成邀请绑定码...");
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
                JSONArray baselineMembers = result.optJSONArray("familyMembers");
                elderInviteBaselineFamilyCount = familyMemberCount;
                prefs.edit()
                        .putString("authToken", authToken)
                        .putString("memberRole", memberRole)
                        .putString("pendingInviteCode", inviteCode)
                        .putInt("pendingInviteFamilyCount", familyMemberCount)
                        .putString("pendingInviteMembers", baselineMembers == null ? "[]" : baselineMembers.toString())
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
                prefs.edit()
                        .remove("pendingInviteFamilyCount")
                        .remove("pendingInviteMembers")
                        .apply();
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

    private void finishAddingFamily(Button sourceButton) {
        if (authToken.isEmpty()) {
            prefs.edit()
                    .remove("pendingInviteCode")
                    .remove("pendingInviteFamilyCount")
                    .remove("pendingInviteMembers")
                    .apply();
            showElderFamilyMembers();
            return;
        }
        setButtonBusy(sourceButton, "正在返回...");
        elderBindPolling = false;
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/invite/cancel", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken));
                prefs.edit()
                        .remove("pendingInviteCode")
                        .remove("pendingInviteFamilyCount")
                        .remove("pendingInviteMembers")
                        .apply();
                main.post(this::showElderFamilyMembers);
            } catch (Exception e) {
                main.post(() -> {
                    restoreButton(sourceButton);
                    setStatus("暂时无法结束添加，请稍后重试。");
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
        if (familyEndButton != null) familyEndButton.setVisibility(View.GONE);
        if (familyControlRequestButton != null) familyControlRequestButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (familyRemoteButton != null) familyRemoteButton.setVisibility(View.GONE);
        if (familyMoreButton != null) familyMoreButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setFamilySessionActive(boolean active) {
        if (active) {
            updateFamilyWaiting("正在连接屏幕", "画面出现后即可帮助长辈。");
            if (familyWaitingActionButton != null) familyWaitingActionButton.setVisibility(View.GONE);
            if (!familyMediaReady && familyScreenSurface != null) {
                resizeFamilyScreenSurface(dp(2));
            }
        } else {
            closeFamilyFullscreen();
            familyMediaReady = false;
            updateFamilyWaiting("暂时没有新的求助", "收到长辈求助后，屏幕会自动显示。");
            showFamilyWaitingBrowseAction();
            updateFamilyControlButton(false, false);
            resizeFamilyScreenSurface(dp(500));
        }
        if (familyWaitingView != null) familyWaitingView.setVisibility(active && familyMediaReady ? View.GONE : View.VISIBLE);
        if (familyScreenLabelView != null) familyScreenLabelView.setVisibility(View.GONE);
        if (familyScreenSurface != null) familyScreenSurface.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyFullscreenButton != null) familyFullscreenButton.setVisibility(active && familyMediaReady ? View.VISIBLE : View.GONE);
        if (familyChangeBindingButton != null) familyChangeBindingButton.setVisibility(active ? View.GONE : View.VISIBLE);
        if (familyBottomNav != null) familyBottomNav.setVisibility(active ? View.GONE : View.VISIBLE);
        setFamilySessionActionsVisible(active);
    }

    private void updateFamilyWaiting(String titleText, String captionText) {
        if (familyWaitingTitle != null) familyWaitingTitle.setText(titleText);
        if (familyWaitingCaption != null) familyWaitingCaption.setText(captionText);
    }

    private void showFamilyWaitingCancelAction() {
        if (familyWaitingActionButton == null) return;
        familyWaitingActionButton.setVisibility(View.VISIBLE);
        familyWaitingActionButton.setText("取消本次请求");
        familyWaitingActionButton.setEnabled(true);
        familyWaitingActionButton.setAlpha(1f);
        familyWaitingActionButton.setOnClickListener(v -> cancelFamilyAssistRequest(familyWaitingActionButton));
        if (familyChangeBindingButton != null) familyChangeBindingButton.setVisibility(View.GONE);
    }

    private void showFamilyWaitingRetryAction() {
        if (familyWaitingActionButton == null) return;
        familyWaitingActionButton.setVisibility(View.VISIBLE);
        familyWaitingActionButton.setText("重新选择家人");
        familyWaitingActionButton.setEnabled(true);
        familyWaitingActionButton.setAlpha(1f);
        familyWaitingActionButton.setOnClickListener(v -> showRelativesManagement());
        if (familyChangeBindingButton != null) familyChangeBindingButton.setVisibility(View.GONE);
    }

    private void showFamilyWaitingBrowseAction() {
        pendingFamilyAssistRequestId = "";
        pendingFamilyAssistRequestExpiresAt = 0L;
        if (familyWaitingActionButton == null) return;
        familyWaitingActionButton.setVisibility(View.GONE);
        if (familyChangeBindingButton != null) familyChangeBindingButton.setVisibility(View.VISIBLE);
    }

    private void cancelFamilyAssistRequest(Button sourceButton) {
        final String requestId = pendingFamilyAssistRequestId;
        if (requestId.isEmpty()) {
            showRelativesManagement();
            return;
        }
        setButtonBusy(sourceButton, "正在取消...");
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/help/family-request/cancel", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("requestId", requestId));
                main.post(() -> {
                    updateFamilyWaiting("请求已取消", "可以从“家人”页面选择长辈重新发起。");
                    showFamilyWaitingRetryAction();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isExpiredOrHandledRequest(error)) {
                        updateFamilyWaiting("请求已结束", "这次请求已过期或已处理，可以重新发起。");
                        showFamilyWaitingRetryAction();
                    } else {
                        restoreButton(sourceButton);
                        setStatus("暂时无法取消，请检查网络后重试。");
                    }
                });
            }
        });
    }

    private void withdrawAcceptedFamilyAssistRequest(String requestId) {
        statusIo.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/help/family-request/withdraw", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("requestId", requestId));
            } catch (Exception ignored) {
                // The accepted request also expires server-side; the local screen-sharing state is already cleared.
            }
        });
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
        familyScreenLabelView.setVisibility(View.GONE);
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
                JSONObject helpInvitation = help.optJSONObject("helpInvitation");
                String helpInvitationState = helpInvitation == null ? "" : helpInvitation.optString("status", "");
                String invitedElderName = helpInvitation == null ? "长辈" : helpInvitation.optString("elderName", "长辈");
                JSONObject familyAssistRequest = help.optJSONObject("familyAssistRequest");
                String familyAssistRequestState = familyAssistRequest == null
                        ? "" : familyAssistRequest.optString("status", "");
                String requestedElderName = familyAssistRequest == null
                        ? "长辈" : familyAssistRequest.optString("elderName", "长辈");
                String familyAssistRequestId = familyAssistRequest == null
                        ? "" : familyAssistRequest.optString("id", "");
                long familyAssistExpiresAt = familyAssistRequest == null
                        ? 0L : familyAssistRequest.optLong("expiresAt", 0L);
                    if (!active) {
                    main.post(() -> {
                        familyLastSessionId = "";
                        stopFamilyWebRtc();
                        clearFamilyScreen();
                        setFamilySessionActive(false);
                        if ("accepted".equals(helpInvitationState)) {
                            updateFamilyWaiting("已接受求助", "正在等待" + invitedElderName + "确认屏幕共享。 ");
                        } else if ("pending".equals(familyAssistRequestState)) {
                            pendingFamilyAssistRequestId = familyAssistRequestId;
                            pendingFamilyAssistRequestExpiresAt = familyAssistExpiresAt;
                            updateFamilyWaiting("等待" + requestedElderName + "确认",
                                    familyAssistRemainingText(familyAssistExpiresAt));
                            showFamilyWaitingCancelAction();
                        } else if ("accepted".equals(familyAssistRequestState)) {
                            updateFamilyWaiting(requestedElderName + "已同意",
                                    "长辈正在确认屏幕共享，画面稍后会自动显示。 ");
                            if (familyWaitingActionButton != null) familyWaitingActionButton.setVisibility(View.GONE);
                        } else if ("declined".equals(familyAssistRequestState)) {
                            updateFamilyWaiting(requestedElderName + "暂时不需要协助",
                                    "可以稍后从“家人”页面再次发起请求。 ");
                            showFamilyWaitingRetryAction();
                        } else if ("expired".equals(familyAssistRequestState)
                                || "cancelled".equals(familyAssistRequestState)) {
                            updateFamilyWaiting("请求已结束", "可以从“家人”页面重新发起。");
                            showFamilyWaitingRetryAction();
                        }
                        if (familyPageTitleView != null) familyPageTitleView.setText("协助长辈");
                        if (wasActive) {
                            updateFamilyWaiting("本次协助已结束", "需要继续时，可在“家人”页面重新发起。");
                            showFamilyWaitingRetryAction();
                        }
                        setStatus(wasActive
                                ? "本次协助已结束"
                                : "连接正常");
                    });
                    return;
                }
                boolean helperIsCurrent = !help.has("helperIsCurrent") || help.optBoolean("helperIsCurrent", false);
                String helperName = help.optString("helperName", "家人");
                boolean targetedForCurrent = help.optBoolean("targetedForCurrent", true);
                String targetHelperName = help.optString("targetHelperName", "");
                if (!targetedForCurrent) {
                    familyControlAllowed = false;
                    main.post(() -> {
                        familyLastSessionId = "";
                        stopFamilyWebRtc();
                        clearFamilyScreen();
                        setFamilySessionActive(false);
                        if (familyWaitingTitle != null) familyWaitingTitle.setText("暂时无需你协助");
                        if (familyWaitingCaption != null) {
                            familyWaitingCaption.setText(targetHelperName.isEmpty()
                                    ? "长辈这次邀请了其他家属。"
                                    : "长辈这次邀请了" + targetHelperName + "。");
                        }
                    });
                    return;
                }
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
                    prefs.edit().remove("pendingFamilyHelpInvitation").apply();
                    AssistNotifier.cancelHelpInviteNotification(this);
                    setFamilySessionActive(true);
                    if (familyPageTitleView != null) familyPageTitleView.setText("协助" + elderName);
                    if (familyScreenLabelView != null) familyScreenLabelView.setText(elderName + "的屏幕");
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
                main.post(() -> setStatus("网络不稳定，正在重连"));
            } finally {
                familyPollInFlight = false;
            }
        });
    }

    private String familyAssistRemainingText(long expiresAt) {
        if (expiresAt <= 0L) return "等待长辈确认。对方同意后会继续确认屏幕共享。";
        long remainingMinutes = Math.max(1L, (expiresAt - System.currentTimeMillis() + 59_999L) / 60_000L);
        return "等待长辈确认，约 " + remainingMinutes + " 分钟后过期。";
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
                main.post(() -> setStatus("网络不稳定，正在重新获取画面"));
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
                main.post(() -> setStatus("画圈提示没有发送成功。"));
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
                    setStatus("远程操作请求没有发送成功，请重试。");
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
                JSONArray familyMembers = family == null ? null : family.optJSONArray("familyMembers");
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
                int baselineFamilyCount = elderInviteBaselineFamilyCount >= 0
                        ? elderInviteBaselineFamilyCount
                        : prefs.getInt("pendingInviteFamilyCount", 0);
                if (familyCount > baselineFamilyCount) {
                    prefs.edit()
                            .putBoolean("familyBound", true)
                            .putString("pendingInviteMembers",
                                    familyMembers == null ? "[]" : familyMembers.toString())
                            .apply();
                    if (!elderInviteBoundShown) {
                        elderInviteBoundShown = true;
                        elderBindPolling = false;
                        JSONObject addedMember = findAddedFamilyMember(familyMembers);
                        main.post(() -> showElderBoundSuccess(
                                familyCount, supportsMultipleFamily && invitePending, addedMember));
                    }
                } else if (!invitePending) {
                    prefs.edit()
                            .remove("pendingInviteCode")
                            .remove("pendingInviteFamilyCount")
                            .remove("pendingInviteMembers")
                            .apply();
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
                main.post(() -> setStatus("网络连接不稳定，正在继续等待家属绑定。"));
            }
        });
    }

    private JSONObject findAddedFamilyMember(JSONArray members) {
        if (members == null || members.length() == 0) return null;
        java.util.HashSet<String> baselineRefs = new java.util.HashSet<>();
        try {
            JSONArray baseline = new JSONArray(prefs.getString("pendingInviteMembers", "[]"));
            for (int index = 0; index < baseline.length(); index++) {
                JSONObject member = baseline.optJSONObject(index);
                if (member != null) baselineRefs.add(member.optString("ref", ""));
            }
        } catch (Exception ignored) {
            // A legacy server may not have returned the baseline list.
        }
        JSONObject newest = null;
        for (int index = 0; index < members.length(); index++) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) continue;
            if (!baselineRefs.contains(member.optString("ref", ""))) return member;
            newest = member;
        }
        return newest;
    }

    private void showPendingBindApproval(JSONObject pending) {
        String requestId = pending.optString("id", "");
        String name = pending.optString("requesterName", "家属");
        String phone = pending.optString("requesterPhone", "");
        new AlertDialog.Builder(this)
                .setTitle("确认添加家人")
                .setMessage(name + (phone.isEmpty() ? "" : "（" + phone + "）") + " 想成为你的协助家人。同意后，对方可以在你求助时收到提醒。")
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
                .remove("pendingInviteFamilyCount")
                .remove("pendingInviteMembers")
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
        main.removeCallbacks(selectedHelpInvitePollLoop);
        pendingTargetHelperRef = "";
        pendingTargetHelperName = "";
        pendingHelpInvitationId = "";
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

    private String friendlyError(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("invalid phone or password")) {
            return "手机号或密码不正确。";
        }
        if (message.contains("phone is already registered")) {
            return "该手机号已注册，请直接登录。";
        }
        if (message.contains("valid phone is required")) {
            return "请输入正确的手机号。";
        }
        if (message.contains("valid password is required") || message.contains("password must be")) {
            return "密码需要 8–64 位。";
        }
        if (message.contains("invalid or expired reset code")) {
            return "验证码不正确或已过期，请重新获取。";
        }
        if (message.contains("sms service unavailable")) {
            return "短信服务暂时不可用，请稍后再试。";
        }
        if (message.contains("too many reset attempts")) {
            return "尝试次数较多，请稍后再试。";
        }
        if (message.contains("invalid or expired invite code")) {
            return "绑定码不正确或已过期，请让长辈重新生成。";
        }
        if (message.contains("family member limit reached")) {
            return "已达到 5 位家属上限。";
        }
        if (message.contains("assist session is active")) {
            return "当前正在协助，请结束后再操作。";
        }
        if (message.contains("no family member is bound")) {
            return "还没有家属完成绑定。";
        }
        if (message.contains("another family member is assisting")) {
            return "已有其他家属正在协助。";
        }
        if (message.contains("binding was not approved") || message.contains("pending request not found")) {
            return "绑定申请已失效，请重新输入绑定码。";
        }
        if (message.contains("not bound")) {
            return "亲属绑定已失效，请重新绑定。";
        }
        if (message.contains("not logged in") || message.contains("http 401") || message.contains("http 403")) {
            return "账号状态已失效，请重新登录。";
        }
        if (message.contains("http 429") || message.contains("too many")) {
            return "操作太频繁，请稍后再试。";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "网络连接较慢，请重试。";
        }
        if (message.contains("unable to resolve") || message.contains("failed to connect")
                || message.contains("connection refused") || message.contains("network is unreachable")) {
            return "暂时无法连接服务，请检查网络后重试。";
        }
        if (message.contains("http 404") || message.contains("http 5")) {
            return "服务暂时不可用，请稍后重试。";
        }
        return "操作没有完成，请检查网络后重试。";
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
        editor.apply();
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String serviceName = getPackageName() + "/" + SensitiveAccessibilityService.class.getName();
        return enabled.toLowerCase(Locale.ROOT).contains(serviceName.toLowerCase(Locale.ROOT));
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
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
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
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
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
        if (value != null && value.startsWith("http://47.238.240.30")) {
            value = DEFAULT_RELAY_URL;
        }
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
                || normalized.startsWith("http://")
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
        return true;
    }

    private String elderPrimaryButtonText() {
        if (needsOverlayPermission()) {
            return "完成首次设置";
        }
        return "请家人帮忙";
    }

    private String elderCurrentStepTitle() {
        if (needsOverlayPermission()) {
            return "首次使用需要设置";
        }
        return "准备好了";
    }

    private String elderAssistHintText() {
        if (prefs.getBoolean("assistActive", false)) {
            return "现在可以打开需要帮助的应用。结束时回到这里点“结束本次求助”。";
        }
        if (needsOverlayPermission()) {
            return "只需设置一次。按提示允许画圈显示，然后返回这里。";
        }
        if (!isAccessibilityServiceEnabled()) {
            return "点上面的按钮，再在系统提示中点“立即开始”。";
        }
        return "点上面的按钮并确认屏幕共享，家人就能看到画面。";
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
            boolean error = isImportantStatus(text);
            boolean positive = !error && isPositiveStatus(text);
            int textColor = error ? 0xFF9F1D24 : positive ? COLOR_SUCCESS : 0xFF294F9B;
            int backgroundColor = error ? 0xFFFFF2F2 : positive ? 0xFFF0F8F4 : 0xFFF1F5FF;
            int borderColor = error ? 0xFFF3B8BC : positive ? 0xFFB9DDCE : 0xFFC9D7F7;
            status.setTextColor(textColor);
            status.setBackground(rounded(backgroundColor, dp(8), borderColor));
            status.setVisibility(empty || quietFamilyUpdate ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isPositiveStatus(String text) {
        if (text == null) return false;
        return text.contains("成功")
                || text.contains("已开启")
                || text.contains("已允许")
                || text.contains("已绑定")
                || text.contains("已保存")
                || text.contains("连接正常");
    }

    private boolean isImportantStatus(String text) {
        if (text == null) return false;
        return text.contains("失败")
                || text.contains("不正确")
                || text.contains("已过期")
                || text.contains("已失效")
                || text.contains("太频繁")
                || text.contains("没有发送成功")
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

    private void setButtonDisabled(Button button, String text) {
        if (button == null) return;
        button.setTag(null);
        button.setText(text);
        button.setEnabled(false);
        button.setAlpha(0.58f);
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
        if ("elder".equals(selectedAppRole)) {
            addNavItem(nav, R.drawable.ic_nav_home, "求助", "elder".equals(current), v -> showElder());
            addNavItem(nav, R.drawable.ic_nav_family, "家人", "family".equals(current), v -> showElderFamilyMembers());
        } else if ("family".equals(selectedAppRole)) {
            addNavItem(nav, R.drawable.ic_nav_home, "协助", "family".equals(current), v -> showFamily());
            addNavItem(nav, R.drawable.ic_nav_elder, "家人", "relatives".equals(current), v -> showRelativesManagement());
        }
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
        int selectedColor = "家属".equals(text) ? COLOR_FAMILY : COLOR_BLUE_DARK;
        iconView.setColorFilter(selected ? selectedColor : COLOR_MUTED);
        iconView.setContentDescription(text);

        TextView labelView = new TextView(this);
        labelView.setText(text);
        labelView.setTextSize(12);
        labelView.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextColor(selected ? selectedColor : COLOR_MUTED);
        labelView.setIncludeFontPadding(false);
        labelView.setPadding(0, dp(3), 0, dp(3));

        View indicator = new View(this);
        indicator.setBackgroundColor(selected ? selectedColor : 0x00FFFFFF);
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
        accent.setBackgroundColor(COLOR_BRAND);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(36), dp(3));
        accentParams.setMargins(0, dp(8), 0, 0);
        layout.addView(accent, accentParams);
        return layout;
    }

    private LinearLayout appBrandHeader(String message) {
        LinearLayout layout = horizontalRow();
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(4), dp(8), dp(4), dp(14));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("亲情帮帮");
        layout.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = sectionTitle("亲情帮帮");
        name.setTextSize(25);
        TextView messageView = caption(message);
        messageView.setPadding(0, 0, 0, 0);
        copy.addView(name);
        copy.addView(messageView);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(dp(12), 0, 0, 0);
        layout.addView(copy, copyParams);
        return layout;
    }

    private LinearLayout actionCard(String heading, String message, int tint) {
        LinearLayout layout = card(heading, message);
        layout.setBackground(rounded(tint, dp(8), Color.TRANSPARENT));
        return layout;
    }

    private TextView settingsRow(String heading, String message) {
        TextView row = new TextView(this);
        String value = heading + "\n" + message;
        SpannableString styled = new SpannableString(value);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, heading.length(), 0);
        int detailStart = heading.length() + 1;
        styled.setSpan(new ForegroundColorSpan(COLOR_MUTED), detailStart, value.length(), 0);
        styled.setSpan(new RelativeSizeSpan(0.86f), detailStart, value.length(), 0);
        row.setText(styled);
        row.setTextColor(COLOR_TEXT);
        row.setTextSize(16);
        row.setLineSpacing(dp(3), 1f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_forward, 0);
        row.setCompoundDrawablePadding(dp(8));
        row.setPadding(dp(2), dp(12), dp(2), dp(12));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setClickable(true);
        row.setFocusable(true);
        row.setLayoutParams(fullWidthParams());
        return row;
    }

    private LinearLayout profileSummary() {
        LinearLayout summary = horizontalRow();
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(14), dp(14), dp(14), dp(14));
        summary.setBackground(rounded(COLOR_SURFACE, dp(8), COLOR_LINE));
        LinearLayout.LayoutParams params = fullWidthParams();
        params.setMargins(0, 0, 0, dp(10));
        summary.setLayoutParams(params);

        TextView avatar = new TextView(this);
        String initial = displayName == null || displayName.isEmpty() ? "亲" : displayName.substring(0, 1);
        avatar.setText(initial);
        avatar.setTextSize(22);
        avatar.setTextColor(Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(COLOR_BRAND, dp(8), COLOR_BRAND));
        summary.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = sectionTitle(displayName);
        TextView phone = caption(maskedPhone(accountPhone));
        phone.setPadding(0, 0, 0, 0);
        copy.addView(name);
        copy.addView(phone);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(dp(12), 0, 0, 0);
        summary.addView(copy, copyParams);
        return summary;
    }

    private String maskedPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone == null ? "" : phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
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

        if (heading != null && !heading.trim().isEmpty()) {
            TextView title = sectionTitle(heading);
            layout.addView(title);
        }
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
        view.setTextSize(15);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView notice(String text) {
        TextView view = body(text);
        view.setTextSize(15);
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
        button.setBackground(rounded(0xFFFFF4F7, dp(8), 0xFFE7C7D0));
        return button;
    }

    private Button familyPrimaryButton(String text) {
        Button button = primaryButton(text);
        button.setBackground(rounded(COLOR_FAMILY, dp(8), COLOR_FAMILY));
        return button;
    }

    private Button familySecondaryButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(COLOR_FAMILY);
        button.setBackground(rounded(0xFFF5F3FF, dp(8), 0xFFD7D1F5));
        return button;
    }

    private Button controlPrimaryButton(String text) {
        Button button = primaryButton(text);
        button.setBackground(rounded(COLOR_CONTROL, dp(8), COLOR_CONTROL));
        return button;
    }

    private Button textButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(COLOR_BLUE_DARK);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(dp(48));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
