package com.example.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsHelper(context)
            // Sadece PIN kurulmuşsa ve gerekli izinler verilmişse servisi başlat
            if (prefs.isPinSet() && PermissionUtils.hasAllRequiredPermissions(context)) {
                AppLockService.start(context)
            }
        }
    }
}
