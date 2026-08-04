package com.qinqing.bangbang;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class SensitiveAccessibilityService extends AccessibilityService {
    private static final String PREFS = "family-assist";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        boolean sensitive = isSensitivePackage(event) || containsSensitiveText(getRootInActiveWindow(), 0);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean("autoPrivacyMask", sensitive).apply();
    }

    @Override
    public void onInterrupt() {
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
