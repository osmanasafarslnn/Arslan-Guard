package com.example.applock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Ön planda çalışan (foreground) servis. UsageStatsManager üzerinden
 * hangi uygulamanın ekranda olduğunu periyodik olarak kontrol eder.
 * Kilitli bir uygulama tespit edildiğinde ve bu oturumda henüz
 * kilidi açılmadıysa LockScreenActivity'yi tam ekran olarak açar.
 *
 * Not: Bu yaklaşım pil dostu olması için 800ms aralıklarla,
 * ağır olan queryUsageStats yerine hafif queryEvents API'sini kullanır.
 */
class AppLockService : Service() {

    private lateinit var prefs: PrefsHelper
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckedTime = System.currentTimeMillis()
    private var lastForegroundPackage: String? = null
    private var activeUnlockedPackage: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsHelper(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sistem tarafından öldürülürse, kaynaklar müsait olduğunda yeniden başlat
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForegroundApp() {
        if (!PermissionUtils.hasUsageStatsPermission(this)) return

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastCheckedTime, now)
        var latestPackage: String? = null

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val movedToForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (movedToForeground) {
                latestPackage = event.packageName
            }
        }
        lastCheckedTime = now

        if (latestPackage != null && latestPackage != lastForegroundPackage) {
            lastForegroundPackage = latestPackage
            onForegroundAppChanged(latestPackage)
        }
    }

    private fun onForegroundAppChanged(packageName: String) {
        // Kendi kilit ekranımız veya kendi uygulamamız tetiklenmesin
        if (packageName == applicationContext.packageName) {
            return
        }

        // Kullanıcı daha önce kilidini açtığı bir uygulamadan farklı bir
        // uygulamaya geçtiyse, o uygulamanın kilidini bir sonraki girişte
        // tekrar iste (gerçek App Lock davranışı).
        activeUnlockedPackage?.let { previouslyActive ->
            if (previouslyActive != packageName) {
                relock(previouslyActive)
                activeUnlockedPackage = null
            }
        }

        if (prefs.isAppLocked(packageName)) {
            if (unlockedThisSession.contains(packageName)) {
                activeUnlockedPackage = packageName
            } else {
                launchLockScreen(packageName)
            }
        }
    }

    private fun launchLockScreen(packageName: String) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LockScreenActivity.EXTRA_PACKAGE_NAME, packageName)
        }
        startActivity(intent)
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_title),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.service_notification_text)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "app_lock_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 800L

        // Bu oturumda kilidi açılmış paketler; servis çalıştığı sürece bellekte tutulur.
        // Uygulama tamamen kapanıp arka plana atıldığında (belirli bir süre sonra)
        // tekrar kilitlenmesi istenirse, burada zaman damgası bazlı bir mekanizma eklenebilir.
        private val unlockedThisSession = mutableSetOf<String>()

        fun markUnlocked(packageName: String) {
            unlockedThisSession.add(packageName)
        }

        /** Bir uygulama arka plana alınıp ana ekrana dönüldüğünde tekrar kilitlenmesi için çağrılabilir. */
        fun relock(packageName: String) {
            unlockedThisSession.remove(packageName)
        }

        fun start(context: Context) {
            val intent = Intent(context, AppLockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
