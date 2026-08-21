package com.qinqing.bangbang;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.IOException;

final class InstallStateGuard {
    private static final String PREFS = "family-assist";
    private static final String KEY_FIRST_INSTALL_TIME = "localFirstInstallTime";
    private static final String SENTINEL_NAME = "install-state-v1";
    private static final long FRESH_INSTALL_WINDOW_MS = 5_000L;

    private InstallStateGuard() {
    }

    static void reconcile(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long currentFirstInstallTime = info.firstInstallTime;
            long previousFirstInstallTime = prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L);
            boolean hasLocalSession = !prefs.getString("accountToken", "").isEmpty();
            boolean looksLikeFreshInstall = Math.abs(info.lastUpdateTime - info.firstInstallTime)
                    <= FRESH_INSTALL_WINDOW_MS;
            File sentinel = new File(context.getNoBackupFilesDir(), SENTINEL_NAME);

            boolean installTimeChanged = previousFirstInstallTime > 0L
                    && previousFirstInstallTime != currentFirstInstallTime;
            boolean legacySessionRestoredIntoFreshInstall = previousFirstInstallTime == 0L
                    && hasLocalSession
                    && looksLikeFreshInstall
                    && !sentinel.exists();
            boolean currentSessionRestoredWithoutSentinel = previousFirstInstallTime > 0L
                    && hasLocalSession
                    && !sentinel.exists()
                    && looksLikeFreshInstall;

            if (installTimeChanged || legacySessionRestoredIntoFreshInstall
                    || currentSessionRestoredWithoutSentinel) {
                prefs.edit().clear().commit();
            }

            prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, currentFirstInstallTime).commit();
            ensureSentinel(sentinel);
        } catch (Exception ignored) {
            // A package-manager or storage failure must not prevent app startup.
        }
    }

    private static void ensureSentinel(File sentinel) throws IOException {
        File parent = sentinel.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create no-backup directory");
        }
        if (!sentinel.exists() && !sentinel.createNewFile()) {
            throw new IOException("Unable to create install sentinel");
        }
    }
}
