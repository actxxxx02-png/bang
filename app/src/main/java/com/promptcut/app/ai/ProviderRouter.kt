package com.promptcut.app.ai

import com.promptcut.app.Settings
import com.promptcut.app.Span
import com.promptcut.app.SpeechToText
import com.promptcut.app.ai.providers.ClaudeProvider
import com.promptcut.app.ai.providers.GeminiProvider
import com.promptcut.app.ai.providers.GroqProvider
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.ApiKeyRecord
import com.promptcut.app.security.KeyHealthStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-key AI provider router enforcing:
 * - Same-provider rotation across eligible keys (preferred first) on Auth, RateLimit, or QuotaExhausted.
 * - Safe bounded exponential backoff for transient 5xx server errors; network failures never invalidate keys.
 * - Strict deduplication (`attemptedKeyIds`) per request to prevent duplicate calls and infinite loops.
 * - Free-first auto routing across Gemini (vision) & Groq (text/STT).
 * - Anthropic Claude is strictly manual-only and gated behind explicit paid-API consent.
 */
class ProviderRouter(
    private val settings: Settings,
    private val providers: Map<ProviderId, AiProvider> = mapOf(
        ProviderId.GEMINI to GeminiProvider(),
        ProviderId.GROQ to GroqProvider(),
        ProviderId.CLAUDE to ClaudeProvider { settings.privacy.claudePaidConsentGranted },
    ),
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val retryBackoffBaseMs: Long = 600L,
) {
    data class RoutingDecision(
        val providerId: ProviderId,
        val reason: String,
        val isPaid: Boolean,
        val selectedKeyId: String? = null,
    )

    fun providerFor(id: ProviderId): AiProvider = providers.getValue(id)

    fun isRateLimitedNow(id: ProviderId, nowMs: Long = nowMsProvider()): Boolean =
        settings.keyManager.keysFor(id).any { it.enabled } &&
            !settings.keyManager.hasEligibleKey(id, nowMs) &&
            settings.keyManager.keysFor(id).any {
                val eff = it.effectiveStatus(nowMs)
                it.enabled && (eff == KeyHealthStatus.RATE_LIMITED || eff == KeyHealthStatus.QUOTA_EXHAUSTED)
            }

    fun markRateLimited(id: ProviderId, @Suppress("UNUSED_PARAMETER") cooldownMs: Long = 60_000L, nowMs: Long = nowMsProvider()) {
        settings.keyManager.selectPrimaryKey(id, nowMs)?.let { key ->
            settings.keyManager.recordFailure(key.id, AppError.RateLimit(id.shortName, 429), nowMs)
        }
    }

    /**
     * Selects the best provider according to task needs, eligible keys, cooldowns, and consent.
     */
    fun chooseProvider(
        needsVision: Boolean,
        manualOverride: ProviderId? = null,
        nowMs: Long = nowMsProvider(),
    ): AppResult<RoutingDecision> {
        val chosenManual = manualOverride ?: if (!settings.routingModeAuto) settings.manualProviderId else null
        if (chosenManual != null) {
            return validateSpecificProvider(chosenManual, "Selected manually by user", nowMs)
        }

        val geminiKey = settings.keyManager.selectPrimaryKey(ProviderId.GEMINI, nowMs)
        val groqKey = settings.keyManager.selectPrimaryKey(ProviderId.GROQ, nowMs)

        // Free-first routing: never auto-route to paid Claude.
        if (needsVision) {
            if (geminiKey != null) {
                return AppResult.Success(
                    RoutingDecision(ProviderId.GEMINI, "Free-first vision routing (Gemini: ${geminiKey.name})", isPaid = false, geminiKey.id)
                )
            }
            if (groqKey != null) {
                return AppResult.Success(
                    RoutingDecision(ProviderId.GROQ, "Fallback text-based clip understanding (Groq: ${groqKey.name})", isPaid = false, groqKey.id)
                )
            }
        } else {
            if (groqKey != null) {
                return AppResult.Success(
                    RoutingDecision(ProviderId.GROQ, "Free-first fast text planning (Groq: ${groqKey.name})", isPaid = false, groqKey.id)
                )
            }
            if (geminiKey != null) {
                return AppResult.Success(
                    RoutingDecision(ProviderId.GEMINI, "Free-first text planning (Gemini: ${geminiKey.name})", isPaid = false, geminiKey.id)
                )
            }
        }

        // Check if keys exist on Gemini/Groq but are all currently invalid, disabled, or cooling down.
        val allFreeKeys = settings.keyManager.keysFor(ProviderId.GEMINI) + settings.keyManager.keysFor(ProviderId.GROQ)
        if (allFreeKeys.isNotEmpty()) {
            val activeCooldown = allFreeKeys.filter { it.enabled && it.remainingCooldownSec(nowMs) > 0 }
                .minByOrNull { it.remainingCooldownSec(nowMs) }
            if (activeCooldown != null) {
                return AppResult.Failure(
                    AppError.RateLimit(
                        activeCooldown.providerId.shortName,
                        429,
                        "All enabled ${activeCooldown.providerId.shortName} keys are cooling down (${activeCooldown.remainingCooldownSec(nowMs)}s remaining).",
                    )
                )
            }
            if (allFreeKeys.none { it.enabled }) {
                return AppResult.Failure(AppError.Auth("AI service", message = "All configured Gemini/Groq keys are disabled in Settings."))
            }
            return AppResult.Failure(
                AppError.Auth("AI service", message = "Configured Gemini/Groq keys are invalid or exhausted. Open Settings to check Key Health.")
            )
        }

        // If only Claude is configured, STILL refuse to run silently without explicit paid consent & manual opt-in.
        if (settings.keyManager.keysFor(ProviderId.CLAUDE).isNotEmpty()) {
            return if (!settings.privacy.claudePaidConsentGranted) {
                AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
            } else {
                AppResult.Failure(
                    AppError.Validation(
                        "No free provider key is configured. Switch to Manual mode and select Claude in Settings to use paid Claude credits."
                    )
                )
            }
        }

        return AppResult.Failure(
            AppError.Auth("AI service", message = "No API key configured. Open Settings to add a free Gemini or Groq key.")
        )
    }

    private fun validateSpecificProvider(id: ProviderId, reason: String, nowMs: Long): AppResult<RoutingDecision> {
        if (id == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        val allKeys = settings.keyManager.keysFor(id)
        if (allKeys.isEmpty()) {
            return AppResult.Failure(
                AppError.Auth(id.shortName, message = "No ${id.shortName} API keys saved. Add one in Settings.")
            )
        }
        val eligible = settings.keyManager.selectPrimaryKey(id, nowMs)
        if (eligible == null) {
            val cooling = allKeys.filter { it.enabled && it.remainingCooldownSec(nowMs) > 0 }
                .minByOrNull { it.remainingCooldownSec(nowMs) }
            return when {
                allKeys.none { it.enabled } -> AppResult.Failure(
                    AppError.Auth(id.shortName, message = "All ${id.shortName} keys are disabled. Enable a key in Settings.")
                )
                cooling != null -> AppResult.Failure(
                    AppError.RateLimit(id.shortName, 429, "All ${id.shortName} keys are cooling down (${cooling.remainingCooldownSec(nowMs)}s left).")
                )
                else -> AppResult.Failure(
                    AppError.Auth(id.shortName, message = "All enabled ${id.shortName} keys are invalid or exhausted. Check Key Health in Settings.")
                )
            }
        }
        return AppResult.Success(RoutingDecision(id, "$reason (${eligible.name})", isPaid = id == ProviderId.CLAUDE, eligible.id))
    }

    suspend fun testConnection(id: ProviderId, rawKey: String, preferredModel: String? = null): AppResult<String> {
        if (id == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        return providerFor(id).testConnection(rawKey.trim(), preferredModel)
    }

    suspend fun testSavedKey(keyId: String): AppResult<String> {
        val record = settings.keyManager.findKey(keyId)
            ?: return AppResult.Failure(AppError.Validation("API key not found."))
        if (record.providerId == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
            val err = AppError.PaidConsentRequired("Anthropic Claude")
            settings.keyManager.recordFailure(keyId, err, nowMsProvider())
            return AppResult.Failure(err)
        }
        val plain = when (val dec = settings.keyManager.decryptKey(keyId)) {
            is AppResult.Success -> dec.value
            is AppResult.Failure -> {
                settings.keyManager.recordFailure(keyId, dec.error, nowMsProvider())
                return dec
            }
        }
        val res = providerFor(record.providerId).testConnection(
            plain,
            record.activeModel ?: settings.modelFor(record.providerId),
        )
        settings.keyManager.recordTestResult(keyId, res, nowMsProvider())
        if (res is AppResult.Success) {
            settings.setModelFor(record.providerId, res.value)
        }
        return res
    }

    suspend fun testAllEnabledKeys(
        concurrency: Int = 2,
        onProgress: (completed: Int, total: Int, keyId: String) -> Unit = { _, _, _ -> },
    ): Map<String, AppResult<String>> = coroutineScope {
        val targets = settings.keyManager.allKeys().filter { it.enabled }
        if (targets.isEmpty()) return@coroutineScope emptyMap()
        val semaphore = Semaphore(concurrency.coerceIn(1, 3))
        val done = AtomicInteger(0)
        targets.map { record ->
            async {
                val res = semaphore.withPermit { testSavedKey(record.id) }
                val count = done.incrementAndGet()
                onProgress(count, targets.size, record.id)
                record.id to res
            }
        }.awaitAll().toMap()
    }

    private fun candidateProviderOrder(needsVision: Boolean, manualOverride: ProviderId?): List<ProviderId> {
        val explicit = manualOverride ?: if (!settings.routingModeAuto) settings.manualProviderId else null
        if (explicit != null) return listOf(explicit)
        return if (needsVision) listOf(ProviderId.GEMINI, ProviderId.GROQ)
        else listOf(ProviderId.GROQ, ProviderId.GEMINI)
    }

    suspend fun complete(
        request: AiRequest,
        needsVision: Boolean = request.frames.isNotEmpty(),
        manualOverride: ProviderId? = null,
    ): AppResult<AiResponse> {
        val initialDecision = when (val d = chooseProvider(needsVision, manualOverride)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> return d
        }

        val order = candidateProviderOrder(needsVision, manualOverride)
            .let { list ->
                if (initialDecision.providerId in list) {
                    listOf(initialDecision.providerId) + list.filter { it != initialDecision.providerId }
                } else list
            }

        val attemptedKeyIds = mutableSetOf<String>()
        var lastError: AppError? = null

        for (providerId in order) {
            if (providerId == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
                return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
            }
            val privacyCheck = settings.privacy.validateRequest(
                provider = providerId,
                frameCount = if (providerFor(providerId).capabilities.supportsVision) request.frames.size else 0,
            )
            val effectiveFrames = when (privacyCheck) {
                is AppResult.Success -> request.frames.take(privacyCheck.value.sendsSampledFrames)
                is AppResult.Failure -> return privacyCheck
            }
            val safeRequest = request.copy(frames = effectiveFrames)

            val providerKeys = settings.keyManager.eligibleKeysFor(providerId, nowMsProvider())
            for (keyRecord in providerKeys) {
                if (!attemptedKeyIds.add(keyRecord.id)) continue
                val res = completeWithSingleKeyAndServerBackoff(providerId, keyRecord, safeRequest)
                when (res) {
                    is AppResult.Success -> {
                        settings.keyManager.markWorking(
                            keyId = keyRecord.id,
                            model = res.value.model,
                            usedNow = true,
                            testSucceededNow = false,
                            nowMs = nowMsProvider(),
                        )
                        settings.setModelFor(providerId, res.value.model)
                        res.value.usage?.let { settings.recordUsage(it.inputTokens, it.outputTokens) }
                        return res
                    }
                    is AppResult.Failure -> {
                        val err = res.error
                        lastError = err
                        settings.keyManager.recordFailure(keyRecord.id, err, nowMsProvider())
                        when (err) {
                            is AppError.Network, is AppError.Timeout -> {
                                // Network failure: never mark key invalid and stop immediately.
                                return res
                            }
                            is AppError.PaidConsentRequired, is AppError.PrivacyBlocked, is AppError.Validation -> {
                                return res
                            }
                            is AppError.Auth, is AppError.RateLimit, is AppError.QuotaExhausted, is AppError.ProviderUnavailable -> {
                                // Rotate to next eligible key on the same provider before moving providers.
                                continue
                            }
                            else -> continue
                        }
                    }
                }
            }

            if (manualOverride != null || !settings.routingModeAuto) break
        }

        return AppResult.Failure(
            lastError ?: AppError.Auth("AI service", message = "No eligible API keys available. Open Settings to check Key Health.")
        )
    }

    private suspend fun completeWithSingleKeyAndServerBackoff(
        providerId: ProviderId,
        keyRecord: ApiKeyRecord,
        safeRequest: AiRequest,
    ): AppResult<AiResponse> {
        val rawKey = when (val dec = settings.keyManager.decryptKey(keyRecord.id)) {
            is AppResult.Success -> dec.value
            is AppResult.Failure -> return dec
        }
        val model = keyRecord.activeModel ?: settings.modelFor(providerId)
        var attempt = 0
        while (true) {
            val res = providerFor(providerId).complete(rawKey, model, safeRequest)
            if (res is AppResult.Failure && res.error is AppError.ProviderUnavailable && attempt < MAX_5XX_RETRIES) {
                attempt++
                if (retryBackoffBaseMs > 0L) delay(retryBackoffBaseMs * attempt)
                continue
            }
            return res
        }
    }

    fun stream(
        request: AiRequest,
        needsVision: Boolean = request.frames.isNotEmpty(),
        manualOverride: ProviderId? = null,
    ): Flow<AiStreamEvent> = flow {
        val decision = when (val d = chooseProvider(needsVision, manualOverride)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> {
                emit(AiStreamEvent.Error(d.error))
                return@flow
            }
        }
        val order = candidateProviderOrder(needsVision, manualOverride).let { list ->
            if (decision.providerId in list) listOf(decision.providerId) + list.filter { it != decision.providerId }
            else list
        }
        val attemptedKeyIds = mutableSetOf<String>()
        var lastError: AppError? = null

        for (providerId in order) {
            if (providerId == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
                emit(AiStreamEvent.Error(AppError.PaidConsentRequired("Anthropic Claude")))
                return@flow
            }
            val privacyCheck = settings.privacy.validateRequest(
                provider = providerId,
                frameCount = if (providerFor(providerId).capabilities.supportsVision) request.frames.size else 0,
            )
            val effectiveFrames = when (privacyCheck) {
                is AppResult.Success -> request.frames.take(privacyCheck.value.sendsSampledFrames)
                is AppResult.Failure -> {
                    emit(AiStreamEvent.Error(privacyCheck.error))
                    return@flow
                }
            }
            val safeReq = request.copy(frames = effectiveFrames)
            val keys = settings.keyManager.eligibleKeysFor(providerId, nowMsProvider())
            for (keyRecord in keys) {
                if (!attemptedKeyIds.add(keyRecord.id)) continue
                val rawKey = when (val dec = settings.keyManager.decryptKey(keyRecord.id)) {
                    is AppResult.Success -> dec.value
                    is AppResult.Failure -> {
                        lastError = dec.error
                        continue
                    }
                }
                var emittedOutput = false
                var completed = false
                var rotatableError: AppError? = null
                var terminalError: AppError? = null
                var attempt = 0

                while (true) {
                    var retry5xx = false
                    rotatableError = null
                    terminalError = null

                    providerFor(providerId).stream(rawKey, keyRecord.activeModel ?: settings.modelFor(providerId), safeReq)
                        .collect { ev ->
                            when (ev) {
                                is AiStreamEvent.Delta, is AiStreamEvent.ToolCallReady -> {
                                    emittedOutput = true
                                    emit(ev)
                                }
                                is AiStreamEvent.Completed -> {
                                    completed = true
                                    settings.keyManager.markWorking(
                                        keyId = keyRecord.id,
                                        model = ev.response.model,
                                        usedNow = true,
                                        testSucceededNow = false,
                                        nowMs = nowMsProvider(),
                                    )
                                    settings.setModelFor(providerId, ev.response.model)
                                    ev.response.usage?.let { settings.recordUsage(it.inputTokens, it.outputTokens) }
                                    emit(ev)
                                }
                                is AiStreamEvent.Error -> {
                                    if (!emittedOutput && ev.error is AppError.ProviderUnavailable && attempt < MAX_5XX_RETRIES) {
                                        attempt++
                                        retry5xx = true
                                    } else {
                                        settings.keyManager.recordFailure(keyRecord.id, ev.error, nowMsProvider())
                                        val canRotate = !emittedOutput && (
                                            ev.error is AppError.Auth ||
                                                ev.error is AppError.RateLimit ||
                                                ev.error is AppError.QuotaExhausted ||
                                                ev.error is AppError.ProviderUnavailable
                                            )
                                        if (canRotate) rotatableError = ev.error
                                        else terminalError = ev.error
                                    }
                                }
                            }
                        }

                    if (completed || terminalError != null || rotatableError != null || !retry5xx) break
                    if (retryBackoffBaseMs > 0L) delay(retryBackoffBaseMs * attempt)
                }

                if (completed) return@flow
                if (terminalError != null) {
                    emit(AiStreamEvent.Error(terminalError!!))
                    return@flow
                }
                if (rotatableError != null) lastError = rotatableError
            }
            if (manualOverride != null || !settings.routingModeAuto) break
        }
        emit(AiStreamEvent.Error(lastError ?: AppError.Auth("AI service", message = "No eligible API key available.")))
    }

    /**
     * Transcribes extracted speech audio with Groq Whisper, rotating across eligible Groq keys
     * on invalid auth, rate limit, or quota exhaustion without invalidating keys on network error.
     */
    suspend fun transcribeAudioWithGroq(
        file: File,
        transcribeCall: suspend (apiKey: String, file: File) -> List<Span> = { key, f -> SpeechToText.groq(key, f) },
    ): AppResult<List<Span>> {
        val privacyCheck = settings.privacy.validateRequest(ProviderId.GROQ, frameCount = 0, isSpeechToText = true)
        if (privacyCheck is AppResult.Failure) return privacyCheck
        val keys = settings.keyManager.eligibleKeysFor(ProviderId.GROQ, nowMsProvider())
        if (keys.isEmpty()) {
            return AppResult.Failure(AppError.Auth("Groq", message = "No eligible Groq API key for speech-to-text."))
        }
        val attempted = mutableSetOf<String>()
        var lastError: AppError? = null
        for (k in keys) {
            if (!attempted.add(k.id)) continue
            val plain = when (val dec = settings.keyManager.decryptKey(k.id)) {
                is AppResult.Success -> dec.value
                is AppResult.Failure -> {
                    lastError = dec.error
                    continue
                }
            }
            try {
                val spans = transcribeCall(plain, file)
                settings.keyManager.markUsed(k.id, nowMs = nowMsProvider())
                return AppResult.Success(spans)
            } catch (t: Throwable) {
                val err = AppError.fromThrowable(t, "Groq")
                settings.keyManager.recordFailure(k.id, err, nowMsProvider())
                lastError = err
                if (err is AppError.Network || err is AppError.Timeout) return AppResult.Failure(err)
            }
        }
        return AppResult.Failure(lastError ?: AppError.Auth("Groq", message = "Speech-to-text failed on all Groq keys."))
    }

    companion object {
        private const val MAX_5XX_RETRIES = 2
    }
}
