package com.arslan.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Kilitli uygulama listesini ve PIN kodunu saklayan basit
 * SharedPreferences sarmalayıcı sınıf.
 */
public class PrefsHelper {

    private static final String PREFS_NAME = "arslan_guard_prefs";
    private static final String KEY_LOCKED_APPS = "locked_apps";
    private static final String KEY_PIN = "user_pin";
    private static final String DEFAULT_PIN = "1234";

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
}
