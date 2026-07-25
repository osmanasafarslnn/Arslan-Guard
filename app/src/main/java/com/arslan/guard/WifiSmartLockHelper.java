package com.arslan.guard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

/**
 * Cihazın şu anda bağlı olduğu Wi-Fi ağının, kullanıcının "güvenli" olarak
 * işaretlediği ağ (ör. ev/ofis) olup olmadığını kontrol eder.
 *
 * NOT: Android 8.0 (API 26) ve üzerinde bağlı SSID bilgisini okuyabilmek
 * için ACCESS_FINE_LOCATION çalışma zamanı izni sistem tarafından zorunlu
 * kılınmıştır (Google'ın gizlilik politikası). Bu, konum takibi için değil,
 * sadece SSID okuma API kısıtlaması yüzündendir.
 */
public class WifiSmartLockHelper {

    /**
     * Şu anda bağlı olunan Wi-Fi ağının SSID'sini döndürür.
     * İzin yoksa veya Wi-Fi'a bağlı değilse null döner.
     */
    public static String getCurrentSsid(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null; // API 26+ için konum izni olmadan SSID okunamaz
        }

        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null || !wifiManager.isWifiEnabled()) return null;

            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return null;

            String ssid = info.getSSID();
            if (TextUtils.isEmpty(ssid) || "<unknown ssid>".equalsIgnoreCase(ssid)) {
                return null;
            }
            // SSID genelde çift tırnak içinde döner (ör: "\"EvWifi\""), temizliyoruz
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Şu anda bağlı ağın, kullanıcının tanımladığı güvenilir ağ olup
     * olmadığını kontrol eder. Wi-Fi Smart Lock özelliği kapalıysa veya
     * güvenilir ağ tanımlanmamışsa false döner (yani normal kilit davranışı
     * devam eder).
     */
    public static boolean isConnectedToTrustedNetwork(Context context) {
        if (!PrefsHelper.isWifiSmartLockEnabled(context) || !PrefsHelper.hasTrustedSsid(context)) {
            return false;
        }
        String currentSsid = getCurrentSsid(context);
        if (currentSsid == null) return false;

        String trustedSsid = PrefsHelper.getTrustedSsid(context);
        return currentSsid.equalsIgnoreCase(trustedSsid);
    }
}
