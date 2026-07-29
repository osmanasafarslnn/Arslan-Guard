package com.example.applock

import android.graphics.drawable.Drawable

/**
 * Uygulama listesinde gösterilen tek bir yüklü uygulamayı temsil eder.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var isLocked: Boolean
)
