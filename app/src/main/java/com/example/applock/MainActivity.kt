package com.example.applock

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsHelper
    private lateinit var adapter: AppListAdapter
    private lateinit var recyclerApps: RecyclerView
    private lateinit var cardPermissionWarning: CardView
    private lateinit var tvPermissionTitle: TextView
    private lateinit var tvPermissionDesc: TextView
    private lateinit var btnGrantPermission: Button
    private lateinit var etSearch: EditText

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsHelper(this)

        recyclerApps = findViewById(R.id.recyclerApps)
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning)
        tvPermissionTitle = findViewById(R.id.tvPermissionTitle)
        tvPermissionDesc = findViewById(R.id.tvPermissionDesc)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        etSearch = findViewById(R.id.etSearch)

        recyclerApps.layoutManager = LinearLayoutManager(this)

        // İlk kurulumda PIN yoksa önce PIN belirleme ekranına yönlendir
        if (!prefs.isPinSet()) {
            startActivity(Intent(this, SetupPinActivity::class.java))
        }

        loadInstalledApps()
        setupSearch()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionCard()
        if (PermissionUtils.hasAllRequiredPermissions(this)) {
            AppLockService.start(this)
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (::adapter.isInitialized) adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Yüklü uygulamaları arka plan iş parçacığında (thread) yükler; UI thread'i
     * bloklamaz, böylece eski/düşük RAM'li cihazlarda takılma yaşanmaz.
     */
    private fun loadInstalledApps() {
        thread {
            val pm = packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val lockedSet = prefs.getLockedApps()

            val result = installedApps
                .filter { it.packageName != packageName } // kendi uygulamamızı listeleme
                .filter { isUserVisibleApp(it) }
                .map { appInfo ->
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        isLocked = lockedSet.contains(appInfo.packageName)
                    )
                }
                .sortedBy { it.appName.lowercase() }
                .toMutableList()

            mainHandler.post {
                adapter = AppListAdapter(result) { app, locked ->
                    prefs.setAppLocked(app.packageName, locked)
                }
                recyclerApps.adapter = adapter
            }
        }
    }

    /** Sistem uygulamalarını gizleyip kullanıcı tarafından yüklenen/güncellenen uygulamaları gösterir. */
    private fun isUserVisibleApp(appInfo: ApplicationInfo): Boolean {
        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val wasUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return !isSystemApp || wasUpdated
    }

    private fun refreshPermissionCard() {
        val hasOverlay = PermissionUtils.hasOverlayPermission(this)
        val hasUsage = PermissionUtils.hasUsageStatsPermission(this)

        when {
            !hasOverlay -> showPermissionCard(
                getString(R.string.permission_overlay_title),
                getString(R.string.permission_overlay_desc)
            ) { requestOverlayPermission() }

            !hasUsage -> showPermissionCard(
                getString(R.string.permission_usage_title),
                getString(R.string.permission_usage_desc)
            ) { requestUsageAccessPermission() }

            else -> cardPermissionWarning.visibility = android.view.View.GONE
        }
    }

    private fun showPermissionCard(title: String, desc: String, onClick: () -> Unit) {
        cardPermissionWarning.visibility = android.view.View.VISIBLE
        tvPermissionTitle.text = title
        tvPermissionDesc.text = desc
        btnGrantPermission.setOnClickListener { onClick() }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.permission_overlay_desc, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestUsageAccessPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
