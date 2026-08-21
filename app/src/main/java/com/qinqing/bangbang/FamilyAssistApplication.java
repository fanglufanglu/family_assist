package com.qinqing.bangbang;

import android.app.Application;

public class FamilyAssistApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        InstallStateGuard.reconcile(this);
        CrashReporter.install(this);
    }
}
