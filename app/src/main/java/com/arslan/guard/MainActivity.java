package com.arslan.guard;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
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
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
    private static final int REQUEST_CODE_CAMERA_PERMISSION = 502;
    private static final int REQUEST_CODE_LOCATION_PERMISSION = 503;

    private LinearLayout permissionCard;
    private TextView txtUsageMissing, txtOverlayMissing;
    private RecyclerView recyclerApps;
    private AppAdapter adapter;
    private final List<AppInfo> appInfoList = new ArrayList<>();

    private SeekBar seekOpacity;
    private Switch switchIntruderSelfie;
    private Switch switchFakeCrash;
    private RadioGroup radioGroupRelock;
    private TextView txtCameraPermissionMissing;
    private Button btnGrantCameraPermission;

    private RadioGroup radioGroupLockType;
    private Button btnSetPattern;
    private Switch switchStealthPattern;
    private Switch switchShakeToLock;
    private Switch switchWifiSmartLock;
    private EditText editTrustedSsid;
    private Button btnUseCurrentNetwork;
    private Switch switchPanicPin;
    private Button btnSetPanicPin;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupAppList();
        setupPermissionCard();
        setupChangePin();
        setupOpacitySeekBar();
        setupBackgroundPicker();
        setupAdvancedSecuritySettings();
        setupDevicePermissionGuide();
        setupLockTypeAndExtras();

        loadInstalledApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionCard();
        refreshCameraPermissionWarning();

        // İzinler tamamsa arka plan servisini başlat / canlı tut
        if (hasUsageAccess() && hasOverlayPermission()) {
            startLockService();
        }
    }

    private void bindViews() {
        permissionCard = findViewById(R.id.permissionCard);
        txtUsageMissing = findViewById(R.id.txtUsageMissing);
        txtOverlayMissing = findViewById(R.id.txtOverlayMissing);
        recyclerApps = findViewById(R.id.recyclerApps);

        seekOpacity = findViewById(R.id.seekOpacity);
        switchIntruderSelfie = findViewById(R.id.switchIntruderSelfie);
        switchFakeCrash = findViewById(R.id.switchFakeCrash);
        radioGroupRelock = findViewById(R.id.radioGroupRelock);
        txtCameraPermissionMissing = findViewById(R.id.txtCameraPermissionMissing);
        btnGrantCameraPermission = findViewById(R.id.btnGrantCameraPermission);

        radioGroupLockType = findViewById(R.id.radioGroupLockType);
        btnSetPattern = findViewById(R.id.btnSetPattern);
        switchStealthPattern = findViewById(R.id.switchStealthPattern);
        switchShakeToLock = findViewById(R.id.switchShakeToLock);
        switchWifiSmartLock = findViewById(R.id.switchWifiSmartLock);
        editTrustedSsid = findViewById(R.id.editTrustedSsid);
        btnUseCurrentNetwork = findViewById(R.id.btnUseCurrentNetwork);
        switchPanicPin = findViewById(R.id.switchPanicPin);
        btnSetPanicPin = findViewById(R.id.btnSetPanicPin);
    }

    private void setupAppList() {
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter(appInfoList, new AppAdapter.OnLockToggleListener() {
            @Override
            public void onToggle(AppInfo appInfo, boolean locked) {
                PrefsHelper.setLocked(MainActivity.this, appInfo.getPackageName(), locked);
            }
        });
        recyclerApps.setAdapter(adapter);
    }

    // ------------------------------------------------------------------
    // TEMEL İZİNLER (Usage Access / Overlay)
    // ------------------------------------------------------------------
    private void setupPermissionCard() {
        Button btnGrantPermissions = findViewById(R.id.btnGrantPermissions);
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
    private void setupChangePin() {
        Button btnChangePin = findViewById(R.id.btnChangePin);
        btnChangePin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangePinDialog();
            }
        });
    }

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
    private void setupBackgroundPicker() {
        Button btnChooseBackground = findViewById(R.id.btnChooseBackground);
        Button btnResetBackground = findViewById(R.id.btnResetBackground);

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
    }

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

    // ------------------------------------------------------------------
    // GELİŞMİŞ GÜVENLİK: Intruder Selfie / Re-lock Timeout / Fake Crash
    // ------------------------------------------------------------------
    private void setupAdvancedSecuritySettings() {
        // --- Intruder Selfie ---
        switchIntruderSelfie.setChecked(PrefsHelper.isIntruderSelfieEnabled(this));
        switchIntruderSelfie.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setIntruderSelfieEnabled(MainActivity.this, isChecked);
                if (isChecked && !hasCameraPermission()) {
                    requestCameraPermission();
                }
                refreshCameraPermissionWarning();
            }
        });

        btnGrantCameraPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCameraPermission();
            }
        });

        // --- Re-lock Timeout ---
        int currentMode = PrefsHelper.getRelockMode(this);
        if (currentMode == PrefsHelper.RELOCK_UNTIL_SCREEN_OFF) {
            radioGroupRelock.check(R.id.radioRelockScreenOff);
        } else if (currentMode == PrefsHelper.RELOCK_DELAY_1_MIN) {
            radioGroupRelock.check(R.id.radioRelockDelay1Min);
        } else {
            radioGroupRelock.check(R.id.radioRelockInstant);
        }

        radioGroupRelock.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int mode;
                if (checkedId == R.id.radioRelockScreenOff) {
                    mode = PrefsHelper.RELOCK_UNTIL_SCREEN_OFF;
                } else if (checkedId == R.id.radioRelockDelay1Min) {
                    mode = PrefsHelper.RELOCK_DELAY_1_MIN;
                } else {
                    mode = PrefsHelper.RELOCK_INSTANT;
                }
                PrefsHelper.setRelockMode(MainActivity.this, mode);
            }
        });

        // --- Fake Crash Cover ---
        switchFakeCrash.setChecked(PrefsHelper.isFakeCrashEnabled(this));
        switchFakeCrash.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setFakeCrashEnabled(MainActivity.this, isChecked);
            }
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA_PERMISSION);
    }

    private void refreshCameraPermissionWarning() {
        boolean needsWarning = PrefsHelper.isIntruderSelfieEnabled(this) && !hasCameraPermission();
        txtCameraPermissionMissing.setVisibility(needsWarning ? View.VISIBLE : View.GONE);
        btnGrantCameraPermission.setVisibility(needsWarning ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            refreshCameraPermissionWarning();
        } else if (requestCode == REQUEST_CODE_LOCATION_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                fillCurrentSsidIntoField();
            } else {
                Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // MIUI / CİHAZA ÖZEL İZİN REHBERİ
    // ------------------------------------------------------------------
    private void setupDevicePermissionGuide() {
        Button btnAutoStart = findViewById(R.id.btnAutoStart);
        Button btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        Button btnBackgroundPermission = findViewById(R.id.btnBackgroundPermission);

        btnAutoStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutoStartSettings();
            }
        });

        btnBatteryOptimization.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        });

        btnBackgroundPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetailsSettings();
            }
        });
    }

    /**
     * MIUI (Xiaomi), EMUI (Huawei), ColorOS (Oppo), FuntouchOS (Vivo) gibi
     * özel Android arayüzlerinde "Otomatik Başlatma" ayarına yönlendirmeyi
     * dener. Üretici arayüzü tanınamazsa uygulama detay ayarlarına düşer.
     */
    private void openAutoStartSettings() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        List<Intent> candidates = new ArrayList<>();

        if (manufacturer.contains("xiaomi")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
        } else if (manufacturer.contains("huawei")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity")));
        } else if (manufacturer.contains("oppo")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")));
        } else if (manufacturer.contains("vivo")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")));
        } else if (manufacturer.contains("oneplus")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")));
        }

        if (!tryStartAnyIntent(candidates)) {
            Toast.makeText(this,
                    "Bu cihaz için otomatik başlatma ayarı bulunamadı, uygulama ayarlarına yönlendiriliyorsunuz",
                    Toast.LENGTH_LONG).show();
            openAppDetailsSettings();
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                String packageName = getPackageName();

                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Pil optimizasyonu zaten devre dışı", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } else {
            Toast.makeText(this, "Bu Android sürümünde pil optimizasyonu ayarı bulunmuyor", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppDetailsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    /**
     * Verilen intent listesindeki ilk çözümlenebilir (resolve edilebilir)
     * intent'i başlatır. Hiçbiri çalışmazsa false döner.
     */
    private boolean tryStartAnyIntent(List<Intent> intents) {
        for (Intent intent : intents) {
            try {
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    return true;
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // sıradaki adaya geç
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // KİLİT TÜRÜ (PIN/Desen), STEALTH, SHAKE-TO-LOCK, WIFI SMART LOCK, PANIC PIN
    // ------------------------------------------------------------------
    private void setupLockTypeAndExtras() {
        // --- Kilit Türü ---
        boolean patternMode = PrefsHelper.isPatternMode(this);
        radioGroupLockType.check(patternMode ? R.id.radioLockPattern : R.id.radioLockPin);
        btnSetPattern.setVisibility(patternMode ? View.VISIBLE : View.GONE);

        radioGroupLockType.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean isPattern = checkedId == R.id.radioLockPattern;

                if (isPattern && !PrefsHelper.hasPatternSet(MainActivity.this)) {
                    // Henüz desen tanımlanmamış: önce belirlemesini iste
                    btnSetPattern.setVisibility(View.VISIBLE);
                    showSetPatternDialog();
                    return;
                }

                PrefsHelper.setLockType(MainActivity.this,
                        isPattern ? PrefsHelper.LOCK_TYPE_PATTERN : PrefsHelper.LOCK_TYPE_PIN);
                btnSetPattern.setVisibility(isPattern ? View.VISIBLE : View.GONE);
            }
        });

        btnSetPattern.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSetPatternDialog();
            }
        });

        // --- Görünmez Desen (Stealth) ---
        switchStealthPattern.setChecked(PrefsHelper.isStealthPatternEnabled(this));
        switchStealthPattern.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setStealthPatternEnabled(MainActivity.this, isChecked);
            }
        });

        // --- Sallama ile Kilitle ---
        switchShakeToLock.setChecked(PrefsHelper.isShakeToLockEnabled(this));
        switchShakeToLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setShakeToLockEnabled(MainActivity.this, isChecked);
            }
        });

        // --- Wi-Fi Smart Lock ---
        editTrustedSsid.setText(PrefsHelper.getTrustedSsid(this));
        switchWifiSmartLock.setChecked(PrefsHelper.isWifiSmartLockEnabled(this));
        switchWifiSmartLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setWifiSmartLockEnabled(MainActivity.this, isChecked);
                if (isChecked && !hasLocationPermission()) {
                    requestLocationPermission();
                }
            }
        });

        editTrustedSsid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    PrefsHelper.setTrustedSsid(MainActivity.this, editTrustedSsid.getText().toString().trim());
                }
            }
        });

        btnUseCurrentNetwork.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasLocationPermission()) {
                    fillCurrentSsidIntoField();
                } else {
                    requestLocationPermission();
                }
            }
        });

        // --- Panik Şifresi ---
        switchPanicPin.setChecked(PrefsHelper.isPanicPinEnabled(this));
        switchPanicPin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked && !PrefsHelper.hasPanicPinSet(MainActivity.this)) {
                    showSetPanicPinDialog();
                }
                PrefsHelper.setPanicPinEnabled(MainActivity.this, isChecked);
            }
        });

        btnSetPanicPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSetPanicPinDialog();
            }
        });
    }

    /**
     * Desen belirleme diyaloğu: kullanıcı deseni bir kere çizer, ardından
     * onaylamak için tekrar çizmesi istenir. İki desen eşleşirse kaydedilir
     * ve kilit türü otomatik olarak "Desen" yapılır.
     */
    private void showSetPatternDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_set_pattern, null);

        final TextView txtStatus = dialogView.findViewById(R.id.txtPatternStatus);
        final TextView txtError = dialogView.findViewById(R.id.txtPatternError);
        final PatternLockView patternView = dialogView.findViewById(R.id.patternSetupView);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        // Kullanıcı vazgeçti; radio seçimini gerçek duruma geri al
                        radioGroupLockType.check(PrefsHelper.isPatternMode(MainActivity.this)
                                ? R.id.radioLockPattern : R.id.radioLockPin);
                    }
                })
                .setCancelable(false)
                .create();

        final String[] firstPattern = {null};

        patternView.setPatternListener(new PatternLockView.PatternListener() {
            @Override
            public void onPatternComplete(String patternCode) {
                if (patternCode.length() < 1 || countDots(patternCode) < 4) {
                    txtError.setText(R.string.pattern_too_short);
                    txtError.setVisibility(View.VISIBLE);
                    patternView.reset();
                    return;
                }

                if (firstPattern[0] == null) {
                    // İlk çizim tamamlandı, onay için tekrar çizmesini iste
                    firstPattern[0] = patternCode;
                    txtError.setVisibility(View.GONE);
                    txtStatus.setText(R.string.draw_pattern_again);
                    patternView.reset();
                } else {
                    if (firstPattern[0].equals(patternCode)) {
                        PrefsHelper.setPattern(MainActivity.this, patternCode);
                        PrefsHelper.setLockType(MainActivity.this, PrefsHelper.LOCK_TYPE_PATTERN);
                        btnSetPattern.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, R.string.pattern_saved, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        txtError.setText(R.string.pattern_mismatch);
                        txtError.setVisibility(View.VISIBLE);
                        firstPattern[0] = null;
                        txtStatus.setText(R.string.draw_new_pattern);
                        patternView.reset();
                    }
                }
            }
        });

        dialog.show();
    }

    private int countDots(String patternCode) {
        if (TextUtils.isEmpty(patternCode)) return 0;
        return patternCode.split("-").length;
    }

    /**
     * Panik şifresi belirleme diyaloğu. Girilen kod, gerçek PIN/Desen ile
     * aynı olamaz (aksi halde ayırt edilemez).
     */
    private void showSetPanicPinDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_set_panic_pin, null);

        final EditText editPanicPin = dialogView.findViewById(R.id.editPanicPin);
        final EditText editPanicPinConfirm = dialogView.findViewById(R.id.editPanicPinConfirm);
        final TextView txtError = dialogView.findViewById(R.id.txtPanicPinError);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (!PrefsHelper.hasPanicPinSet(MainActivity.this)) {
                            switchPanicPin.setChecked(false);
                        }
                    }
                })
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String panicPin = editPanicPin.getText().toString();
                String confirmPin = editPanicPinConfirm.getText().toString();

                if (panicPin.length() < 4) {
                    showDialogError(txtError, getString(R.string.pin_too_short));
                    return;
                }
                if (!panicPin.equals(confirmPin)) {
                    showDialogError(txtError, getString(R.string.pin_mismatch));
                    return;
                }
                boolean sameAsPatternOrPin = panicPin.equals(PrefsHelper.getPin(MainActivity.this))
                        || panicPin.equals(PrefsHelper.getPattern(MainActivity.this));
                if (sameAsPatternOrPin) {
                    showDialogError(txtError, getString(R.string.panic_pin_same_as_real));
                    return;
                }

                PrefsHelper.setPanicPin(MainActivity.this, panicPin);
                PrefsHelper.setPanicPinEnabled(MainActivity.this, true);
                switchPanicPin.setChecked(true);
                Toast.makeText(MainActivity.this, R.string.panic_pin_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
    }

    // ------------------------------------------------------------------
    // Wi-Fi Smart Lock: Konum izni (SSID okumak için gereklidir, API 26+)
    // ------------------------------------------------------------------
    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true; // eski sürümlerde gerekmez
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_LOCATION_PERMISSION);
    }

    private void fillCurrentSsidIntoField() {
        String ssid = WifiSmartLockHelper.getCurrentSsid(this);
        if (ssid != null) {
            editTrustedSsid.setText(ssid);
            PrefsHelper.setTrustedSsid(this, ssid);
        } else {
            Toast.makeText(this, "Bağlı bir Wi-Fi ağı bulunamadı", Toast.LENGTH_SHORT).show();
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
