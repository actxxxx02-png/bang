package com.promptcut.app

import android.content.Context
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.toProviderId
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.ApiKeyManager
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.PrivacyManager

enum class ThemePreference(val label: String) {
    DARK("Dark (Studio)"),
    LIGHT("Light"),
    SYSTEM("System"),
}

enum class ExportResolution(val label: String, val shortEdge: Int) {
    P720("720p HD", 720),
    P1080("1080p Full HD", 1080),
    P1440("1440p QHD", 1440),
    P2160("4K UHD", 2160),
}

/**
 * Encrypted settings & preferences store.
 * Multi-key credentials stay on this phone only, encrypted via Android Keystore ([KeyVault])
 * and managed through [ApiKeyManager] with zero plaintext fallback.
 */
class Settings(context: Context, private val vault: KeyVault = KeyVault()) {
    private val prefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)
    val privacy = PrivacyManager(prefs)
    val keyManager = ApiKeyManager(prefs, vault)

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }.getOrDefault(Provider.GEMINI)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    var routingModeAuto: Boolean
        get() = prefs.getBoolean("routing_mode_auto", true)
        set(v) = prefs.edit().putBoolean("routing_mode_auto", v).apply()

    var manualProviderId: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString("manual_provider_id", null) ?: "") }
            .getOrDefault(provider.toProviderId())
        set(v) {
            prefs.edit().putString("manual_provider_id", v.name).apply()
            v.toLegacyProvider()?.let { provider = it }
        }

    var themePreference: ThemePreference
        get() = runCatching { ThemePreference.valueOf(prefs.getString("theme_pref", null) ?: "") }
            .getOrDefault(ThemePreference.DARK)
        set(v) = prefs.edit().putString("theme_pref", v.name).apply()

    var preview720pMode: Boolean
        get() = prefs.getBoolean("preview_720p", false)
        set(v) = prefs.edit().putBoolean("preview_720p", v).apply()

    var fullEffectsPreview: Boolean
        get() = prefs.getBoolean("full_effects_preview", true)
        set(v) = prefs.edit().putBoolean("full_effects_preview", v).apply()

    var preferredResolution: ExportResolution
        get() = runCatching { ExportResolution.valueOf(prefs.getString("export_res", null) ?: "") }
            .getOrDefault(ExportResolution.P1080)
        set(v) = prefs.edit().putString("export_res", v.name).apply()

    var preferredFps: Int
        get() = prefs.getInt("export_fps", 30).coerceIn(24, 60)
        set(v) = prefs.edit().putInt("export_fps", v.coerceIn(24, 60)).apply()

    var preferredBitrateMbps: Int
        get() = prefs.getInt("export_bitrate_mbps", 8).coerceIn(4, 24)
        set(v) = prefs.edit().putInt("export_bitrate_mbps", v.coerceIn(4, 24)).apply()

    var preferredAudioBitrateKbps: Int
        get() = prefs.getInt("export_audio_bitrate_kbps", 192).coerceIn(96, 320)
        set(v) = prefs.edit().putInt("export_audio_bitrate_kbps", v.coerceIn(96, 320)).apply()

    var preferredCodecHevc: Boolean
        get() = prefs.getBoolean("export_codec_hevc", false)
        set(v) = prefs.edit().putBoolean("export_codec_hevc", v).apply()

    var lastSecurityError: String? = keyManager.migrationError
        private set

    fun key(p: Provider): String = keyFor(p.toProviderId())

    fun keyFor(id: ProviderId): String {
        val selected = keyManager.selectPrimaryKey(id)
            ?: keyManager.keysFor(id).firstOrNull { it.enabled }
            ?: return ""
        return when (val res = keyManager.decryptKey(selected.id)) {
            is AppResult.Success -> {
                lastSecurityError = keyManager.migrationError
                res.value
            }
            is AppResult.Failure -> {
                lastSecurityError = res.error.message
                ""
            }
        }
    }

    fun setKey(p: Provider, key: String): Boolean = setKeyFor(p.toProviderId(), key).isSuccess

    fun setKeyFor(id: ProviderId, key: String): AppResult<Unit> {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            keyManager.clearProvider(id)
            lastSecurityError = keyManager.migrationError
            return AppResult.Success(Unit)
        }
        val res = keyManager.addKey(
            providerId = id,
            name = "Default ${id.shortName} Key",
            rawKey = trimmed,
            setPreferred = true,
            activeModel = modelFor(id),
        )
        lastSecurityError = res.errorOrNull()?.message ?: keyManager.migrationError
        return res.map { Unit }
    }

    fun model(p: Provider): String? = modelFor(p.toProviderId())
    fun modelFor(id: ProviderId): String? =
        keyManager.selectPrimaryKey(id)?.activeModel ?: prefs.getString("model_${id.name}", null)

    fun setModel(p: Provider, model: String?) = setModelFor(p.toProviderId(), model)
    fun setModelFor(id: ProviderId, model: String?) {
        prefs.edit().putString("model_${id.name}", model).apply()
        keyManager.selectPrimaryKey(id)?.let { keyManager.setModelForKey(it.id, model) }
    }

    /** Set after the fast one-pass render failed once on this phone. */
    var safeRender: Boolean
        get() = prefs.getBoolean("safe_render", false)
        set(v) = prefs.edit().putBoolean("safe_render", v).apply()

    var totalPromptTokensUsed: Long
        get() = prefs.getLong("usage_input_tokens", 0L)
        private set(v) = prefs.edit().putLong("usage_input_tokens", v).apply()

    var totalCompletionTokensUsed: Long
        get() = prefs.getLong("usage_output_tokens", 0L)
        private set(v) = prefs.edit().putLong("usage_output_tokens", v).apply()

    fun recordUsage(inputTokens: Int, outputTokens: Int) {
        totalPromptTokensUsed += inputTokens.coerceAtLeast(0)
        totalCompletionTokensUsed += outputTokens.coerceAtLeast(0)
    }

    fun hasKey(id: ProviderId): Boolean = keyManager.hasEnabledNonInvalidKey(id)

    val hasActiveKey: Boolean
        get() = hasKey(ProviderId.GEMINI) || hasKey(ProviderId.GROQ) ||
            (privacy.claudePaidConsentGranted && hasKey(ProviderId.CLAUDE))

    fun allConfiguredKeys(): List<String> = keyManager.allDecryptedSecrets()
}
