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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * Arka planda çalışan, ön plandaki uygulamayı tespit edip kilitli ise
 * ekran üstüne kilit ekranı (PIN, Desen veya sahte çökme) açan
 * Foreground Service. Tüm gelişmiş güvenlik özelliklerinin orkestrasyonu
 * burada yapılır:
 *
 *  - IntruderCapture: art arda yanlış giriş yapıldığında ön kameradan fotoğraf.
 *  - Re-lock Timeout: anlık / ekran-kapalı / 1-dk gecikmeli yeniden kilitlenme.
 *  - FakeCrashDialog: kilit ekranını sahte "uygulama durduruldu" hatasının arkasına gizler.
 *  - PatternLockView + Stealth Mode: PIN yerine görünmez desen ile kilit açma.
 *  - ShakeDetector: telefon sallandığında tüm korumalı uygulamaları anında yeniden kilitler.
 *  - WifiSmartLockHelper: güvenilir Wi-Fi ağındayken kilitlemeyi devre dışı bırakır.
 *  - Panic PIN: girilirse gerçek uygulamayı asla açmadan kullanıcıyı ana ekrana yönlendirir.
 */
public class LockService extends Service {

    private static final String CHANNEL_ID = "arslan_guard_channel";
    private static final int NOTIFICATION_ID = 101;
    private static final long CHECK_INTERVAL_MS = 800;

    private Handler handler;
    private Runnable checkRunnable;
    private UsageStatsManager usageStatsManager;
    private WindowManager windowManager;
    private IntruderCapture intruderCapture;
    private ShakeDetector shakeDetector;
    private boolean shakeRegistered = false;

    private View overlayView;
    private boolean overlayShowing = false;
    private FakeCrashDialog activeFakeCrashDialog; // bekleyen hold-runnable'ı iptal edebilmek için

    private String lastForegroundPackage = "";

    // Paket adı -> son başarılı doğrulama zamanı (re-lock timeout hesaplamak için)
    private final Map<String, Long> lastUnlockedAt = new HashMap<>();

    // Kilit ekranı açıkken yapılan yanlış giriş sayacı (oturuma özel)
    private int wrongAttemptCount = 0;

    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        intruderCapture = new IntruderCapture(this);
        shakeDetector = new ShakeDetector(this);
        shakeDetector.setOnShakeListener(new ShakeDetector.OnShakeListener() {
            @Override
            public void onShake() {
                onShakeToLockTriggered();
            }
        });

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenOffReceiver();

        checkRunnable = new Runnable() {
            @Override
            public void run() {
                updateShakeDetectorState();
                checkForegroundApp();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.post(checkRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // sistem tarafından kapatılırsa tekrar başlatılsın
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
        if (shakeDetector != null) {
            shakeDetector.stop();
        }
        removeOverlayIfShowing();
    }

    // ------------------------------------------------------------------
    // "Ekran Kapanana Kadar Kilitleme" modu için: ekran kapandığında tüm
    // geçici açık kilitleri sıfırlıyoruz ki ekran tekrar açıldığında sorulsun
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
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    // ------------------------------------------------------------------
    // Sallama ile Hızlı Kilitleme (Shake to Lock)
    // ------------------------------------------------------------------
    private void updateShakeDetectorState() {
        boolean shouldEnable = PrefsHelper.isShakeToLockEnabled(this) && shakeDetector.isAvailable();
        if (shouldEnable && !shakeRegistered) {
            shakeDetector.setSensitivity(PrefsHelper.getShakeSensitivity(this));
            shakeDetector.start();
            shakeRegistered = true;
        } else if (!shouldEnable && shakeRegistered) {
            shakeDetector.stop();
            shakeRegistered = false;
        }
    }

    private void onShakeToLockTriggered() {
        // Tüm paketlerin "geçici açık" durumunu sıfırla; bir sonraki kontrolde
        // (veya hemen şimdi, geçerli uygulama kilitliyse) kilit ekranı gösterilsin
        lastUnlockedAt.clear();
        checkForegroundApp();
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

            boolean isLocked = PrefsHelper.isLocked(this, currentPackage);
            boolean trustedWifi = WifiSmartLockHelper.isConnectedToTrustedNetwork(this);

            if (isLocked && !trustedWifi && !isStillUnlocked(currentPackage)) {
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
                return true; // ScreenOffReceiver tetiklenene kadar geçerli kalır
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
            showRealLockOverlay(packageName);
        }
    }

    /**
     * Sahte "Uygulama durduruldu" hatası. Kısa dokunuş overlay'i kapatıp
     * kullanıcıyı ana ekrana gönderir (gerçek bir çökme gibi davranır).
     * "TAMAM" butonuna 3 saniye basılı tutmak ise gerçek kilit ekranını açar.
     */
    private void showFakeCrashOverlay(final String packageName) {
        String appLabel = resolveAppLabel(packageName);

        activeFakeCrashDialog = new FakeCrashDialog(this);
        overlayView = activeFakeCrashDialog.build(appLabel, new FakeCrashDialog.Callback() {
            @Override
            public void onDismissed() {
                removeOverlayIfShowing();
                goToHomeScreen();
            }

            @Override
            public void onRevealed() {
                removeOverlayIfShowing();
                showRealLockOverlay(packageName);
            }
        });

        addOverlayToWindow();
    }

    /**
     * Gerçek kilit ekranı: kullanıcının tercihine göre PIN veya Desen paneli.
     * Panik şifre/desen girilirse gerçek uygulama asla açılmaz.
     */
    private void showRealLockOverlay(final String packageName) {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.lock_overlay, null);

        ImageView imgIcon = overlayView.findViewById(R.id.imgLockedAppIcon);
        TextView txtAppName = overlayView.findViewById(R.id.txtLockedAppName);
        TextView txtInstruction = overlayView.findViewById(R.id.txtInstruction);
        final TextView txtError = overlayView.findViewById(R.id.txtError);
        ImageView imgBackground = overlayView.findViewById(R.id.imgBackground);
        LinearLayout pinGroup = overlayView.findViewById(R.id.pinGroup);
        final EditText editPin = overlayView.findViewById(R.id.editPin);
        View btnUnlock = overlayView.findViewById(R.id.btnUnlock);
        final PatternLockView patternLockView = overlayView.findViewById(R.id.patternLockView);

        applyBackgroundAppearance(imgBackground);

        try {
            PackageManager pm = getPackageManager();
            Drawable icon = pm.getApplicationIcon(packageName);
            imgIcon.setImageDrawable(icon);
            txtAppName.setText(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
        } catch (Exception ignored) {
        }

        boolean patternMode = PrefsHelper.isPatternMode(this);

        if (patternMode) {
            pinGroup.setVisibility(View.GONE);
            patternLockView.setVisibility(View.VISIBLE);
            txtInstruction.setText(R.string.enter_pattern);
            patternLockView.setStealthMode(PrefsHelper.isStealthPatternEnabled(this));
            patternLockView.setPatternListener(new PatternLockView.PatternListener() {
                @Override
                public void onPatternComplete(String patternCode) {
                    handleCodeEntered(packageName, patternCode, txtError, null, patternLockView);
                }
            });
        } else {
            pinGroup.setVisibility(View.VISIBLE);
            patternLockView.setVisibility(View.GONE);
            txtInstruction.setText(R.string.enter_pin);
            btnUnlock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String entered = editPin.getText().toString();
                    handleCodeEntered(packageName, entered, txtError, editPin, null);
                }
            });
        }

        addOverlayToWindow();
    }

    /**
     * Girilen PIN ya da Desen kodunu; sırasıyla Panik kodu, ardından gerçek
     * kod ile karşılaştırır. Ortak mantığı hem PIN hem Desen akışı kullanır.
     */
    private void handleCodeEntered(String packageName, String enteredCode,
                                    TextView txtError,
                                    @Nullable EditText editPin,
                                    @Nullable PatternLockView patternLockView) {

        // 1) Panik kodu kontrolü — eşleşirse gerçek uygulama ASLA açılmaz
        if (PrefsHelper.isPanicCode(this, enteredCode)) {
            removeOverlayIfShowing();
            goToHomeScreen();
            return;
        }

        // 2) Gerçek kod kontrolü
        String correctCode = PrefsHelper.isPatternMode(this)
                ? PrefsHelper.getPattern(this)
                : PrefsHelper.getPin(this);

        if (!TextUtils.isEmpty(enteredCode) && enteredCode.equals(correctCode)) {
            lastUnlockedAt.put(packageName, System.currentTimeMillis());
            wrongAttemptCount = 0;
            removeOverlayIfShowing();
        } else {
            wrongAttemptCount++;
            txtError.setVisibility(View.VISIBLE);
            if (editPin != null) editPin.setText("");
            if (patternLockView != null) patternLockView.reset();

            if (PrefsHelper.isIntruderSelfieEnabled(this)
                    && wrongAttemptCount >= PrefsHelper.getWrongAttemptsThreshold()) {
                intruderCapture.captureIntruderPhoto();
                wrongAttemptCount = 0; // sayaç sıfırlanır, sonraki eşit sayıda denemede tekrar tetiklenebilir
            }
        }
    }

    private void goToHomeScreen() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }

    private String resolveAppLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
        } catch (Exception e) {
            return "Uygulama";
        }
    }

    /**
     * Hazırlanmış overlayView'ı (kilit paneli ya da sahte çökme ekranı) sistem
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
                imgBackground.setImageDrawable(null);
                imgBackground.setBackground(new ColorDrawable(0xFF121212));
            }
        } else {
            imgBackground.setImageDrawable(null);
            imgBackground.setBackground(new ColorDrawable(0xFF121212));
        }

        imgBackground.setAlpha(alpha);
    }

    @Nullable
    private Bitmap decodeSampledBitmap(String filePath) {
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, boundsOptions);

            int targetSize = 1080;
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
        if (activeFakeCrashDialog != null) {
            activeFakeCrashDialog.cancelPendingReveal();
            activeFakeCrashDialog = null;
        }
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
