package com.promptcut.app.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM encryption for API keys.
 *
 * Security invariants:
 * - Zero plaintext fallback: if encryption fails, nothing is written in plaintext.
 * - Safe legacy migration: reads `enc1:` or legacy unencrypted entries, re-encrypts with `enc2:`,
 *   verifies round-trip, and overwrites/removes legacy storage.
 * - Redaction helpers keep API keys out of logs, crash reports, and UI errors.
 */
class KeyVault(
    private val cipherEngine: CipherEngine = AndroidKeystoreCipherEngine(),
) {
    interface CipherEngine {
        fun encrypt(plaintext: ByteArray): ByteArray
        fun decrypt(ciphertext: ByteArray): ByteArray
    }

    fun encryptString(plain: String): AppResult<String> {
        val trimmed = plain.trim()
        if (trimmed.isEmpty()) return AppResult.Success("")
        return try {
            val encrypted = cipherEngine.encrypt(trimmed.toByteArray(Charsets.UTF_8))
            val encoded = PREFIX_V2 + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            // Verify round-trip before returning so corrupted keystore state never loses a key silently.
            val check = decryptString(encoded)
            if (check is AppResult.Success && check.value == trimmed) {
                AppResult.Success(encoded)
            } else {
                AppResult.Failure(AppError.Security("Key verification failed after encryption."))
            }
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Security("Failed to encrypt API key with Android Keystore.", t.message.orEmpty(), t))
        }
    }

    fun decryptString(stored: String): AppResult<String> {
        if (stored.isEmpty()) return AppResult.Success("")
        return try {
            when {
                stored.startsWith(PREFIX_V2) -> {
                    val raw = Base64.decode(stored.removePrefix(PREFIX_V2), Base64.NO_WRAP)
                    val plain = cipherEngine.decrypt(raw).toString(Charsets.UTF_8)
                    AppResult.Success(plain)
                }
                stored.startsWith(PREFIX_V1) -> {
                    // Legacy v1 encrypted with the same alias/IV layout: decrypt and allow caller to upgrade to v2.
                    val raw = Base64.decode(stored.removePrefix(PREFIX_V1), Base64.NO_WRAP)
                    val plain = cipherEngine.decrypt(raw).toString(Charsets.UTF_8)
                    AppResult.Success(plain)
                }
                else -> AppResult.Failure(
                    AppError.Security("Legacy unencrypted credential detected; migration required.")
                )
            }
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Security("Could not decrypt stored API key.", t.message.orEmpty(), t))
        }
    }

    /**
     * Safely reads a stored key from [prefs] under [prefKey], migrating legacy `enc1:` or
     * plaintext values into `enc2:` encrypted storage. Never leaves plaintext behind.
     */
    fun readAndMigrate(prefs: SharedPreferences, prefKey: String): AppResult<String> {
        val raw = prefs.getString(prefKey, "") ?: ""
        if (raw.isEmpty()) return AppResult.Success("")

        if (raw.startsWith(PREFIX_V2)) {
            return decryptString(raw)
        }

        val plaintext: String = if (raw.startsWith(PREFIX_V1)) {
            when (val dec = decryptString(raw)) {
                is AppResult.Success -> dec.value
                is AppResult.Failure -> return dec
            }
        } else {
            // Legacy unencrypted key from very old builds: migrate immediately or wipe if empty.
            raw.trim()
        }

        if (plaintext.isEmpty()) {
            prefs.edit().remove(prefKey).commit()
            return AppResult.Success("")
        }

        return when (val upgraded = encryptString(plaintext)) {
            is AppResult.Success -> {
                val saved = prefs.edit().putString(prefKey, upgraded.value).commit()
                if (saved) AppResult.Success(plaintext)
                else AppResult.Failure(AppError.Security("Could not commit migrated key to storage."))
            }
            is AppResult.Failure -> upgraded
        }
    }

    /**
     * Encrypts and persists [plainKey] to [prefs]. Never writes plaintext on failure.
     */
    fun writeKey(prefs: SharedPreferences, prefKey: String, plainKey: String): AppResult<Unit> {
        val trimmed = plainKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(prefKey).commit()
            return AppResult.Success(Unit)
        }
        return when (val enc = encryptString(trimmed)) {
            is AppResult.Success -> {
                val ok = prefs.edit().putString(prefKey, enc.value).commit()
                if (ok) AppResult.Success(Unit)
                else AppResult.Failure(AppError.Security("Failed to save encrypted key."))
            }
            is AppResult.Failure -> enc
        }
    }

    companion object {
        const val PREFIX_V1 = "enc1:"
        const val PREFIX_V2 = "enc2:"
        private const val ALIAS = "promptcut_api_keys"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128

        fun maskKey(key: String): String {
            val k = key.trim()
            if (k.length <= 8) return if (k.isEmpty()) "" else "••••"
            return "${k.take(4)}••••${k.takeLast(4)}"
        }

        /** Scrubs known API key formats, header/query credentials, and explicit secrets from logs or error messages. */
        fun redactSecrets(text: String, knownKeys: Collection<String> = emptyList()): String {
            var out = text
            knownKeys.filter { it.length >= 6 }.forEach { k ->
                out = out.replace(k, maskKey(k))
            }
            out = out.replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "AIza••••")
            out = out.replace(Regex("sk-ant-[0-9A-Za-z_-]{16,}"), "sk-ant-••••")
            out = out.replace(Regex("gsk_[0-9A-Za-z_-]{16,}"), "gsk_••••")
            out = out.replace(Regex("sk-[0-9A-Za-z_-]{16,}"), "sk-••••")
            out = out.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{12,}"), "$1••••")
            out = out.replace(Regex("(?i)((?:x-api-key|x-goog-api-key|api_key|key)\\s*[:=]\\s*)[^\\s,;\"'&]+"), "$1••••")
            return out
        }
    }

    class AndroidKeystoreCipherEngine : CipherEngine {
        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val sealed = cipher.doFinal(plaintext)
            return iv + sealed
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size > GCM_IV_LEN) { "Malformed encrypted payload" }
            val iv = ciphertext.copyOfRange(0, GCM_IV_LEN)
            val data = ciphertext.copyOfRange(GCM_IV_LEN, ciphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(data)
        }
    }
}

/** In-memory AES-GCM cipher engine for JVM unit tests where AndroidKeyStore is absent. */
class SoftwareAesGcmEngine : KeyVault.CipherEngine {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val rng = SecureRandom()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 12) { "Malformed encrypted payload" }
        val iv = ciphertext.copyOfRange(0, 12)
        val data = ciphertext.copyOfRange(12, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
