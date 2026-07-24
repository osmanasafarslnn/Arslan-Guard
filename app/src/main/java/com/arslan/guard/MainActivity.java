package com.arslan.guard;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_IMAGE = 501;

    private LinearLayout permissionCard;
    private TextView txtUsageMissing, txtOverlayMissing;
    private RecyclerView recyclerApps;
    private AppAdapter adapter;
    private final List<AppInfo> appInfoList = new ArrayList<>();

    private SeekBar seekOpacity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionCard = findViewById(R.id.permissionCard);
        txtUsageMissing = findViewById(R.id.txtUsageMissing);
        txtOverlayMissing = findViewById(R.id.txtOverlayMissing);
        recyclerApps = findViewById(R.id.recyclerApps);
        Button btnGrantPermissions = findViewById(R.id.btnGrantPermissions);

        Button btnChangePin = findViewById(R.id.btnChangePin);
        Button btnChooseBackground = findViewById(R.id.btnChooseBackground);
        Button btnResetBackground = findViewById(R.id.btnResetBackground);
        seekOpacity = findViewById(R.id.seekOpacity);

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

        btnChangePin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangePinDialog();
            }
        });

        btnChooseBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openImagePicker();
            }
        });

        btnResetBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrefsHelper.clearCustomBackground(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        R.string.background_reset_done, Toast.LENGTH_SHORT).show();
            }
        });

        setupOpacitySeekBar();
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

    // ------------------------------------------------------------------
    // ŞİFRE (PIN) DEĞİŞTİRME
    // ------------------------------------------------------------------
    private void showChangePinDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_change_pin, null);

        final EditText editCurrentPin = dialogView.findViewById(R.id.editCurrentPin);
        final EditText editNewPin = dialogView.findViewById(R.id.editNewPin);
        final EditText editConfirmPin = dialogView.findViewById(R.id.editConfirmPin);
        final TextView txtDialogError = dialogView.findViewById(R.id.txtDialogError);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.save, null) // aşağıda manuel override edilecek
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.show();

        // Pozitif butonu manuel yakalıyoruz ki hatalı girişte dialog kapanmasın
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String currentPin = editCurrentPin.getText().toString();
                String newPin = editNewPin.getText().toString();
                String confirmPin = editConfirmPin.getText().toString();

                String realPin = PrefsHelper.getPin(MainActivity.this);

                if (!currentPin.equals(realPin)) {
                    showDialogError(txtDialogError, getString(R.string.current_pin_wrong));
                    return;
                }
                if (newPin.length() < 4) {
                    showDialogError(txtDialogError, getString(R.string.pin_too_short));
                    return;
                }
                if (!newPin.equals(confirmPin)) {
                    showDialogError(txtDialogError, getString(R.string.pin_mismatch));
                    return;
                }

                PrefsHelper.setPin(MainActivity.this, newPin);
                Toast.makeText(MainActivity.this, R.string.pin_changed_success, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
    }

    private void showDialogError(TextView txtDialogError, String message) {
        txtDialogError.setText(message);
        txtDialogError.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // KİLİT EKRANI ŞEFFAFLIK AYARI
    // ------------------------------------------------------------------
    private void setupOpacitySeekBar() {
        seekOpacity.setProgress(PrefsHelper.getOverlayAlpha(this));
        seekOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    PrefsHelper.setOverlayAlpha(MainActivity.this, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // gerekli değil
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // gerekli değil
            }
        });
    }

    // ------------------------------------------------------------------
    // ÖZEL ARKA PLAN RESMİ SEÇİMİ
    // ------------------------------------------------------------------
    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        } catch (Exception e) {
            // Bazı eski cihazlarda ACTION_OPEN_DOCUMENT olmayabilir; ACTION_GET_CONTENT'e düş
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            startActivityForResult(fallback, REQUEST_CODE_PICK_IMAGE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {

            final Uri selectedUri = data.getData();

            // Kopyalama işlemini arka planda yapıyoruz ki UI thread donmasın
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean success = copyImageToInternalStorage(selectedUri);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (success) {
                                PrefsHelper.setHasCustomBackground(MainActivity.this, true);
                                Toast.makeText(MainActivity.this,
                                        R.string.background_updated, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        R.string.background_load_error, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        }
    }

    /**
     * Kullanıcının seçtiği resmi ContentResolver üzerinden okuyup uygulamanın
     * özel (private) depo alanına sabit bir dosya adıyla kaydeder. Böylece
     * content:// URI izinlerinin kalıcılığıyla uğraşmaya gerek kalmaz;
     * LockService her zaman aynı dosya yolunu okur.
     */
    private boolean copyImageToInternalStorage(Uri sourceUri) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(sourceUri);
            if (inputStream == null) return false;

            File destFile = PrefsHelper.getBackgroundFile(this);
            outputStream = new FileOutputStream(destFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (inputStream != null) inputStream.close();
            } catch (Exception ignored) {
            }
            try {
                if (outputStream != null) outputStream.close();
            } catch (Exception ignored) {
            }
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
