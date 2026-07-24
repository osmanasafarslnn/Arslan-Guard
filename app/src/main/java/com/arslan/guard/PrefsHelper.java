package com.arslan.guard;

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
}
