package com.arslan.guard;

import android.text.TextUtils;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Kilitli uygulama listesini, PIN kodunu ve kilit ekranı görünüm
 * tercihlerini (şeffaflık / özel arka plan) saklayan SharedPreferences
 * sarmalayıcı sınıf.
 */
public class PrefsHelper {

    private static final String PREFS_NAME = "arslan_guard_prefs";
    private static final String KEY_LOCKED_APPS = "locked_apps";
    private static final String KEY_PIN = "user_pin";
    private static final String DEFAULT_PIN = "1234";

    // Kilit ekranı görünüm ayarları
    private static final String KEY_OVERLAY_ALPHA = "overlay_alpha"; // 0-100 (100 = tam opak)
    private static final String KEY_HAS_CUSTOM_BG = "has_custom_background";
    private static final int DEFAULT_ALPHA = 100;
    private static final String CUSTOM_BG_FILENAME = "lock_background.jpg";

    // Yeniden kilitlenme (re-lock) modu
    private static final String KEY_RELOCK_MODE = "relock_mode";
    public static final int RELOCK_INSTANT = 0;      // Anında Kilitle
    public static final int RELOCK_UNTIL_SCREEN_OFF = 1; // Ekran Kapanana Kadar Kilitleme
    public static final int RELOCK_DELAY_1_MIN = 2;  // 1 Dakika Sonra Kilitle
    private static final long RELOCK_DELAY_MS = 60_000L;

    // Sahte çökme ekranı (fake crash / cover)
    private static final String KEY_FAKE_CRASH_ENABLED = "fake_crash_enabled";

    // Yanlış şifrede gizli fotoğraf (intruder selfie)
    private static final String KEY_INTRUDER_SELFIE_ENABLED = "intruder_selfie_enabled";
    private static final String INTRUDER_SELFIE_DIR = "intruder_selfies";
    private static final int WRONG_ATTEMPTS_THRESHOLD = 3;

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> getLockedApps(Context context) {
        SharedPreferences p = prefs(context);
        // getStringSet döndürülen set'i doğrudan mutasyona uğratmamak için kopyalıyoruz
        return new HashSet<>(p.getStringSet(KEY_LOCKED_APPS, new HashSet<String>()));
    }

    public static boolean isLocked(Context context, String packageName) {
        return getLockedApps(context).contains(packageName);
    }

    public static void setLocked(Context context, String packageName, boolean locked) {
        Set<String> current = getLockedApps(context);
        if (locked) {
            current.add(packageName);
        } else {
            current.remove(packageName);
        }
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, current).apply();
    }

    public static String getPin(Context context) {
        return prefs(context).getString(KEY_PIN, DEFAULT_PIN);
    }

    public static void setPin(Context context, String pin) {
        prefs(context).edit().putString(KEY_PIN, pin).apply();
    }

    // ------------------------------------------------------------------
    // Kilit ekranı şeffaflık ayarı (0 = tamamen şeffaf, 100 = tam opak)
    // ------------------------------------------------------------------
    public static int getOverlayAlpha(Context context) {
        return prefs(context).getInt(KEY_OVERLAY_ALPHA, DEFAULT_ALPHA);
    }

    public static void setOverlayAlpha(Context context, int alphaPercent) {
        if (alphaPercent < 0) alphaPercent = 0;
        if (alphaPercent > 100) alphaPercent = 100;
        prefs(context).edit().putInt(KEY_OVERLAY_ALPHA, alphaPercent).apply();
    }

    // ------------------------------------------------------------------
    // Kilit ekranı özel arka plan resmi
    // ------------------------------------------------------------------
    public static boolean hasCustomBackground(Context context) {
        return prefs(context).getBoolean(KEY_HAS_CUSTOM_BG, false)
                && getBackgroundFile(context).exists();
    }

    public static void setHasCustomBackground(Context context, boolean hasCustom) {
        prefs(context).edit().putBoolean(KEY_HAS_CUSTOM_BG, hasCustom).apply();
    }

    /**
     * Kullanıcının seçtiği arka plan resminin uygulama içi (private) depoda
     * her zaman kaydedildiği sabit dosya yolu. Content URI izinlerinin
     * kalıcılığıyla uğraşmamak için resim seçilir seçilmez bu dosyaya
     * kopyalanır.
     */
    public static File getBackgroundFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_BG_FILENAME);
    }

    /**
     * Kullanıcı "Varsayılana Sıfırla" dediğinde özel arka planı kaldırır.
     */
    public static void clearCustomBackground(Context context) {
        File file = getBackgroundFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        setHasCustomBackground(context, false);
    }

    // ------------------------------------------------------------------
    // Yeniden kilitlenme (Re-lock Timeout) ayarı
    // ------------------------------------------------------------------
    public static int getRelockMode(Context context) {
        return prefs(context).getInt(KEY_RELOCK_MODE, RELOCK_INSTANT);
    }

    public static void setRelockMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_RELOCK_MODE, mode).apply();
    }

    public static long getRelockDelayMs() {
        return RELOCK_DELAY_MS;
    }

    // ------------------------------------------------------------------
    // Sahte çökme ekranı (Fake Cover / Crash Dialog)
    // ------------------------------------------------------------------
    public static boolean isFakeCrashEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FAKE_CRASH_ENABLED, false);
    }

    public static void setFakeCrashEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FAKE_CRASH_ENABLED, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Yanlış şifrede gizli fotoğraf (Intruder Selfie)
    // ------------------------------------------------------------------
    public static boolean isIntruderSelfieEnabled(Context context) {
        return prefs(context).getBoolean(KEY_INTRUDER_SELFIE_ENABLED, true);
    }

    public static void setIntruderSelfieEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_INTRUDER_SELFIE_ENABLED, enabled).apply();
    }

    public static int getWrongAttemptsThreshold() {
        return WRONG_ATTEMPTS_THRESHOLD;
    }

    /**
     * Yakalanan "izinsiz kullanıcı" fotoğraflarının saklandığı klasör.
     * Uygulama özel (private) depo alanında tutulur, ekstra depolama
     * izni gerektirmez.
     */
    public static File getIntruderSelfieDir(Context context) {
        File dir = new File(context.getFilesDir(), INTRUDER_SELFIE_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // Kilit Türü: PIN veya Desen (Pattern)
    // ------------------------------------------------------------------
    private static final String KEY_LOCK_TYPE = "lock_type";
    private static final String KEY_PATTERN = "user_pattern";
    public static final String LOCK_TYPE_PIN = "pin";
    public static final String LOCK_TYPE_PATTERN = "pattern";

    public static String getLockType(Context context) {
        return prefs(context).getString(KEY_LOCK_TYPE, LOCK_TYPE_PIN);
    }

    public static void setLockType(Context context, String type) {
        prefs(context).edit().putString(KEY_LOCK_TYPE, type).apply();
    }

    public static boolean isPatternMode(Context context) {
        return LOCK_TYPE_PATTERN.equals(getLockType(context));
    }

    /**
     * Desen, dokunulan noktaların (0-8) sırasını "-" ile ayırarak
     * saklar (ör: "0-1-4-7"). En az 4 nokta zorunlu tutulur (PatternLockView
     * tarafında da doğrulanır).
     */
    public static String getPattern(Context context) {
        return prefs(context).getString(KEY_PATTERN, "");
    }

    public static void setPattern(Context context, String patternString) {
        prefs(context).edit().putString(KEY_PATTERN, patternString).apply();
    }

    public static boolean hasPatternSet(Context context) {
        return !TextUtils.isEmpty(getPattern(context));
    }
}
