package com.example.applock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Kilitli bir uygulama ön plana geldiğinde AppLockService tarafından
 * başlatılan tam ekran kilit arayüzü. Doğru PIN/desen girilene kadar
 * kullanıcı gerçek uygulamaya erişemez.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsHelper
    private lateinit var targetPackage: String

    private lateinit var pinModeContainer: View
    private lateinit var patternView: PatternLockView
    private lateinit var tvSwitchToPin: TextView
    private lateinit var tvLockStatus: TextView
    private lateinit var dots: List<View>

    private val currentPin = StringBuilder()
    private var usingPatternMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        prefs = PrefsHelper(this)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish(); return
        }

        bindViews()
        loadTargetAppInfo()
        setupKeypad()
        setupPatternView()
        decideInitialMode()
    }

    private fun bindViews() {
        pinModeContainer = findViewById(R.id.pinModeContainer)
        patternView = findViewById(R.id.patternView)
        tvSwitchToPin = findViewById(R.id.tvSwitchToPin)
        tvLockStatus = findViewById(R.id.tvLockStatus)
        dots = listOf(
            findViewById(R.id.lockDot1), findViewById(R.id.lockDot2),
            findViewById(R.id.lockDot3), findViewById(R.id.lockDot4)
        )
    }

    private fun loadTargetAppInfo() {
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            findViewById<ImageView>(R.id.ivLockedAppIcon).setImageDrawable(pm.getApplicationIcon(appInfo))
            findViewById<TextView>(R.id.tvLockedAppName).text = pm.getApplicationLabel(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            // Uygulama kaldırılmış olabilir; sessizce yok say
        }
    }

    private fun decideInitialMode() {
        val hasPattern = prefs.isPatternSet()
        val preferred = prefs.getPreferredLockMethod()
        usingPatternMode = hasPattern && preferred == PrefsHelper.LOCK_METHOD_PATTERN
        applyMode()
    }

    private fun applyMode() {
        pinModeContainer.visibility = if (usingPatternMode) View.GONE else View.VISIBLE
        patternView.visibility = if (usingPatternMode) View.VISIBLE else View.GONE
        tvSwitchToPin.visibility = if (usingPatternMode) View.VISIBLE else View.GONE
        tvLockStatus.text = getString(if (usingPatternMode) R.string.draw_pattern else R.string.enter_pin)
        currentPin.clear()
        updateDots()
        patternView.reset()
    }

    private fun setupKeypad() {
        val keyIds = mapOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2",
            R.id.key3 to "3", R.id.key4 to "4", R.id.key5 to "5",
            R.id.key6 to "6", R.id.key7 to "7", R.id.key8 to "8",
            R.id.key9 to "9"
        )
        keyIds.forEach { (id, digit) ->
            findViewById<TextView>(id).setOnClickListener { onDigitPressed(digit) }
        }
        findViewById<TextView>(R.id.keyBackspace).setOnClickListener { onBackspace() }

        val switchKey = findViewById<TextView>(R.id.keyPattern)
        if (prefs.isPatternSet()) {
            switchKey.text = getString(R.string.use_pattern)
            switchKey.textSize = 10f
            switchKey.setOnClickListener {
                usingPatternMode = true
                applyMode()
            }
        } else {
            switchKey.visibility = View.INVISIBLE
        }

        tvSwitchToPin.setOnClickListener {
            usingPatternMode = false
            applyMode()
        }
    }

    private fun setupPatternView() {
        patternView.onPatternComplete = { patternString ->
            if (prefs.verifyPattern(patternString)) {
                onUnlockSuccess()
            } else {
                vibrate()
                tvLockStatus.text = getString(R.string.wrong_pattern)
                patternView.showErrorAndReset()
            }
        }
    }

    private fun onDigitPressed(digit: String) {
        if (currentPin.length >= 4) return
        currentPin.append(digit)
        updateDots()
        if (currentPin.length == 4) {
            checkPin(currentPin.toString())
        }
    }

    private fun onBackspace() {
        if (currentPin.isNotEmpty()) {
            currentPin.deleteCharAt(currentPin.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        for (i in dots.indices) {
            dots[i].setBackgroundResource(
                if (i < currentPin.length) R.drawable.dot_filled else R.drawable.dot_empty
            )
        }
    }

    private fun checkPin(pin: String) {
        if (prefs.verifyPin(pin)) {
            onUnlockSuccess()
        } else {
            vibrate()
            tvLockStatus.text = getString(R.string.wrong_pin)
            currentPin.clear()
            updateDots()
        }
    }

    private fun onUnlockSuccess() {
        AppLockService.markUnlocked(targetPackage)
        finish()
    }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    /**
     * Kullanıcı geri tuşuna bastığında kilitli uygulamaya erişimin
     * engellenmesi için ana ekrana yönlendirilir; kilit ekranı atlanamaz.
     */
    override fun onBackPressed() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }
}
