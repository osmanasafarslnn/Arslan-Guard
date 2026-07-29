package com.example.applock

import android.app.Application

class AppLockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // İleride global başlatma (örn. crash-safe loglama) buraya eklenebilir.
    }
}
