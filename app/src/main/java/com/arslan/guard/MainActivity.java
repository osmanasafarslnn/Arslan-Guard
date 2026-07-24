package com.arslan.guard;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private LinearLayout permissionCard;
    private TextView txtUsageMissing, txtOverlayMissing;
    private RecyclerView recyclerApps;
    private AppAdapter adapter;
    private final List<AppInfo> appInfoList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionCard = findViewById(R.id.permissionCard);
        txtUsageMissing = findViewById(R.id.txtUsageMissing);
        txtOverlayMissing = findViewById(R.id.txtOverlayMissing);
        recyclerApps = findViewById(R.id.recyclerApps);
        Button btnGrantPermissions = findViewById(R.id.btnGrantPermissions);

        recyclerApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter(appInfoList, new AppAdapter.OnLockToggleListener() {
            @Override
            public void onToggle(AppInfo appInfo, boolean locked) {
                PrefsHelper.setLocked(MainActivity.this, appInfo.getPackageName(), locked);
            }
        });
        recyclerApps.setAdapter(adapter);

        btnGrantPermissions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasUsageAccess()) {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } else if (!hasOverlayPermission()) {
                    requestOverlayPermission();
                }
            }
        });

        loadInstalledApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionCard();

        // İzinler tamamsa arka plan servisini başlat / canlı tut
        if (hasUsageAccess() && hasOverlayPermission()) {
            startLockService();
        }
    }

    private void refreshPermissionCard() {
        boolean usageOk = hasUsageAccess();
        boolean overlayOk = hasOverlayPermission();

        if (usageOk && overlayOk) {
            permissionCard.setVisibility(View.GONE);
        } else {
            permissionCard.setVisibility(View.VISIBLE);
            txtUsageMissing.setVisibility(usageOk ? View.GONE : View.VISIBLE);
            txtOverlayMissing.setVisibility(overlayOk ? View.GONE : View.VISIBLE);
        }
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(
                        "android:get_usage_stats", android.os.Process.myUid(), getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(
                        "android:get_usage_stats", android.os.Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true; // API 21-22 için ekstra izin gerekmez
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void startLockService() {
        Intent serviceIntent = new Intent(this, LockService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Cihazda yüklü, başlatılabilir (launcher'da görünen) uygulamaları
     * listeler. Kendi uygulamamızı listeden çıkarırız.
     */
    private void loadInstalledApps() {
        appInfoList.clear();
        PackageManager pm = getPackageManager();

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfoList = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo resolveInfo : resolveInfoList) {
            ApplicationInfo appInfo = resolveInfo.activityInfo.applicationInfo;
            String packageName = appInfo.packageName;

            if (packageName.equals(getPackageName())) {
                continue; // kendi uygulamamızı listeleme
            }

            String label = String.valueOf(pm.getApplicationLabel(appInfo));
            boolean locked = PrefsHelper.isLocked(this, packageName);

            appInfoList.add(new AppInfo(label, packageName,
                    pm.getApplicationIcon(appInfo), locked));
        }

        Collections.sort(appInfoList, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return a.getAppName().compareToIgnoreCase(b.getAppName());
            }
        });

        adapter.notifyDataSetChanged();

        if (appInfoList.isEmpty()) {
            Toast.makeText(this, "Listelenecek uygulama bulunamadı", Toast.LENGTH_SHORT).show();
        }
    }
}
