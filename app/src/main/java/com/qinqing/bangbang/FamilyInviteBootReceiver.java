package com.qinqing.bangbang;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class FamilyInviteBootReceiver extends BroadcastReceiver {
    private static final String TAG = "FamilyInviteBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("family-assist", Context.MODE_PRIVATE);
        String selectedRole = prefs.getString("selectedAppRole", "");
        String memberRole = prefs.getString("memberRole", "");
        if (!("family".equals(selectedRole) || "elder".equals(selectedRole))
                || !selectedRole.equals(memberRole)
                || prefs.getString("authToken", "").isEmpty()) {
            return;
        }
        Intent service = new Intent(context, FamilyInviteMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to restore family event monitor after boot", error);
        }
    }
}
