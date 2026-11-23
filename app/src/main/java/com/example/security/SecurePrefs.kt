package com.example.security

import android.content.Context

class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dsk_locker_secure_store", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_OWNER_PHONE = "enc_owner_phone"
        private const val KEY_OWNER_EMAIL = "enc_owner_email"
        private const val KEY_MPIN_HASH = "enc_mpin_hash"
        private const val KEY_MPIN_SALT = "enc_mpin_salt"
        private const val KEY_MPIN_LENGTH = "mpin_length"
        private const val KEY_SECRET_MPIN_HASH = "enc_secret_mpin_hash"
        private const val KEY_SECRET_MPIN_SALT = "enc_secret_mpin_salt"
        private const val KEY_SECRET_MPIN_LENGTH = "secret_mpin_length"
        private const val KEY_DELETE_PROTECTION_ENABLED = "delete_protection_enabled"
        private const val KEY_FIREBASE_UID = "enc_firebase_uid"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_FAILED_ATTEMPTS = "mpin_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "mpin_lockout_until"
        private const val KEY_LAST_BACKGROUND_MS = "last_background_ms"
        private const val KEY_LAST_BACKUP_MS = "last_backup_ms"
        private const val KEY_CLOUD_OWNER_LOCKED = "cloud_owner_locked"
    }

    private fun putEncrypted(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, CryptoManager.encryptString(value)).apply()
        }
    }

    private fun getDecrypted(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return CryptoManager.decryptString(raw)
    }

    var ownerPhone: String?
        get() = getDecrypted(KEY_OWNER_PHONE)
        set(value) = putEncrypted(KEY_OWNER_PHONE, value)

    var ownerEmail: String?
        get() = getDecrypted(KEY_OWNER_EMAIL)
        set(value) = putEncrypted(KEY_OWNER_EMAIL, value)

    var firebaseUid: String?
        get() = getDecrypted(KEY_FIREBASE_UID)
        set(value) = putEncrypted(KEY_FIREBASE_UID, value)

    fun saveMpin(mpin: String) {
        val salt = CryptoManager.generateSaltBase64()
        val hash = CryptoManager.hashMpin(mpin, salt)
        putEncrypted(KEY_MPIN_SALT, salt)
        putEncrypted(KEY_MPIN_HASH, hash)
        prefs.edit().putInt(KEY_MPIN_LENGTH, mpin.length.let { if (it == 4) 4 else 6 }).apply()
        resetFailedAttempts()
    }

    fun hasMpin(): Boolean {
        return !getDecrypted(KEY_MPIN_HASH).isNullOrEmpty() && !getDecrypted(KEY_MPIN_SALT).isNullOrEmpty()
    }

    fun getMpinLength(): Int {
        val stored = prefs.getInt(KEY_MPIN_LENGTH, 6)
        return if (stored == 4) 4 else 6
    }

    fun getMpinHashForSync(): Pair<String, String>? {
        val hash = getDecrypted(KEY_MPIN_HASH) ?: return null
        val salt = getDecrypted(KEY_MPIN_SALT) ?: return null
        return hash to salt
    }

    fun verifyMpin(inputMpin: String): Boolean {
        val salt = getDecrypted(KEY_MPIN_SALT) ?: return false
        val hash = getDecrypted(KEY_MPIN_HASH) ?: return false
        return CryptoManager.verifyMpin(inputMpin, salt, hash)
    }

    // --- Separate Secret MPIN (Mandatory Delete Security) ---
    fun saveSecretMpin(secretPin: String) {
        val salt = CryptoManager.generateSaltBase64()
        val hash = CryptoManager.hashMpin(secretPin, salt)
        putEncrypted(KEY_SECRET_MPIN_SALT, salt)
        putEncrypted(KEY_SECRET_MPIN_HASH, hash)
        prefs.edit()
            .putInt(KEY_SECRET_MPIN_LENGTH, secretPin.length.let { if (it == 4) 4 else 6 })
            .putBoolean(KEY_DELETE_PROTECTION_ENABLED, true)
            .apply()
    }

    fun hasSecretMpin(): Boolean {
        return !getDecrypted(KEY_SECRET_MPIN_HASH).isNullOrEmpty() &&
            !getDecrypted(KEY_SECRET_MPIN_SALT).isNullOrEmpty()
    }

    fun getSecretMpinLength(): Int {
        val stored = prefs.getInt(KEY_SECRET_MPIN_LENGTH, 6)
        return if (stored == 4) 4 else 6
    }

    fun verifySecretMpin(inputPin: String): Boolean {
        val salt = getDecrypted(KEY_SECRET_MPIN_SALT) ?: return false
        val hash = getDecrypted(KEY_SECRET_MPIN_HASH) ?: return false
        return CryptoManager.verifyMpin(inputPin, salt, hash)
    }

    var deleteProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DELETE_PROTECTION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DELETE_PROTECTION_ENABLED, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    var lockoutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    var lastBackgroundMs: Long
        get() = prefs.getLong(KEY_LAST_BACKGROUND_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKGROUND_MS, value).apply()

    var lastBackupMs: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_MS, value).apply()

    var isCloudOwnerVerified: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_OWNER_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_OWNER_LOCKED, value).apply()

    fun getOrCreatePortableVaultKeyBase64(): String {
        val existing = getDecrypted("enc_portable_vault_key")
        if (!existing.isNullOrBlank()) return existing
        val created = CryptoManager.generatePortableAesKeyBase64()
        putEncrypted("enc_portable_vault_key", created)
        return created
    }

    fun getPortableVaultKeyBase64(): String? = getDecrypted("enc_portable_vault_key")

    fun saveRecoveredPortableVaultKeyBase64(keyBase64: String) {
        putEncrypted("enc_portable_vault_key", keyBase64)
        prefs.edit().putBoolean("portable_vault_key_wrapped_cloud", true).apply()
    }

    var isPortableKeyWrappedInCloud: Boolean
        get() = prefs.getBoolean("portable_vault_key_wrapped_cloud", false)
        set(value) = prefs.edit().putBoolean("portable_vault_key_wrapped_cloud", value).apply()

    fun setFileKeyVersion(fileId: String, version: Int) {
        prefs.edit().putInt("vault_kv_$fileId", version).apply()
    }

    fun getFileKeyVersion(fileId: String): Int = prefs.getInt("vault_kv_$fileId", 0)

    fun recordFailedAttempt(): Long {
        val attempts = failedAttempts + 1
        failedAttempts = attempts
        if (attempts >= 5) {
            val penaltySeconds = when {
                attempts >= 10 -> 120L
                attempts >= 7 -> 60L
                else -> 30L
            }
            val until = System.currentTimeMillis() + penaltySeconds * 1000L
            lockoutUntilMs = until
            return penaltySeconds
        }
        return 0L
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun remainingLockoutSeconds(): Long {
        val diff = lockoutUntilMs - System.currentTimeMillis()
        return if (diff > 0) (diff + 999L) / 1000L else 0L
    }
}
