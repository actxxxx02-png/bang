package com.promptcut.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.ai.AiCapabilities
import com.promptcut.app.ai.AiProvider
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiUsage
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.ApiKeyManager
import com.promptcut.app.security.KeyHealthStatus
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.SoftwareAesGcmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiKeyManagerTest {

    private lateinit var context: Context
    private var fakeNowMs: Long = 1_700_000_000_000L

    private class RecordingFakeProvider(
        override val id: ProviderId,
        override val capabilities: AiCapabilities = AiCapabilities(
            supportsVision = id != ProviderId.GROQ,
            supportsStreaming = true,
            supportsToolCalling = true,
            supportsJsonMode = true,
            maxFrames = 12,
            isFreeTierAvailable = id != ProviderId.CLAUDE,
            requiresPaidConsent = id == ProviderId.CLAUDE,
            contextWindowTokens = 64_000,
        ),
        var testHandler: suspend (String) -> AppResult<String> = { AppResult.Success("${id.shortName}-ok-model") },
        var completeHandler: suspend (String, AiRequest) -> AppResult<AiResponse> = { _, _ ->
            AppResult.Success(AiResponse("done", "${id.shortName}-ok-model", id, usage = AiUsage(12, 6)))
        },
        var streamHandler: (String, AiRequest) -> Flow<AiStreamEvent> = { _, _ ->
            flow {
                emit(AiStreamEvent.Delta("hello "))
                emit(AiStreamEvent.Completed(AiResponse("hello ", "${id.shortName}-ok-model", id)))
            }
        },
    ) : AiProvider {
        val completeAttemptedKeys = mutableListOf<String>()
        val testAttemptedKeys = mutableListOf<String>()
        val concurrentTests = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        override suspend fun listModels(apiKey: String) = AppResult.Success(listOf("${id.shortName}-ok-model"))

        override suspend fun testConnection(apiKey: String, preferredModel: String?): AppResult<String> {
            testAttemptedKeys += apiKey
            val cur = concurrentTests.incrementAndGet()
            maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, cur) }
            return try {
                kotlinx.coroutines.delay(25L)
                testHandler(apiKey)
            } finally {
                concurrentTests.decrementAndGet()
            }
        }

        override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> {
            completeAttemptedKeys += apiKey
            return completeHandler(apiKey, request)
        }

        override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> =
            streamHandler(apiKey, request)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("promptcut", Context.MODE_PRIVATE).edit().clear().commit()
        fakeNowMs = 1_700_000_000_000L
    }

    private fun newSettings(vault: KeyVault = KeyVault(SoftwareAesGcmEngine())) = Settings(context, vault)

    @Test
    fun multipleKeys_crudEncryptionMaskedAndUntestedInitially() {
        val settings = newSettings()
        val km = settings.keyManager

        val k1 = km.addKey(ProviderId.GEMINI, "Primary Free", "AIzaSyPrimarySecret123456").getOrNull()!!
        val k2 = km.addKey(ProviderId.GEMINI, "Backup Free", "AIzaSyBackupSecret987654").getOrNull()!!
        val k3 = km.addKey(ProviderId.GEMINI, "Third Free", "AIzaSyThirdSecret555555").getOrNull()!!

        // Newly added keys must ALWAYS begin UNTESTED, never pre-marked WORKING
        assertEquals(KeyHealthStatus.UNTESTED, k1.status)
        assertEquals(KeyHealthStatus.UNTESTED, k2.status)
        assertEquals(KeyHealthStatus.UNTESTED, k3.status)
        assertTrue(k1.isPreferred)
        assertFalse(k2.isPreferred)

        // Zero plaintext storage & always masked
        val rawPrefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)
            .getString(ApiKeyManager.PREF_KEYS_V2, "")!!
        assertFalse(rawPrefs.contains("AIzaSyPrimarySecret123456"))
        assertFalse(rawPrefs.contains("AIzaSyBackupSecret987654"))
        assertTrue(k1.encryptedKey.startsWith(KeyVault.PREFIX_V2))
        assertEquals(KeyVault.maskKey("AIzaSyPrimarySecret123456"), k1.maskedKey)
        assertFalse(k1.maskedKey.contains("PrimarySecret"))

        // Rename, enable/disable, setPreferred, delete promotes next eligible key
        km.renameKey(k2.id, "Backup Renamed")
        assertEquals("Backup Renamed", km.findKey(k2.id)?.name)

        km.setEnabled(k3.id, false)
        assertEquals(KeyHealthStatus.DISABLED, km.findKey(k3.id)?.effectiveStatus(fakeNowMs))

        km.setPreferred(k2.id)
        assertTrue(km.findKey(k2.id)!!.isPreferred)
        assertFalse(km.findKey(k1.id)!!.isPreferred)

        val summary = km.summaryFor(ProviderId.GEMINI, fakeNowMs)
        assertEquals(3, summary.totalCount)
        assertEquals(2, summary.untestedCount)
        assertEquals(1, summary.disabledCount)
        assertEquals(0, summary.workingCount)

        km.deleteKey(k2.id)
        assertNull(km.findKey(k2.id))
        assertTrue(km.findKey(k1.id)!!.isPreferred)
    }

    @Test
    fun rotation_invalidAuthRotatesWithinSameProviderBeforeSwitchingProviders() = runTest {
        val settings = newSettings()
        val gemini = RecordingFakeProvider(ProviderId.GEMINI)
        val groq = RecordingFakeProvider(ProviderId.GROQ)
        val claude = RecordingFakeProvider(ProviderId.CLAUDE)
        val router = ProviderRouter(
            settings = settings,
            providers = mapOf(ProviderId.GEMINI to gemini, ProviderId.GROQ to groq, ProviderId.CLAUDE to claude),
            nowMsProvider = { fakeNowMs },
            retryBackoffBaseMs = 0L,
        )

        val g1 = settings.keyManager.addKey(ProviderId.GEMINI, "G1", "AIzaSyGeminiBad11111111", setPreferred = true).getOrNull()!!
        val g2 = settings.keyManager.addKey(ProviderId.GEMINI, "G2", "AIzaSyGeminiGood2222222").getOrNull()!!
        settings.keyManager.addKey(ProviderId.GROQ, "Groq1", "gsk_GroqKey1111111111111")

        gemini.completeHandler = { key, _ ->
            if (key == "AIzaSyGeminiBad11111111") {
                AppResult.Failure(AppError.Auth("Gemini", 400, "API key not valid"))
            } else {
                AppResult.Success(AiResponse("rotated-gemini-ok", "gemini-2.5-flash", ProviderId.GEMINI))
            }
        }

        val res = router.complete(AiRequest("sys", "analyze video"), needsVision = true)
        assertTrue(res is AppResult.Success)
        assertEquals("rotated-gemini-ok", (res as AppResult.Success).value.text)
        // Same-provider rotation happened BEFORE Groq was ever touched
        assertEquals(listOf("AIzaSyGeminiBad11111111", "AIzaSyGeminiGood2222222"), gemini.completeAttemptedKeys)
        assertEquals(0, groq.completeAttemptedKeys.size)
        assertEquals(0, claude.completeAttemptedKeys.size)

        assertEquals(KeyHealthStatus.INVALID, settings.keyManager.findKey(g1.id)?.status)
        assertEquals(KeyHealthStatus.WORKING, settings.keyManager.findKey(g2.id)?.status)
        assertEquals(fakeNowMs, settings.keyManager.findKey(g2.id)?.lastUsedAtMs)
    }

    @Test
    fun rotation_rateLimitAndQuotaCooldownExpireProperly() = runTest {
        val settings = newSettings()
        val groq = RecordingFakeProvider(ProviderId.GROQ)
        val gemini = RecordingFakeProvider(ProviderId.GEMINI)
        val claude = RecordingFakeProvider(ProviderId.CLAUDE)
        val router = ProviderRouter(
            settings = settings,
            providers = mapOf(ProviderId.GEMINI to gemini, ProviderId.GROQ to groq, ProviderId.CLAUDE to claude),
            nowMsProvider = { fakeNowMs },
            retryBackoffBaseMs = 0L,
        )

        val q1 = settings.keyManager.addKey(ProviderId.GROQ, "Groq1", "gsk_GroqKeyOne1111111111", setPreferred = true).getOrNull()!!
        val q2 = settings.keyManager.addKey(ProviderId.GROQ, "Groq2", "gsk_GroqKeyTwo2222222222").getOrNull()!!
        val q3 = settings.keyManager.addKey(ProviderId.GROQ, "Groq3", "gsk_GroqKeyThree33333333").getOrNull()!!

        groq.completeHandler = { key, _ ->
            when (key) {
                "gsk_GroqKeyOne1111111111" -> AppResult.Failure(AppError.RateLimit("Groq", 429, "Rate limit reached"))
                "gsk_GroqKeyTwo2222222222" -> AppResult.Failure(AppError.QuotaExhausted("Groq", 429, "Insufficient quota"))
                else -> AppResult.Success(AiResponse("q3-ok", "llama-3.3-70b-versatile", ProviderId.GROQ))
            }
        }

        val res = router.complete(AiRequest("sys", "plan edits"), needsVision = false)
        assertTrue(res is AppResult.Success)
        assertEquals("q3-ok", (res as AppResult.Success).value.text)

        assertEquals(KeyHealthStatus.RATE_LIMITED, settings.keyManager.findKey(q1.id)?.effectiveStatus(fakeNowMs))
        assertEquals(KeyHealthStatus.QUOTA_EXHAUSTED, settings.keyManager.findKey(q2.id)?.effectiveStatus(fakeNowMs))
        assertEquals(KeyHealthStatus.WORKING, settings.keyManager.findKey(q3.id)?.effectiveStatus(fakeNowMs))

        val summary = settings.keyManager.summaryFor(ProviderId.GROQ, fakeNowMs)
        assertEquals(1, summary.rateLimitedCount)
        assertEquals(1, summary.quotaExhaustedCount)
        assertEquals(1, summary.workingCount)

        // Advance time past 60s rate-limit cooldown: q1 becomes eligible again, q2 remains quota-exhausted (15m)
        fakeNowMs += ApiKeyManager.RATE_LIMIT_COOLDOWN_MS + 1_000L
        assertTrue(settings.keyManager.findKey(q1.id)!!.isEligibleForRouting(fakeNowMs))
        assertFalse(settings.keyManager.findKey(q2.id)!!.isEligibleForRouting(fakeNowMs))
    }

    @Test
    fun server5xxRetriesBoundedAndNetworkNeverInvalidatesKey() = runTest {
        val settings = newSettings()
        val groq = RecordingFakeProvider(ProviderId.GROQ)
        val gemini = RecordingFakeProvider(ProviderId.GEMINI)
        val router = ProviderRouter(
            settings = settings,
            providers = mapOf(
                ProviderId.GEMINI to gemini,
                ProviderId.GROQ to groq,
                ProviderId.CLAUDE to RecordingFakeProvider(ProviderId.CLAUDE),
            ),
            nowMsProvider = { fakeNowMs },
            retryBackoffBaseMs = 0L,
        )

        val q1 = settings.keyManager.addKey(ProviderId.GROQ, "Q1", "gsk_GroqTransient1111111", setPreferred = true).getOrNull()!!
        val q2 = settings.keyManager.addKey(ProviderId.GROQ, "Q2", "gsk_GroqBackup2222222222").getOrNull()!!

        // Q1 returns 503 three times (initial + 2 bounded retries), then rotates once to Q2
        groq.completeHandler = { key, _ ->
            if (key == "gsk_GroqTransient1111111") {
                AppResult.Failure(AppError.ProviderUnavailable("Groq", 503))
            } else {
                AppResult.Success(AiResponse("recovered-on-q2", "llama-3.3-70b-versatile", ProviderId.GROQ))
            }
        }
        val r5xx = router.complete(AiRequest("sys", "edit"), needsVision = false)
        assertTrue(r5xx is AppResult.Success)
        // Exactly 3 attempts on Q1 (initial + MAX_5XX_RETRIES=2) and 1 on Q2 — no infinite loop or duplicates
        assertEquals(3, groq.completeAttemptedKeys.count { it == "gsk_GroqTransient1111111" })
        assertEquals(1, groq.completeAttemptedKeys.count { it == "gsk_GroqBackup2222222222" })
        // Q1 must still NOT be marked INVALID after transient 503
        assertFalse(settings.keyManager.findKey(q1.id)?.status == KeyHealthStatus.INVALID)

        // Network failure must NOT mark Q2 INVALID either and stops cleanly without burning Q1
        groq.completeAttemptedKeys.clear()
        groq.completeHandler = { _, _ -> AppResult.Failure(AppError.Network("No internet")) }
        val rNet = router.complete(AiRequest("sys", "edit"), needsVision = false)
        assertTrue(rNet is AppResult.Failure)
        assertTrue((rNet as AppResult.Failure).error is AppError.Network)
        assertEquals(1, groq.completeAttemptedKeys.size)
        assertEquals(KeyHealthStatus.WORKING, settings.keyManager.findKey(q2.id)?.status)
    }

    @Test
    fun streamRotationBeforeDeltaAndNoSilentClaudeFallback() = runTest {
        val settings = newSettings()
        val groq = RecordingFakeProvider(ProviderId.GROQ)
        val gemini = RecordingFakeProvider(ProviderId.GEMINI)
        val claude = RecordingFakeProvider(ProviderId.CLAUDE)
        val router = ProviderRouter(
            settings = settings,
            providers = mapOf(ProviderId.GEMINI to gemini, ProviderId.GROQ to groq, ProviderId.CLAUDE to claude),
            nowMsProvider = { fakeNowMs },
            retryBackoffBaseMs = 0L,
        )

        settings.keyManager.addKey(ProviderId.GROQ, "BadGroq", "gsk_GroqBadStream11111111", setPreferred = true)
        settings.keyManager.addKey(ProviderId.CLAUDE, "PaidClaude", "sk-ant-ClaudeSecret123456")
        settings.privacy.claudePaidConsentGranted = true

        groq.streamHandler = { _, _ -> flow { emit(AiStreamEvent.Error(AppError.Auth("Groq", 401))) } }
        val events = router.stream(AiRequest("sys", "cut"), needsVision = false).toList()
        assertTrue(events.last() is AiStreamEvent.Error)
        assertEquals(0, claude.completeAttemptedKeys.size)
    }

    @Test
    fun testSavedKeyAndTestAllEnabledKeys_respectClaudeConsentConcurrencyAndDisabledKeys() = runTest {
        val settings = newSettings()
        val gemini = RecordingFakeProvider(ProviderId.GEMINI)
        val groq = RecordingFakeProvider(ProviderId.GROQ)
        val claude = RecordingFakeProvider(ProviderId.CLAUDE)
        val router = ProviderRouter(
            settings = settings,
            providers = mapOf(ProviderId.GEMINI to gemini, ProviderId.GROQ to groq, ProviderId.CLAUDE to claude),
            nowMsProvider = { fakeNowMs },
            retryBackoffBaseMs = 0L,
        )

        val g1 = settings.keyManager.addKey(ProviderId.GEMINI, "G1", "AIzaSyValidTest111111111").getOrNull()!!
        val g2 = settings.keyManager.addKey(ProviderId.GEMINI, "G2", "AIzaSyInvalidTest2222222").getOrNull()!!
        val g3Disabled = settings.keyManager.addKey(ProviderId.GEMINI, "G3", "AIzaSyDisabledTest333333").getOrNull()!!
        settings.keyManager.setEnabled(g3Disabled.id, false)
        val q1 = settings.keyManager.addKey(ProviderId.GROQ, "Q1", "gsk_ValidGroqTest1111111").getOrNull()!!
        val c1 = settings.keyManager.addKey(ProviderId.CLAUDE, "C1", "sk-ant-ClaudeTest11111111").getOrNull()!!

        gemini.testHandler = { key ->
            if (key.contains("Invalid")) AppResult.Failure(AppError.Auth("Gemini", 400, "API key not valid"))
            else AppResult.Success("gemini-2.5-flash")
        }

        val results = router.testAllEnabledKeys(concurrency = 2)
        // Disabled key skipped; Claude blocked without consent; g1 & q1 working; g2 invalid
        assertFalse(results.containsKey(g3Disabled.id))
        assertTrue(results[g1.id] is AppResult.Success)
        assertTrue(results[q1.id] is AppResult.Success)
        assertTrue((results[g2.id] as AppResult.Failure).error is AppError.Auth)
        assertTrue((results[c1.id] as AppResult.Failure).error is AppError.PaidConsentRequired)
        assertEquals(0, claude.testAttemptedKeys.size)
        assertTrue(gemini.maxObservedConcurrency.get() <= 2)

        val g1Rec = settings.keyManager.findKey(g1.id)!!
        assertEquals(KeyHealthStatus.WORKING, g1Rec.status)
        assertEquals(fakeNowMs, g1Rec.lastSuccessfulTestAtMs)
        assertTrue(g1Rec.quotaNote.orEmpty().contains("not exposed", ignoreCase = true))
    }

    @Test
    fun groqWhisperTranscription_blockedByDefaultWithoutOptIn() = runTest {
        val settings = newSettings()
        val router = ProviderRouter(settings, nowMsProvider = { fakeNowMs }, retryBackoffBaseMs = 0L)
        settings.keyManager.addKey(ProviderId.GROQ, "Q1", "gsk_SttGoodKey1111111111", setPreferred = true)

        val dummyAudio = File(context.cacheDir, "dummy_blocked.m4a").apply { writeText("audio") }
        var called = false
        val res = router.transcribeAudioWithGroq(dummyAudio) { _, _ ->
            called = true
            emptyList()
        }
        assertFalse(called)
        assertTrue(res is AppResult.Failure)
        assertTrue((res as AppResult.Failure).error is AppError.PrivacyBlocked)
        dummyAudio.delete()
    }

    @Test
    fun groqWhisperTranscription_rotatesAcrossGroqKeys() = runTest {
        val settings = newSettings()
        settings.privacy.allowAudioToGroqStt = true
        val router = ProviderRouter(settings, nowMsProvider = { fakeNowMs }, retryBackoffBaseMs = 0L)
        val q1 = settings.keyManager.addKey(ProviderId.GROQ, "Q1", "gsk_SttFailKey1111111111", setPreferred = true).getOrNull()!!
        val q2 = settings.keyManager.addKey(ProviderId.GROQ, "Q2", "gsk_SttGoodKey2222222222").getOrNull()!!

        val dummyAudio = File(context.cacheDir, "dummy.m4a").apply { writeText("audio") }
        val attempted = mutableListOf<String>()
        val res = router.transcribeAudioWithGroq(dummyAudio) { apiKey, _ ->
            attempted += apiKey
            if (apiKey == "gsk_SttFailKey1111111111") throw ApiException("Rate limit", 429, "rate_limit_exceeded")
            listOf(Span(0.0, 1.0, "hello world"))
        }

        assertTrue(res is AppResult.Success)
        assertEquals(listOf("gsk_SttFailKey1111111111", "gsk_SttGoodKey2222222222"), attempted)
        assertEquals(KeyHealthStatus.RATE_LIMITED, settings.keyManager.findKey(q1.id)?.effectiveStatus(fakeNowMs))
        assertEquals(fakeNowMs, settings.keyManager.findKey(q2.id)?.lastUsedAtMs)
        dummyAudio.delete()
    }

    @Test
    fun legacyMigration_migratesAllThreeKeysPreservesModelsAndConsentAndHandlesFailuresSafely() {
        val vault = KeyVault(SoftwareAesGcmEngine())
        val prefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("key_GEMINI", "AIzaSyLegacyGeminiKey123456")
            .putString("key_GROQ", vault.encryptString("gsk_LegacyGroqKey654321").getOrNull()!!)
            .putString("key_CLAUDE", "sk-ant-LegacyClaudeKey999999")
            .putString("model_GEMINI", "gemini-2.5-pro")
            .putBoolean("privacy_claude_paid_consent", true)
            .commit()

        val settings = Settings(context, vault)
        assertNull(settings.keyManager.migrationError)

        val gemKeys = settings.keyManager.keysFor(ProviderId.GEMINI)
        val groqKeys = settings.keyManager.keysFor(ProviderId.GROQ)
        val claudeKeys = settings.keyManager.keysFor(ProviderId.CLAUDE)
        assertEquals(1, gemKeys.size)
        assertEquals(1, groqKeys.size)
        assertEquals(1, claudeKeys.size)

        // Migrated keys start UNTESTED, preserve model prefs and Claude paid consent
        assertEquals(KeyHealthStatus.UNTESTED, gemKeys.first().status)
        assertEquals("gemini-2.5-pro", gemKeys.first().activeModel)
        assertTrue(settings.privacy.claudePaidConsentGranted)
        assertEquals("AIzaSyLegacyGeminiKey123456", settings.keyFor(ProviderId.GEMINI))
        assertEquals("gsk_LegacyGroqKey654321", settings.keyFor(ProviderId.GROQ))
        assertEquals("sk-ant-LegacyClaudeKey999999", settings.keyFor(ProviderId.CLAUDE))

        // Legacy plaintext/single prefs must be removed after safe migration
        assertEquals("", prefs.getString("key_GEMINI", ""))
        assertEquals("", prefs.getString("key_GROQ", ""))
        assertEquals("", prefs.getString("key_CLAUDE", ""))
    }

    @Test
    fun legacyMigration_failurePreservesRecoverableEntriesAndReportsError() {
        val prefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)
        prefs.edit().putString("key_GEMINI", "AIzaSyRecoverableLegacy987654").commit()

        val brokenEngine = object : KeyVault.CipherEngine {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException("Keystore locked")
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalStateException("Keystore locked")
        }
        val settings = Settings(context, KeyVault(brokenEngine))

        assertNotNull(settings.keyManager.migrationError)
        assertTrue(settings.keyManager.migrationError!!.contains("Keystore locked"))
        // Original recoverable entry MUST NOT be wiped on failure
        assertEquals("AIzaSyRecoverableLegacy987654", prefs.getString("key_GEMINI", ""))
    }

    @Test
    fun appError_classifies403QuotaAnd400GeminiInvalidKeyAndRedactsSecrets() {
        val secret = "AIzaSyVerySecretKeyABCDEF123456"
        val authErr = AppError.fromThrowable(
            ApiException("Bad key $secret", 400, """{"error":{"message":"API_KEY_INVALID for $secret"}}"""),
            "Gemini",
        )
        assertTrue(authErr is AppError.Auth)
        assertFalse(authErr.message.contains(secret))
        assertFalse(authErr.detail.contains(secret))

        val quota403 = AppError.fromThrowable(
            ApiException("Forbidden", 403, """{"error":{"message":"Quota exceeded for billing project"}}"""),
            "Gemini",
        )
        assertTrue(quota403 is AppError.QuotaExhausted)
    }
}
