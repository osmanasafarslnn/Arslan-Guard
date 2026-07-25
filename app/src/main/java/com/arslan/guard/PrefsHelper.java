package com.arslan.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Tüm uygulama ayarlarının (kilitli uygulama listesi, PIN/Desen, kilit
 * ekranı görünümü ve gelişmiş güvenlik özellikleri) SharedPreferences
 * üzerinden okunup yazıldığı tek merkez sınıf.
 *
 * Not: Hiçbir ağır bağımlılık (Gson, Room vb.) kullanılmaz; sadece
 * SharedPreferences ilkel tipleri (String/int/boolean/Set) kullanılır.
 */
public class PrefsHelper {

    private static final String PREFS_NAME = "arslan_guard_prefs";

    // ------------------------------------------------------------------
    // Kilitli uygulama listesi
    // ------------------------------------------------------------------
    private static final String KEY_LOCKED_APPS = "locked_apps";

    // ------------------------------------------------------------------
    // PIN
    // ------------------------------------------------------------------
    private static final String KEY_PIN = "user_pin";
    private static final String DEFAULT_PIN = "1234";

    // ------------------------------------------------------------------
    // Kilit ekranı görünüm ayarları
    // ------------------------------------------------------------------
    private static final String KEY_OVERLAY_ALPHA = "overlay_alpha"; // 0-100 (100 = tam opak)
    private static final String KEY_HAS_CUSTOM_BG = "has_custom_background";
    private static final int DEFAULT_ALPHA = 100;
    private static final String CUSTOM_BG_FILENAME = "lock_background.jpg";

    // ------------------------------------------------------------------
    // Yeniden kilitlenme (re-lock) modu
    // ------------------------------------------------------------------
    private static final String KEY_RELOCK_MODE = "relock_mode";
    public static final int RELOCK_INSTANT = 0;           // Anında Kilitle
    public static final int RELOCK_UNTIL_SCREEN_OFF = 1;  // Ekran Kapanana Kadar Kilitleme
    public static final int RELOCK_DELAY_1_MIN = 2;       // 1 Dakika Sonra Kilitle
    private static final long RELOCK_DELAY_MS = 60_000L;

    // ------------------------------------------------------------------
    // Sahte çökme ekranı (Fake Cover / Crash Dialog)
    // ------------------------------------------------------------------
    private static final String KEY_FAKE_CRASH_ENABLED = "fake_crash_enabled";
    private static final long FAKE_CRASH_HOLD_MS = 3000L; // 3 saniye basılı tutma

    // ------------------------------------------------------------------
    // Yanlış şifrede gizli fotoğraf (Intruder Selfie / IntruderCapture)
    // ------------------------------------------------------------------
    private static final String KEY_INTRUDER_SELFIE_ENABLED = "intruder_selfie_enabled";
    private static final String INTRUDER_SELFIE_DIR = "intruder_selfies";
    private static final int WRONG_ATTEMPTS_THRESHOLD = 2; // 2 yanlış denemede tetiklenir

    // ------------------------------------------------------------------
    // Kilit Türü: PIN veya Desen (Pattern)
    // ------------------------------------------------------------------
    private static final String KEY_LOCK_TYPE = "lock_type";
    private static final String KEY_PATTERN = "user_pattern";
    public static final String LOCK_TYPE_PIN = "pin";
    public static final String LOCK_TYPE_PATTERN = "pattern";

    // ------------------------------------------------------------------
    // Görünmez Desen / Anti-Peeping (Stealth Mode)
    // ------------------------------------------------------------------
    private static final String KEY_STEALTH_PATTERN_ENABLED = "stealth_pattern_enabled";

    // ------------------------------------------------------------------
    // Sallama ile Hızlı Kilitleme (Shake to Lock)
    // ------------------------------------------------------------------
    private static final String KEY_SHAKE_TO_LOCK_ENABLED = "shake_to_lock_enabled";
    private static final String KEY_SHAKE_SENSITIVITY = "shake_sensitivity"; // 1=düşük,2=orta,3=yüksek
    private static final int DEFAULT_SHAKE_SENSITIVITY = 2;

    // ------------------------------------------------------------------
    // Ağ Kontrollü Otomatik Kilit (Wi-Fi Smart Lock)
    // ------------------------------------------------------------------
    private static final String KEY_WIFI_SMART_LOCK_ENABLED = "wifi_smart_lock_enabled";
    private static final String KEY_TRUSTED_SSID = "trusted_wifi_ssid";

    // ------------------------------------------------------------------
    // Acil Durum Panik Şifresi (Panic PIN / Fake Vault)
    // ------------------------------------------------------------------
    private static final String KEY_PANIC_PIN_ENABLED = "panic_pin_enabled";
    private static final String KEY_PANIC_PIN = "panic_pin_value";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Kilitli uygulama listesi
    // ------------------------------------------------------------------
    public static Set<String> getLockedApps(Context context) {
        SharedPreferences p = prefs(context);
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

    // ------------------------------------------------------------------
    // PIN
    // ------------------------------------------------------------------
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

    public static File getBackgroundFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_BG_FILENAME);
    }

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

    public static long getFakeCrashHoldMs() {
        return FAKE_CRASH_HOLD_MS;
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
     * Desen, dokunulan noktaların (0-8) sırasını "-" ile ayırarak saklar
     * (ör: "0-1-4-7"). PatternLockView en az 4 nokta zorunlu tutar.
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

    // ------------------------------------------------------------------
    // Görünmez Desen / Anti-Peeping (Stealth Mode)
    // ------------------------------------------------------------------
    public static boolean isStealthPatternEnabled(Context context) {
        return prefs(context).getBoolean(KEY_STEALTH_PATTERN_ENABLED, false);
    }

    public static void setStealthPatternEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_STEALTH_PATTERN_ENABLED, enabled).apply();
    }

    // ------------------------------------------------------------------
    // Sallama ile Hızlı Kilitleme (Shake to Lock)
    // ------------------------------------------------------------------
    public static boolean isShakeToLockEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHAKE_TO_LOCK_ENABLED, false);
    }

    public static void setShakeToLockEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHAKE_TO_LOCK_ENABLED, enabled).apply();
    }

    /** 1 = Düşük hassasiyet (sert sallama gerekir), 3 = Yüksek hassasiyet (hafif sallama yeter) */
    public static int getShakeSensitivity(Context context) {
        return prefs(context).getInt(KEY_SHAKE_SENSITIVITY, DEFAULT_SHAKE_SENSITIVITY);
    }

    public static void setShakeSensitivity(Context context, int level) {
        if (level < 1) level = 1;
        if (level > 3) level = 3;
        prefs(context).edit().putInt(KEY_SHAKE_SENSITIVITY, level).apply();
    }

    // ------------------------------------------------------------------
    // Ağ Kontrollü Otomatik Kilit (Wi-Fi Smart Lock)
    // ------------------------------------------------------------------
    public static boolean isWifiSmartLockEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WIFI_SMART_LOCK_ENABLED, false);
    }

    public static void setWifiSmartLockEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_WIFI_SMART_LOCK_ENABLED, enabled).apply();
    }

    public static String getTrustedSsid(Context context) {
        return prefs(context).getString(KEY_TRUSTED_SSID, "");
    }

    public static void setTrustedSsid(Context context, String ssid) {
        prefs(context).edit().putString(KEY_TRUSTED_SSID, ssid).apply();
    }

    public static boolean hasTrustedSsid(Context context) {
        return !TextUtils.isEmpty(getTrustedSsid(context));
    }

    // ------------------------------------------------------------------
    // Acil Durum Panik Şifresi (Panic PIN / Fake Vault)
    // ------------------------------------------------------------------
    public static boolean isPanicPinEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PANIC_PIN_ENABLED, false);
    }

    public static void setPanicPinEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PANIC_PIN_ENABLED, enabled).apply();
    }

    public static String getPanicPin(Context context) {
        return prefs(context).getString(KEY_PANIC_PIN, "");
    }

    public static void setPanicPin(Context context, String pin) {
        prefs(context).edit().putString(KEY_PANIC_PIN, pin).apply();
    }

    public static boolean hasPanicPinSet(Context context) {
        return !TextUtils.isEmpty(getPanicPin(context));
    }

    /**
     * Girilen kodun panik şifresi olup olmadığını kontrol eder. Panik modu
     * kapalıysa veya panik şifresi tanımlanmamışsa her zaman false döner.
     */
    public static boolean isPanicCode(Context context, String enteredCode) {
        if (!isPanicPinEnabled(context) || !hasPanicPinSet(context)) return false;
        return !TextUtils.isEmpty(enteredCode) && enteredCode.equals(getPanicPin(context));
    }
}
