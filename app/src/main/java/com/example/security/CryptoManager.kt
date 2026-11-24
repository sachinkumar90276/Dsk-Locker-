package com.example.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object CryptoManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsk_locker_master_aes_key_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS = 256

    @Volatile
    private var jvmTestFallbackKey: SecretKey? = null

    private fun isAndroidKeyStoreAvailable(): Boolean {
        val isRobolectric = Build.FINGERPRINT?.equals("robolectric", ignoreCase = true) == true
        if (isRobolectric) return false
        return Security.getProvider(ANDROID_KEYSTORE) != null
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!isAndroidKeyStoreAvailable()) {
            return jvmTestFallbackKey ?: synchronized(this) {
                jvmTestFallbackKey ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                    .also { jvmTestFallbackKey = it }
            }
        }

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                if (existing != null) return existing

                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            } catch (t: Throwable) {
                lastError = t
                if (attempt == 0) {
                    runCatching { Thread.sleep(50L) }
                }
            }
        }
        throw IllegalStateException("AndroidKeyStore master key unavailable", lastError)
    }

    fun encryptString(plainText: String): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptString(cipherTextBase64: String): String? {
        return try {
            val combined = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            if (combined.size < 2) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val encrypted = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun generatePortableAesKeyBase64(): String {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    fun secretKeyFromBase64(keyBase64: String): SecretKey {
        val raw = Base64.decode(keyBase64, Base64.NO_WRAP)
        require(raw.size == 16 || raw.size == 24 || raw.size == 32) { "Invalid AES key length." }
        return javax.crypto.spec.SecretKeySpec(raw, "AES")
    }

    fun encryptStreamWithKey(key: SecretKey, input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        output.write(iv.size)
        output.write(iv)
        CipherOutputStream(output, cipher).use { cos ->
            input.copyTo(cos, bufferSize = 16 * 1024)
        }
    }

    fun decryptStreamWithKey(key: SecretKey, input: InputStream, output: OutputStream) {
        val ivLen = input.read()
        require(ivLen in 8..32) { "Invalid encrypted vault header." }
        val iv = ByteArray(ivLen)
        var readTotal = 0
        while (readTotal < ivLen) {
            val n = input.read(iv, readTotal, ivLen - readTotal)
            if (n <= 0) throw IllegalStateException("Truncated vault IV.")
            readTotal += n
        }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        CipherInputStream(input, cipher).use { cis ->
            cis.copyTo(output, bufferSize = 16 * 1024)
        }
    }

    fun encryptStream(input: InputStream, output: OutputStream) {
        encryptStreamWithKey(getOrCreateMasterKey(), input, output)
    }

    fun decryptStream(input: InputStream, output: OutputStream) {
        decryptStreamWithKey(getOrCreateMasterKey(), input, output)
    }

    fun generateSaltBase64(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun hashMpin(mpin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(mpin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verifyMpin(mpin: String, saltBase64: String, expectedHashBase64: String): Boolean {
        val actual = Base64.decode(hashMpin(mpin, saltBase64), Base64.NO_WRAP)
        val expected = Base64.decode(expectedHashBase64, Base64.NO_WRAP)
        return MessageDigest.isEqual(actual, expected)
    }

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
