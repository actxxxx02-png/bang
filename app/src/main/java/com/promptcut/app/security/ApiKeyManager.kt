package com.promptcut.app.security

import android.content.SharedPreferences
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class KeyHealthStatus(val label: String) {
    UNTESTED("Untested"),
    WORKING("Working"),
    INVALID("Invalid"),
    RATE_LIMITED("Rate Limited"),
    QUOTA_EXHAUSTED("Quota Exhausted"),
    DISABLED("Disabled"),
}

data class ApiKeyRecord(
    val id: String,
    val name: String,
    val providerId: ProviderId,
    val encryptedKey: String,
    val maskedKey: String,
    val enabled: Boolean = true,
    val status: KeyHealthStatus = KeyHealthStatus.UNTESTED,
    val lastSuccessfulTestAtMs: Long? = null,
    val lastTestedAtMs: Long? = null,
    val lastError: String? = null,
    val lastUsedAtMs: Long? = null,
    val cooldownUntilMs: Long = 0L,
    val isPreferred: Boolean = false,
    val activeModel: String? = null,
    val quotaNote: String? = null,
) {
    fun effectiveStatus(nowMs: Long = System.currentTimeMillis()): KeyHealthStatus {
        if (!enabled) return KeyHealthStatus.DISABLED
        if ((status == KeyHealthStatus.RATE_LIMITED || status == KeyHealthStatus.QUOTA_EXHAUSTED) &&
            cooldownUntilMs in 1..nowMs
        ) {
            return if (lastSuccessfulTestAtMs != null) KeyHealthStatus.WORKING else KeyHealthStatus.UNTESTED
        }
        return status
    }

    fun remainingCooldownSec(nowMs: Long = System.currentTimeMillis()): Int {
        if (!enabled || cooldownUntilMs <= nowMs) return 0
        return ((cooldownUntilMs - nowMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    fun isEligibleForRouting(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        val eff = effectiveStatus(nowMs)
        return eff == KeyHealthStatus.WORKING || eff == KeyHealthStatus.UNTESTED
    }
}

data class ProviderKeySummary(
    val providerId: ProviderId,
    val totalCount: Int,
    val workingCount: Int,
    val invalidCount: Int,
    val rateLimitedCount: Int,
    val quotaExhaustedCount: Int,
    val untestedCount: Int,
    val disabledCount: Int,
) {
    val activeAvailableCount: Int get() = workingCount + untestedCount
}

/**
 * Multi-key encrypted store per AI provider built on Android Keystore AES-GCM ([KeyVault]).
 *
 * Invariants:
 * - Zero plaintext key persistence: keys are encrypted with [KeyVault.encryptString] before commit.
 * - Saving or migrating a key NEVER marks it as [KeyHealthStatus.WORKING] until verified by an API call.
 * - Network failures preserve key health and NEVER falsely mark a key [KeyHealthStatus.INVALID].
 * - Legacy single-key preferences (`key_GEMINI`, `key_GROQ`, `key_CLAUDE`) migrate safely without data loss.
 */
class ApiKeyManager(
    private val prefs: SharedPreferences,
    private val vault: KeyVault,
) {
    private val lock = Any()
    private val records = mutableListOf<ApiKeyRecord>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    var migrationError: String? = null
        private set

    init {
        loadFromPrefsLocked()
        migrateLegacyKeysIfNeeded()
    }

    fun allKeys(): List<ApiKeyRecord> = synchronized(lock) { records.toList() }

    fun keysFor(providerId: ProviderId): List<ApiKeyRecord> = synchronized(lock) {
        records.filter { it.providerId == providerId }
            .sortedWith(
                compareByDescending<ApiKeyRecord> { it.isPreferred }
                    .thenByDescending { it.enabled }
                    .thenBy { it.name.lowercase() }
            )
    }

    fun findKey(keyId: String): ApiKeyRecord? = synchronized(lock) { records.firstOrNull { it.id == keyId } }

    fun summaryFor(providerId: ProviderId, nowMs: Long = System.currentTimeMillis()): ProviderKeySummary {
        val list = keysFor(providerId)
        var working = 0
        var invalid = 0
        var rateLimited = 0
        var quota = 0
        var untested = 0
        var disabled = 0
        list.forEach { r ->
            when (r.effectiveStatus(nowMs)) {
                KeyHealthStatus.WORKING -> working++
                KeyHealthStatus.INVALID -> invalid++
                KeyHealthStatus.RATE_LIMITED -> rateLimited++
                KeyHealthStatus.QUOTA_EXHAUSTED -> quota++
                KeyHealthStatus.UNTESTED -> untested++
                KeyHealthStatus.DISABLED -> disabled++
            }
        }
        return ProviderKeySummary(
            providerId = providerId,
            totalCount = list.size,
            workingCount = working,
            invalidCount = invalid,
            rateLimitedCount = rateLimited,
            quotaExhaustedCount = quota,
            untestedCount = untested,
            disabledCount = disabled,
        )
    }

    /**
     * Adds a new encrypted key for [providerId]. Newly saved keys always begin as [KeyHealthStatus.UNTESTED]
     * and are never marked working simply because they were saved.
     */
    fun addKey(
        providerId: ProviderId,
        name: String,
        rawKey: String,
        setPreferred: Boolean = false,
        activeModel: String? = null,
    ): AppResult<ApiKeyRecord> {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            return AppResult.Failure(AppError.Validation("API key cannot be empty."))
        }
        val encrypted = when (val enc = vault.encryptString(trimmed)) {
            is AppResult.Success -> enc.value
            is AppResult.Failure -> return enc
        }
        return synchronized(lock) {
            val existingSame = records.firstOrNull {
                it.providerId == providerId && decryptPlainLocked(it) == trimmed
            }
            if (existingSame != null) {
                val updatedName = name.trim().take(48).ifBlank { existingSame.name }
                val promoted = setPreferred || existingSame.isPreferred
                val updated = existingSame.copy(
                    name = updatedName,
                    enabled = true,
                    isPreferred = promoted,
                )
                replaceRecordLocked(updated, clearPreferredSibling = promoted)
                if (!persistLocked()) {
                    return@synchronized AppResult.Failure(AppError.Security("Could not persist updated key."))
                }
                return@synchronized AppResult.Success(updated)
            }

            val providerKeys = records.filter { it.providerId == providerId }
            val cleanName = name.trim().take(48).ifBlank {
                "${providerId.shortName} Key ${providerKeys.size + 1}"
            }
            val shouldPrefer = setPreferred || providerKeys.none { it.isPreferred }
            if (shouldPrefer) {
                for (i in records.indices) {
                    if (records[i].providerId == providerId && records[i].isPreferred) {
                        records[i] = records[i].copy(isPreferred = false)
                    }
                }
            }
            val record = ApiKeyRecord(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                providerId = providerId,
                encryptedKey = encrypted,
                maskedKey = KeyVault.maskKey(trimmed),
                enabled = true,
                status = KeyHealthStatus.UNTESTED,
                isPreferred = shouldPrefer,
                activeModel = activeModel?.takeIf { it.isNotBlank() },
                quotaNote = "Not tested yet. Tap Test to verify credentials.",
            )
            records += record
            if (!persistLocked()) {
                records.removeAll { it.id == record.id }
                return@synchronized AppResult.Failure(AppError.Security("Failed to save encrypted key to storage."))
            }
            AppResult.Success(record)
        }
    }

    fun renameKey(keyId: String, newName: String): AppResult<ApiKeyRecord> {
        val clean = newName.trim().take(48)
        if (clean.isEmpty()) return AppResult.Failure(AppError.Validation("Key name cannot be empty."))
        return synchronized(lock) {
            val idx = records.indexOfFirst { it.id == keyId }
            if (idx < 0) return@synchronized AppResult.Failure(AppError.Validation("Key not found."))
            val updated = records[idx].copy(name = clean)
            records[idx] = updated
            if (!persistLocked()) AppResult.Failure(AppError.Security("Failed to save key rename."))
            else AppResult.Success(updated)
        }
    }

    fun setEnabled(keyId: String, enabled: Boolean): AppResult<ApiKeyRecord> = synchronized(lock) {
        val idx = records.indexOfFirst { it.id == keyId }
        if (idx < 0) return@synchronized AppResult.Failure(AppError.Validation("Key not found."))
        val cur = records[idx]
        val nextStatus = when {
            !enabled -> KeyHealthStatus.DISABLED
            cur.status == KeyHealthStatus.DISABLED ->
                if (cur.lastSuccessfulTestAtMs != null) KeyHealthStatus.WORKING else KeyHealthStatus.UNTESTED
            else -> cur.status
        }
        val updated = cur.copy(enabled = enabled, status = nextStatus)
        records[idx] = updated
        if (!persistLocked()) AppResult.Failure(AppError.Security("Failed to update key state."))
        else AppResult.Success(updated)
    }

    fun setPreferred(keyId: String): AppResult<ApiKeyRecord> = synchronized(lock) {
        val idx = records.indexOfFirst { it.id == keyId }
        if (idx < 0) return@synchronized AppResult.Failure(AppError.Validation("Key not found."))
        val target = records[idx]
        for (i in records.indices) {
            if (records[i].providerId == target.providerId) {
                records[i] = records[i].copy(isPreferred = records[i].id == keyId)
            }
        }
        val updated = records.first { it.id == keyId }
        if (!persistLocked()) AppResult.Failure(AppError.Security("Failed to update preferred key."))
        else AppResult.Success(updated)
    }

    fun deleteKey(keyId: String): AppResult<Unit> = synchronized(lock) {
        val target = records.firstOrNull { it.id == keyId }
            ?: return@synchronized AppResult.Failure(AppError.Validation("Key not found."))
        records.removeAll { it.id == keyId }
        if (target.isPreferred) {
            val replacementIdx = records.indexOfFirst { it.providerId == target.providerId && it.enabled }
                .takeIf { it >= 0 } ?: records.indexOfFirst { it.providerId == target.providerId }
            if (replacementIdx >= 0) {
                records[replacementIdx] = records[replacementIdx].copy(isPreferred = true)
            }
        }
        if (!persistLocked()) AppResult.Failure(AppError.Security("Failed to delete key from storage."))
        else AppResult.Success(Unit)
    }

    fun clearProvider(providerId: ProviderId) {
        synchronized(lock) {
            records.removeAll { it.providerId == providerId }
            persistLocked()
        }
    }

    fun decryptKey(keyId: String): AppResult<String> {
        val record = findKey(keyId) ?: return AppResult.Failure(AppError.Validation("API key not found."))
        return vault.decryptString(record.encryptedKey)
    }

    /**
     * Ordered list of eligible keys for [providerId]:
     * 1) Preferred eligible key
     * 2) Remaining Working eligible keys (most recently verified first)
     * 3) Untested eligible keys
     * Disabled, Invalid, Quota-exhausted, and actively cooling-down keys are excluded.
     */
    fun eligibleKeysFor(providerId: ProviderId, nowMs: Long = System.currentTimeMillis()): List<ApiKeyRecord> =
        synchronized(lock) {
            records.filter { it.providerId == providerId && it.isEligibleForRouting(nowMs) }
                .sortedWith(
                    compareByDescending<ApiKeyRecord> { it.isPreferred }
                        .thenByDescending { it.effectiveStatus(nowMs) == KeyHealthStatus.WORKING }
                        .thenByDescending { it.lastSuccessfulTestAtMs ?: 0L }
                )
        }

    fun selectPrimaryKey(providerId: ProviderId, nowMs: Long = System.currentTimeMillis()): ApiKeyRecord? =
        eligibleKeysFor(providerId, nowMs).firstOrNull()

    fun hasEligibleKey(providerId: ProviderId, nowMs: Long = System.currentTimeMillis()): Boolean =
        eligibleKeysFor(providerId, nowMs).isNotEmpty()

    fun hasEnabledNonInvalidKey(providerId: ProviderId): Boolean = synchronized(lock) {
        records.any { it.providerId == providerId && it.enabled && it.status != KeyHealthStatus.INVALID }
    }

    fun markWorking(
        keyId: String,
        model: String?,
        usedNow: Boolean,
        testSucceededNow: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        quotaNote: String? = null,
    ) {
        synchronized(lock) {
            val idx = records.indexOfFirst { it.id == keyId }
            if (idx < 0) return
            val cur = records[idx]
            val note = quotaNote ?: "Auth verified (provider API does not expose exact remaining quota)"
            records[idx] = cur.copy(
                status = if (cur.enabled) KeyHealthStatus.WORKING else KeyHealthStatus.DISABLED,
                lastError = null,
                cooldownUntilMs = 0L,
                activeModel = model ?: cur.activeModel,
                lastUsedAtMs = if (usedNow) nowMs else cur.lastUsedAtMs,
                lastTestedAtMs = if (testSucceededNow) nowMs else cur.lastTestedAtMs,
                lastSuccessfulTestAtMs = if (testSucceededNow) nowMs else cur.lastSuccessfulTestAtMs,
                quotaNote = note,
            )
            persistLocked()
        }
    }

    fun markUsed(keyId: String, model: String? = null, nowMs: Long = System.currentTimeMillis()) {
        markWorking(keyId, model, usedNow = true, testSucceededNow = false, nowMs = nowMs)
    }

    fun recordFailure(keyId: String, error: AppError, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val idx = records.indexOfFirst { it.id == keyId }
            if (idx < 0) return
            val cur = records[idx]
            val safeError = KeyVault.redactSecrets(error.message, knownSecretsLocked()).take(220)
            val updated = when (error) {
                is AppError.Auth -> cur.copy(
                    status = KeyHealthStatus.INVALID,
                    lastError = safeError,
                    cooldownUntilMs = 0L,
                    quotaNote = "Authentication rejected by provider",
                )
                is AppError.RateLimit -> cur.copy(
                    status = KeyHealthStatus.RATE_LIMITED,
                    lastError = safeError,
                    cooldownUntilMs = nowMs + RATE_LIMIT_COOLDOWN_MS,
                    quotaNote = "Temporary rate limit cooldown (60s)",
                )
                is AppError.QuotaExhausted -> cur.copy(
                    status = KeyHealthStatus.QUOTA_EXHAUSTED,
                    lastError = safeError,
                    cooldownUntilMs = nowMs + QUOTA_EXHAUSTED_COOLDOWN_MS,
                    quotaNote = "Provider reported quota/credits exhausted",
                )
                is AppError.Network, is AppError.Timeout -> cur.copy(
                    // Network failure must NEVER falsely mark a key invalid!
                    lastError = safeError,
                )
                is AppError.ProviderUnavailable -> cur.copy(
                    lastError = safeError,
                )
                is AppError.PaidConsentRequired -> cur.copy(
                    lastError = safeError,
                )
                else -> cur.copy(lastError = safeError)
            }
            records[idx] = updated
            persistLocked()
        }
    }

    fun recordTestResult(
        keyId: String,
        result: AppResult<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        when (result) {
            is AppResult.Success -> {
                markWorking(
                    keyId = keyId,
                    model = result.value,
                    usedNow = false,
                    testSucceededNow = true,
                    nowMs = nowMs,
                    quotaNote = "Auth verified (remaining quota not exposed by API)",
                )
            }
            is AppResult.Failure -> {
                synchronized(lock) {
                    val idx = records.indexOfFirst { it.id == keyId }
                    if (idx >= 0) {
                        records[idx] = records[idx].copy(lastTestedAtMs = nowMs)
                    }
                }
                recordFailure(keyId, result.error, nowMs)
            }
        }
    }

    fun setModelForKey(keyId: String, model: String?) {
        synchronized(lock) {
            val idx = records.indexOfFirst { it.id == keyId }
            if (idx < 0) return
            records[idx] = records[idx].copy(activeModel = model?.takeIf { it.isNotBlank() })
            persistLocked()
        }
    }

    fun allDecryptedSecrets(): List<String> = synchronized(lock) { knownSecretsLocked() }

    /**
     * Non-destructive migration from single-key-per-provider preferences (`key_GEMINI`, `key_GROQ`, `key_CLAUDE`)
     * into the encrypted multi-key store. Preserves model preferences and Claude paid consent flags, and keeps
     * recoverable legacy entries intact if encryption or migration fails.
     */
    fun migrateLegacyKeysIfNeeded() {
        synchronized(lock) {
            val legacyKeys = ProviderId.entries.mapNotNull { pid ->
                val prefKey = "key_${pid.name}"
                val raw = prefs.getString(prefKey, "") ?: ""
                if (raw.isBlank()) null else pid to prefKey
            }
            if (legacyKeys.isEmpty()) {
                prefs.edit().putBoolean(PREF_LEGACY_MIGRATED, true).apply()
                return
            }

            val errors = mutableListOf<String>()
            val migratedPrefKeys = mutableListOf<String>()

            for ((pid, prefKey) in legacyKeys) {
                when (val dec = vault.readAndMigrate(prefs, prefKey)) {
                    is AppResult.Success -> {
                        val plain = dec.value.trim()
                        if (plain.isEmpty()) {
                            migratedPrefKeys += prefKey
                            continue
                        }
                        val alreadyPresent = records.any {
                            it.providerId == pid && decryptPlainLocked(it) == plain
                        }
                        if (alreadyPresent) {
                            migratedPrefKeys += prefKey
                            continue
                        }
                        when (val enc = vault.encryptString(plain)) {
                            is AppResult.Success -> {
                                val hasPref = records.any { it.providerId == pid && it.isPreferred }
                                val savedModel = prefs.getString("model_${pid.name}", null)
                                records += ApiKeyRecord(
                                    id = UUID.randomUUID().toString(),
                                    name = "Default ${pid.shortName} Key",
                                    providerId = pid,
                                    encryptedKey = enc.value,
                                    maskedKey = KeyVault.maskKey(plain),
                                    enabled = true,
                                    status = KeyHealthStatus.UNTESTED,
                                    isPreferred = !hasPref,
                                    activeModel = savedModel,
                                    quotaNote = "Migrated from legacy settings — tap Test to verify",
                                )
                                migratedPrefKeys += prefKey
                            }
                            is AppResult.Failure -> {
                                val detail = KeyVault.redactSecrets(enc.error.detail).trim()
                                errors += if (detail.isNotBlank()) "${pid.shortName}: ${enc.error.message} ($detail)"
                                else "${pid.shortName}: ${enc.error.message}"
                            }
                        }
                    }
                    is AppResult.Failure -> {
                        val detail = KeyVault.redactSecrets(dec.error.detail).trim()
                        errors += if (detail.isNotBlank()) "${pid.shortName}: ${dec.error.message} ($detail)"
                        else "${pid.shortName}: ${dec.error.message}"
                    }
                }
            }

            if (migratedPrefKeys.isNotEmpty()) {
                if (persistLocked()) {
                    val ed = prefs.edit()
                    migratedPrefKeys.forEach { ed.remove(it) }
                    if (errors.isEmpty()) ed.putBoolean(PREF_LEGACY_MIGRATED, true)
                    ed.commit()
                } else {
                    errors += "Failed to commit migrated keys; original entries were preserved."
                }
            }

            migrationError = if (errors.isEmpty()) null else errors.joinToString(" | ")
        }
    }

    private fun decryptPlainLocked(record: ApiKeyRecord): String? =
        vault.decryptString(record.encryptedKey).getOrNull()

    private fun knownSecretsLocked(): List<String> =
        records.mapNotNull { decryptPlainLocked(it)?.takeIf { k -> k.length >= 6 } }

    private fun replaceRecordLocked(updated: ApiKeyRecord, clearPreferredSibling: Boolean) {
        if (clearPreferredSibling) {
            for (i in records.indices) {
                if (records[i].providerId == updated.providerId && records[i].id != updated.id && records[i].isPreferred) {
                    records[i] = records[i].copy(isPreferred = false)
                }
            }
        }
        val idx = records.indexOfFirst { it.id == updated.id }
        if (idx >= 0) records[idx] = updated else records += updated
    }

    private fun loadFromPrefsLocked() {
        records.clear()
        val raw = prefs.getString(PREF_KEYS_V2, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = runCatching { ProviderId.valueOf(o.getString("providerId")) }.getOrNull() ?: continue
                val encrypted = o.optString("encryptedKey", "")
                if (encrypted.isBlank()) continue
                val status = runCatching { KeyHealthStatus.valueOf(o.optString("status", "UNTESTED")) }
                    .getOrDefault(KeyHealthStatus.UNTESTED)
                records += ApiKeyRecord(
                    id = o.getString("id"),
                    name = o.optString("name", "${pid.shortName} Key"),
                    providerId = pid,
                    encryptedKey = encrypted,
                    maskedKey = o.optString("maskedKey", "••••"),
                    enabled = o.optBoolean("enabled", true),
                    status = status,
                    lastSuccessfulTestAtMs = o.optLong("lastSuccessfulTestAtMs", -1L).takeIf { it > 0 },
                    lastTestedAtMs = o.optLong("lastTestedAtMs", -1L).takeIf { it > 0 },
                    lastError = o.optString("lastError", "").ifBlank { null },
                    lastUsedAtMs = o.optLong("lastUsedAtMs", -1L).takeIf { it > 0 },
                    cooldownUntilMs = o.optLong("cooldownUntilMs", 0L),
                    isPreferred = o.optBoolean("isPreferred", false),
                    activeModel = o.optString("activeModel", "").ifBlank { null },
                    quotaNote = o.optString("quotaNote", "").ifBlank { null },
                )
            }
        }
    }

    private fun persistLocked(): Boolean {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("providerId", r.providerId.name)
                put("encryptedKey", r.encryptedKey)
                put("maskedKey", r.maskedKey)
                put("enabled", r.enabled)
                put("status", r.status.name)
                r.lastSuccessfulTestAtMs?.let { put("lastSuccessfulTestAtMs", it) }
                r.lastTestedAtMs?.let { put("lastTestedAtMs", it) }
                r.lastError?.let { put("lastError", it) }
                r.lastUsedAtMs?.let { put("lastUsedAtMs", it) }
                put("cooldownUntilMs", r.cooldownUntilMs)
                put("isPreferred", r.isPreferred)
                r.activeModel?.let { put("activeModel", it) }
                r.quotaNote?.let { put("quotaNote", it) }
            })
        }
        val ok = prefs.edit().putString(PREF_KEYS_V2, arr.toString()).commit()
        if (ok) _revision.value = _revision.value + 1
        return ok
    }

    companion object {
        const val PREF_KEYS_V2 = "api_keys_v2"
        const val PREF_LEGACY_MIGRATED = "legacy_keys_migrated_v2"
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val QUOTA_EXHAUSTED_COOLDOWN_MS = 15 * 60_000L
    }
}
