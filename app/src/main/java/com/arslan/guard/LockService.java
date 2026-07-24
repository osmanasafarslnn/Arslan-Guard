package com.arslan.guard;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.Set;

/**
 * Arka planda çalışan, ön plandaki uygulamayı tespit edip
 * kilitli ise ekran üstüne PIN paneli açan Foreground Service.
 */
public class LockService extends Service {

    private static final String CHANNEL_ID = "arslan_guard_channel";
    private static final int NOTIFICATION_ID = 101;
    private static final long CHECK_INTERVAL_MS = 800;

    private Handler handler;
    private Runnable checkRunnable;
    private UsageStatsManager usageStatsManager;
    private WindowManager windowManager;

    private View overlayView;
    private boolean overlayShowing = false;

    private String lastForegroundPackage = "";
    // Bu oturumda kullanıcı doğru PIN girdiği için kilidi geçici olarak açılmış paketler
    private final Set<String> unlockedThisSession = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        checkRunnable = new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.post(checkRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Servis sistem tarafından kapatılırsa tekrar başlatılsın
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
        removeOverlayIfShowing();
    }

    // ------------------------------------------------------------------
    // Ön planda çalışan uygulamayı UsageEvents üzerinden tespit ediyoruz
    // ------------------------------------------------------------------
    private void checkForegroundApp() {
        if (usageStatsManager == null) return;

        long endTime = System.currentTimeMillis();
        long startTime = endTime - 10_000; // son 10 saniyelik olay penceresi

        UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
        String currentPackage = null;

        UsageEvents.Event event = new UsageEvents.Event();
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentPackage = event.getPackageName();
            }
        }

        if (currentPackage == null || currentPackage.equals(getPackageName())) {
            return; // kendi uygulamamız veya tespit edilemedi
        }

        if (!currentPackage.equals(lastForegroundPackage)) {
            // Uygulama değişti; önceki uygulamanın "geçici açık" durumunu temizle
            unlockedThisSession.remove(lastForegroundPackage);
            lastForegroundPackage = currentPackage;

            if (PrefsHelper.isLocked(this, currentPackage)
                    && !unlockedThisSession.contains(currentPackage)) {
                showLockOverlay(currentPackage);
            } else {
                removeOverlayIfShowing();
            }
        }
    }

    // ------------------------------------------------------------------
    // Overlay (kilit ekranı) gösterimi
    // ------------------------------------------------------------------
    private void showLockOverlay(final String packageName) {
        if (overlayShowing) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.lock_overlay, null);

        ImageView imgIcon = overlayView.findViewById(R.id.imgLockedAppIcon);
        TextView txtAppName = overlayView.findViewById(R.id.txtLockedAppName);
        final EditText editPin = overlayView.findViewById(R.id.editPin);
        final TextView txtError = overlayView.findViewById(R.id.txtError);
        View btnUnlock = overlayView.findViewById(R.id.btnUnlock);
        ImageView imgBackground = overlayView.findViewById(R.id.imgBackground);

        applyBackgroundAppearance(imgBackground);

        try {
            PackageManager pm = getPackageManager();
            Drawable icon = pm.getApplicationIcon(packageName);
            imgIcon.setImageDrawable(icon);
            txtAppName.setText(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
        } catch (Exception ignored) {
        }

        View.OnClickListener unlockAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String entered = editPin.getText().toString();
                String correctPin = PrefsHelper.getPin(LockService.this);

                if (!TextUtils.isEmpty(entered) && entered.equals(correctPin)) {
                    unlockedThisSession.add(packageName);
                    removeOverlayIfShowing();
                } else {
                    txtError.setVisibility(View.VISIBLE);
                    editPin.setText("");
                }
            }
        };
        btnUnlock.setOnClickListener(unlockAction);

        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                0, // odaklanabilir olmalı ki PIN girişi (klavye) çalışsın
                PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.CENTER;

        try {
            windowManager.addView(overlayView, params);
            overlayShowing = true;
        } catch (Exception e) {
            overlayShowing = false;
        }
    }

    /**
     * Kullanıcının tercihine göre kilit ekranı arka planını (özel resim ya da
     * varsayılan koyu renk) ve şeffaflık (alpha) seviyesini uygular.
     */
    private void applyBackgroundAppearance(ImageView imgBackground) {
        float alpha = PrefsHelper.getOverlayAlpha(this) / 100f; // 0.0 - 1.0

        if (PrefsHelper.hasCustomBackground(this)) {
            Bitmap bitmap = decodeSampledBitmap(PrefsHelper.getBackgroundFile(this).getAbsolutePath());
            if (bitmap != null) {
                imgBackground.setBackground(null);
                imgBackground.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
            } else {
                // Dosya okunamazsa varsayılana düş
                imgBackground.setImageDrawable(null);
                imgBackground.setBackground(new ColorDrawable(0xFF121212));
            }
        } else {
            imgBackground.setImageDrawable(null);
            imgBackground.setBackground(new ColorDrawable(0xFF121212));
        }

        imgBackground.setAlpha(alpha);
    }

    /**
     * Overlay penceresi için gereksiz yere büyük bitmap yüklenip bellek
     * taşmasına (OutOfMemory) yol açmaması amacıyla örnekleme (downsampling)
     * yaparak resmi çözer.
     */
    @Nullable
    private Bitmap decodeSampledBitmap(String filePath) {
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, boundsOptions);

            int targetSize = 1080; // ekran genişliği için makul üst sınır
            int sampleSize = 1;
            while ((boundsOptions.outWidth / sampleSize) > targetSize
                    || (boundsOptions.outHeight / sampleSize) > targetSize) {
                sampleSize *= 2;
            }

            BitmapFactory.Options loadOptions = new BitmapFactory.Options();
            loadOptions.inSampleSize = sampleSize;
            return BitmapFactory.decodeFile(filePath, loadOptions);
        } catch (Exception e) {
            return null;
        }
    }

    private void removeOverlayIfShowing() {
        if (overlayShowing && overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayShowing = false;
            overlayView = null;
        }
    }

    // ------------------------------------------------------------------
    // Bildirim (Foreground Service için zorunlu)
    // ------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private android.app.Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, contentIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE
                        : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
