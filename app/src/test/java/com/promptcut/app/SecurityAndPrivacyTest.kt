package com.promptcut.app

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.PrivacyManager
import com.promptcut.app.security.SoftwareAesGcmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurityAndPrivacyTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var vault: KeyVault

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = ctx.getSharedPreferences("sec_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        vault = KeyVault(SoftwareAesGcmEngine())
    }

    @Test
    fun keyVault_encryptsAndMigratesWithoutPlaintextFallback() {
        val secret = "AIzaSyTestExampleKey1234567890"
        val writeRes = vault.writeKey(prefs, "key_GEMINI", secret)
        assertTrue(writeRes.isSuccess)

        val storedRaw = prefs.getString("key_GEMINI", "") ?: ""
        assertTrue(storedRaw.startsWith(KeyVault.PREFIX_V2))
        assertFalse(storedRaw.contains(secret))

        val readRes = vault.readAndMigrate(prefs, "key_GEMINI")
        assertEquals(secret, readRes.getOrNull())
    }

    @Test
    fun keyVault_refusesPlaintextFallbackWhenCipherEngineFails() {
        val brokenVault = KeyVault(object : KeyVault.CipherEngine {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException("Keystore locked")
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalStateException("Keystore locked")
        })
        val res = brokenVault.writeKey(prefs, "key_GROQ", "gsk_secret_token_987654321")
        assertTrue(res is AppResult.Failure)
        assertFalse(prefs.contains("key_GROQ"))
    }

    @Test
    fun keyVault_migratesLegacyPlaintextAndEnc1ToEnc2() {
        val legacyPlain = "gsk_legacy_plain_1234567890"
        prefs.edit().putString("key_GROQ", legacyPlain).commit()

        val migrated = vault.readAndMigrate(prefs, "key_GROQ")
        assertEquals(legacyPlain, migrated.getOrNull())
        val upgradedRaw = prefs.getString("key_GROQ", "") ?: ""
        assertTrue(upgradedRaw.startsWith(KeyVault.PREFIX_V2))
        assertNotEquals(legacyPlain, upgradedRaw)

        // Simulate legacy enc1: value encrypted under same AES-GCM engine
        val v2 = upgradedRaw.removePrefix(KeyVault.PREFIX_V2)
        prefs.edit().putString("key_GROQ", KeyVault.PREFIX_V1 + v2).commit()
        val migratedFromV1 = vault.readAndMigrate(prefs, "key_GROQ")
        assertEquals(legacyPlain, migratedFromV1.getOrNull())
        assertTrue((prefs.getString("key_GROQ", "") ?: "").startsWith(KeyVault.PREFIX_V2))
    }

    @Test
    fun keyVault_masksAndRedactsSecretsInLogs() {
        val k = "sk-ant-api03-secretvalue123456789"
        val masked = KeyVault.maskKey(k)
        assertEquals("sk-a••••6789", masked)

        val rawLog = "Request failed with AIzaSyAbCdEfGhIjKlMnOpQrStUvWx and gsk_1234567890abcdef123456 and $k"
        val redacted = KeyVault.redactSecrets(rawLog, listOf(k))
        assertFalse(redacted.contains("AIzaSyAbCdEfGhIjKlMnOpQrStUvWx"))
        assertFalse(redacted.contains("gsk_1234567890abcdef123456"))
        assertFalse(redacted.contains("secretvalue123456789"))
    }

    @Test
    fun privacyManager_blocksFullVideoUploadAndUnconsentedClaude() {
        val pm = PrivacyManager(prefs)

        val fullVideoRes = pm.validateRequest(ProviderId.GEMINI, frameCount = 4, includesFullVideo = true)
        assertTrue(fullVideoRes is AppResult.Failure)
        assertTrue((fullVideoRes as AppResult.Failure).error is AppError.PrivacyBlocked)

        val claudeNoConsent = pm.validateRequest(ProviderId.CLAUDE, frameCount = 2)
        assertTrue(claudeNoConsent is AppResult.Failure)
        assertTrue((claudeNoConsent as AppResult.Failure).error is AppError.PaidConsentRequired)

        pm.claudePaidConsentGranted = true
        pm.allowFrameSamplingToAi = false
        val claudeOk = pm.validateRequest(ProviderId.CLAUDE, frameCount = 5)
        assertTrue(claudeOk is AppResult.Success)
        assertEquals(0, (claudeOk as AppResult.Success).value.sendsSampledFrames)

        pm.allowAudioToGroqStt = false
        val sttBlocked = pm.validateRequest(ProviderId.GROQ, frameCount = 0, isSpeechToText = true)
        assertTrue(sttBlocked is AppResult.Failure)
    }

    @Test
    fun privacyManager_freshInstallDefaultsOptInFalseAndPreservesSavedUpgradePrefs() {
        val freshPm = PrivacyManager(prefs)
        assertFalse(freshPm.allowFrameSamplingToAi)
        assertFalse(freshPm.allowAudioToGroqStt)
        assertFalse(freshPm.allowFullVideoUpload)
        assertFalse(freshPm.claudePaidConsentGranted)

        val framesAudit = freshPm.validateRequest(ProviderId.GEMINI, frameCount = 6).getOrNull()!!
        assertEquals(0, framesAudit.sendsSampledFrames)

        val sttDefaultBlocked = freshPm.validateRequest(ProviderId.GROQ, frameCount = 0, isSpeechToText = true)
        assertTrue(sttDefaultBlocked is AppResult.Failure)
        assertTrue((sttDefaultBlocked as AppResult.Failure).error is AppError.PrivacyBlocked)

        // Simulate upgrade where existing user had previously opted into frames & STT
        prefs.edit()
            .putBoolean("privacy_allow_frames", true)
            .putBoolean("privacy_allow_stt", true)
            .commit()
        val upgradedPm = PrivacyManager(prefs)
        assertTrue(upgradedPm.allowFrameSamplingToAi)
        assertTrue(upgradedPm.allowAudioToGroqStt)
        assertEquals(6, upgradedPm.validateRequest(ProviderId.GEMINI, frameCount = 6).getOrNull()!!.sendsSampledFrames)
        assertTrue(upgradedPm.validateRequest(ProviderId.GROQ, frameCount = 0, isSpeechToText = true) is AppResult.Success)
    }

    @Test
    fun musicLibrary_rejectsNonHttpsAndSanitizesTrackFilenames() {
        val badHttp = runCatching { MusicLibrary.validateHttpsUrl("http://example.com/song.mp3") }
        assertTrue(badHttp.isFailure)
        val badFile = runCatching { MusicLibrary.validateHttpsUrl("file:///etc/passwd") }
        assertTrue(badFile.isFailure)
        val okHttps = MusicLibrary.validateHttpsUrl("https://cdn.openverse.org/audio/track.mp3")
        assertEquals("https", okHttps.protocol)

        val safeName = MusicLibrary.safeCacheFileName("../../evil/track?name=1")
        assertFalse(safeName.contains(".."))
        assertFalse(safeName.contains("/"))
        assertTrue(safeName.endsWith(".audio"))
    }
}
