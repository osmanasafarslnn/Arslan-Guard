package com.example.applock

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SharedPreferences üzerine kurulu güvenli yerel depolama katmanı.
 * PIN / desen açık metin olarak asla saklanmaz; tuzlanıp (salt) SHA-256 ile
 * hash'lenerek saklanır. Tuz (salt) her kurulumda rastgele üretilir.
 */
class PrefsHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- PIN / Desen kurulum durumu ----------

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = generateSalt()
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin, salt) == storedHash
    }

    fun isPatternSet(): Boolean = prefs.contains(KEY_PATTERN_HASH)

    fun setPattern(pattern: String) {
        val salt = generateSalt()
        val hash = hash(pattern, salt)
        prefs.edit()
            .putString(KEY_PATTERN_SALT, salt)
            .putString(KEY_PATTERN_HASH, hash)
            .apply()
    }

    fun verifyPattern(pattern: String): Boolean {
        val salt = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        return hash(pattern, salt) == storedHash
    }

    fun setPreferredLockMethod(method: String) {
        prefs.edit().putString(KEY_LOCK_METHOD, method).apply()
    }

    fun getPreferredLockMethod(): String =
        prefs.getString(KEY_LOCK_METHOD, LOCK_METHOD_PIN) ?: LOCK_METHOD_PIN

    // ---------- Kilitli uygulama listesi ----------

    fun getLockedApps(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet())

    fun setAppLocked(packageName: String, locked: Boolean) {
        val current = getLockedApps()
        if (locked) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(KEY_LOCKED_APPS, current).apply()
    }

    fun isAppLocked(packageName: String): Boolean =
        getLockedApps().contains(packageName)

    companion object {
        private const val PREFS_NAME = "app_lock_secure_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PATTERN_HASH = "pattern_hash"
        private const val KEY_PATTERN_SALT = "pattern_salt"
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val KEY_LOCK_METHOD = "preferred_lock_method"

        const val LOCK_METHOD_PIN = "pin"
        const val LOCK_METHOD_PATTERN = "pattern"

        private fun generateSalt(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun hash(value: String, salt: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt.toByteArray(Charsets.UTF_8))
            val hashedBytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return hashedBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
