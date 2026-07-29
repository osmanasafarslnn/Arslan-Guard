package com.example.applock

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * SYSTEM_ALERT_WINDOW ve PACKAGE_USAGE_STATS gibi "özel" izinlerin
 * kontrolü için yardımcı fonksiyonlar. Bu izinler normal
 * <uses-permission> ile otomatik verilmez; kullanıcı Ayarlar'dan
 * elle onaylamalıdır.
 */
object PermissionUtils {

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // API 21-22 arasında bu izin otomatik verilir
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasOverlayPermission(context) && hasUsageStatsPermission(context)
    }
}
