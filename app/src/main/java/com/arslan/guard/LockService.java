package com.arslan.guard;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Arka planda çalışan, ön plandaki uygulamayı tespit edip kilitli ise
 * ekran üstüne PIN paneli (veya sahte çökme ekranı) açan Foreground Service.
 *
 * Ayrıca şu gelişmiş güvenlik özelliklerini yönetir:
 *  - Intruder Selfie: art arda yanlış PIN girildiğinde ön kameradan fotoğraf çeker.
 *  - Re-lock Timeout: kullanıcı deneyimini iyileştirmek için anlık/ekran-kapalı/1-dk gecikmeli kilitlenme.
 *  - Fake Crash Cover: kilit ekranını sahte bir "uygulama durduruldu" hatasının arkasına gizler.
 */
public class LockService extends Service {

    private static final String CHANNEL_ID = "arslan_guard_channel";
    private static final int NOTIFICATION_ID = 101;
    private static final long CHECK_INTERVAL_MS = 800;

    private Handler handler;
    private Runnable checkRunnable;
    private UsageStatsManager usageStatsManager;
    private WindowManager windowManager;
    private CameraHelper cameraHelper;

    private View overlayView;
    private boolean overlayShowing = false;

    private String lastForegroundPackage = "";

    // Paket adı -> son başarılı PIN doğrulama zamanı (re-lock timeout hesaplamak için)
    private final Map<String, Long> lastUnlockedAt = new HashMap<>();

    // Kilit ekranı açıkken yapılan yanlış PIN denemesi sayacı (oturuma özel)
    private int wrongAttemptCount = 0;

    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        cameraHelper = new CameraHelper(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenOffReceiver();

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
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
        }
        removeOverlayIfShowing();
    }

    // ------------------------------------------------------------------
    // "Ekran Kapanana Kadar Kilitleme" modu için: ekran kapandığında tüm
    // geçici açık kilitleri sıfırlıyoruz ki ekran tekrar açıldığında PIN sorulsun
    // ------------------------------------------------------------------
    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    lastUnlockedAt.clear();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOffReceiver, filter);
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
            lastForegroundPackage = currentPackage;

            if (PrefsHelper.isLocked(this, currentPackage) && !isStillUnlocked(currentPackage)) {
                showLockOverlay(currentPackage);
            } else {
                removeOverlayIfShowing();
            }
        }
    }

    /**
     * Re-lock Timeout ayarına göre, bu paketin hâlâ "kilitsiz" sayılıp
     * sayılmayacağını hesaplar.
     */
    private boolean isStillUnlocked(String packageName) {
        Long unlockedAt = lastUnlockedAt.get(packageName);
        if (unlockedAt == null) return false;

        int mode = PrefsHelper.getRelockMode(this);
        switch (mode) {
            case PrefsHelper.RELOCK_UNTIL_SCREEN_OFF:
                // ScreenOffReceiver tetiklenene kadar geçerli kalır
                return true;
            case PrefsHelper.RELOCK_DELAY_1_MIN:
                return (System.currentTimeMillis() - unlockedAt) < PrefsHelper.getRelockDelayMs();
            case PrefsHelper.RELOCK_INSTANT:
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Overlay (kilit ekranı) gösterimi — sahte çökme ekranı açık ise önce o gösterilir
    // ------------------------------------------------------------------
    private void showLockOverlay(final String packageName) {
        if (overlayShowing) return;

        wrongAttemptCount = 0;

        if (PrefsHelper.isFakeCrashEnabled(this)) {
            showFakeCrashOverlay(packageName);
        } else {
            showRealPinOverlay(packageName);
        }
    }

    /**
     * Sahte "Uygulama durduruldu" hatası. Kısa dokunuş overlay'i kapatıp
     * kullanıcıyı ana ekrana gönderir (gerçek bir çökme gibi davranır).
     * Uzun basış ise gerçek PIN ekranını açar (uygulama sahibi içindir).
     */
    private void showFakeCrashOverlay(final String packageName) {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.fake_crash_overlay, null);

        TextView txtCrashAppName = overlayView.findViewById(R.id.txtCrashAppName);
        View btnOk = overlayView.findViewById(R.id.btnFakeCrashOk);

        try {
            PackageManager pm = getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            txtCrashAppName.setText(label + " durduruldu");
        } catch (Exception ignored) {
        }

        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Normal (kısa) dokunuş: sahte çökmeyi tamamla, kullanıcıyı ana ekrana yönlendir
                removeOverlayIfShowing();
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
            }
        });

        btnOk.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // Uzun basış: gizli kapı - gerçek PIN ekranını göster
                removeOverlayIfShowing();
                showRealPinOverlay(packageName);
                return true;
            }
        });

        addOverlayToWindow();
    }

    /**
     * Gerçek PIN giriş paneli. Art arda 3 yanlış denemede intruder selfie tetiklenir.
     */
    private void showRealPinOverlay(final String packageName) {
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
                    lastUnlockedAt.put(packageName, System.currentTimeMillis());
                    wrongAttemptCount = 0;
                    removeOverlayIfShowing();
                } else {
                    handleWrongPin(txtError, editPin);
                }
            }
        };
        btnUnlock.setOnClickListener(unlockAction);

        addOverlayToWindow();
    }

    /**
     * Yanlış PIN girişini sayar; eşik değere (varsayılan 3) ulaşıldığında
     * ve özellik açıksa ön kameradan sessizce fotoğraf çeker.
     */
    private void handleWrongPin(TextView txtError, EditText editPin) {
        wrongAttemptCount++;
        txtError.setVisibility(View.VISIBLE);
        editPin.setText("");

        if (PrefsHelper.isIntruderSelfieEnabled(this)
                && wrongAttemptCount >= PrefsHelper.getWrongAttemptsThreshold()) {
            cameraHelper.captureIntruderPhoto();
            wrongAttemptCount = 0; // sayaç sıfırlanır, sonraki 3 yanlış denemede tekrar tetiklenebilir
        }
    }

    /**
     * Hazırlanmış overlayView'ı (PIN paneli ya da sahte çökme ekranı) sistem
     * penceresine ekler.
     */
    private void addOverlayToWindow() {
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
