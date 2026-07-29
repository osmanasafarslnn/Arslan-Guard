package com.example.applock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Kurulum akışı: kullanıcı 4 haneli bir PIN girer, ardından aynı PIN'i
 * tekrar girerek onaylar. Onaylanan PIN tuzlanıp hash'lenerek saklanır
 * (bkz. PrefsHelper). Açık metin PIN hiçbir zaman diske yazılmaz.
 */
class SetupPinActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsHelper
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var dots: List<View>

    private var firstEntry: String? = null
    private val currentInput = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_pin)

        prefs = PrefsHelper(this)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvError = findViewById(R.id.tvError)
        dots = listOf(
            findViewById(R.id.dot1), findViewById(R.id.dot2),
            findViewById(R.id.dot3), findViewById(R.id.dot4)
        )

        setupKeypad()
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
        // Kurulum ekranında desen seçeneği kullanılmıyor; gizle.
        findViewById<TextView>(R.id.keyPattern).visibility = View.INVISIBLE
    }

    private fun onDigitPressed(digit: String) {
        if (currentInput.length >= 4) return
        currentInput.append(digit)
        updateDots()

        if (currentInput.length == 4) {
            handleCompletedEntry(currentInput.toString())
        }
    }

    private fun onBackspace() {
        if (currentInput.isNotEmpty()) {
            currentInput.deleteCharAt(currentInput.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        for (i in dots.indices) {
            dots[i].setBackgroundResource(
                if (i < currentInput.length) R.drawable.dot_filled else R.drawable.dot_empty
            )
        }
    }

    private fun handleCompletedEntry(pin: String) {
        if (firstEntry == null) {
            // İlk giriş tamamlandı; onay için tekrar iste
            firstEntry = pin
            currentInput.clear()
            tvSubtitle.text = getString(R.string.confirm_pin_subtitle)
            tvError.visibility = View.INVISIBLE
            dots.forEach { it.postDelayed({ updateDots() }, 150) }
        } else {
            if (pin == firstEntry) {
                prefs.setPin(pin)
                prefs.setPreferredLockMethod(PrefsHelper.LOCK_METHOD_PIN)
                finish()
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                // Uyuşmuyor: baştan başlat
                tvError.visibility = View.VISIBLE
                firstEntry = null
                currentInput.clear()
                tvSubtitle.text = getString(R.string.setup_pin_subtitle)
                updateDots()
            }
        }
    }
}
