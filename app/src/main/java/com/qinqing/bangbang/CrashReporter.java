package com.qinqing.bangbang;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;

final class CrashReporter {
    private static final String PREFS = "family-assist";
    private static final String PENDING_CRASH = "pendingCrashReport";

    private CrashReporter() {
    }

    static void install(Context context) {
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveCrash(appContext, thread, error);
            uploadPendingAsync(appContext);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        uploadPendingAsync(appContext);
    }

    static void uploadPendingAsync(Context context) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> uploadPending(appContext), "crash-upload").start();
    }

    private static void saveCrash(Context context, Thread thread, Throwable error) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject payload = basePayload(context, prefs)
                    .put("thread", thread == null ? "" : thread.getName())
                    .put("message", error == null ? "" : error.getClass().getName() + ": " + error.getMessage())
                    .put("stack", stackTrace(error));
            prefs.edit().putString(PENDING_CRASH, payload.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void uploadPending(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String pending = prefs.getString(PENDING_CRASH, "");
            if (pending.isEmpty()) {
                return;
            }
            String baseUrl = prefs.getString("baseUrl", "");
            String pairCode = prefs.getString("pairCode", "");
            String authToken = prefs.getString("authToken", "");
            if (baseUrl.isEmpty() || pairCode.isEmpty() || authToken.isEmpty()) {
                return;
            }
            JSONObject payload = new JSONObject(pending)
                    .put("pairCode", pairCode)
                    .put("authToken", authToken);
            NetworkClient.postJson(baseUrl, "/api/crash", payload);
            prefs.edit().remove(PENDING_CRASH).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject basePayload(Context context, SharedPreferences prefs) throws Exception {
        return new JSONObject()
                .put("role", prefs.getString("memberRole", ""))
                .put("device", Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE)
                .put("appVersion", appVersion(context));
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String stackTrace(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
