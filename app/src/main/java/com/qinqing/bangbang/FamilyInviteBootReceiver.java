package com.qinqing.bangbang;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class FamilyInviteBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("family-assist", Context.MODE_PRIVATE);
        if (!"family".equals(prefs.getString("selectedAppRole", ""))
                || !"family".equals(prefs.getString("memberRole", ""))
                || prefs.getString("authToken", "").isEmpty()) {
            return;
        }
        Intent service = new Intent(context, FamilyInviteMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
