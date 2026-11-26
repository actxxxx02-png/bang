package com.promptcut.app.ai

import com.promptcut.app.Provider

enum class ProviderId(
    val displayName: String,
    val shortName: String,
    val keyPageUrl: String,
    val isPaidByDefault: Boolean,
) {
    GEMINI(
        displayName = "Google Gemini (Free tier)",
        shortName = "Gemini",
        keyPageUrl = "https://aistudio.google.com/apikey",
        isPaidByDefault = false,
    ),
    GROQ(
        displayName = "Groq (Free tier)",
        shortName = "Groq",
        keyPageUrl = "https://console.groq.com/keys",
        isPaidByDefault = false,
    ),
    CLAUDE(
        displayName = "Anthropic Claude (Paid API)",
        shortName = "Claude",
        keyPageUrl = "https://console.anthropic.com/settings/keys",
        isPaidByDefault = true,
    );

    fun toLegacyProvider(): Provider? = when (this) {
        GEMINI -> Provider.GEMINI
        GROQ -> Provider.GROQ
        CLAUDE -> null
    }
}

fun Provider.toProviderId(): ProviderId = when (this) {
    Provider.GEMINI -> ProviderId.GEMINI
    Provider.GROQ -> ProviderId.GROQ
}

data class AiCapabilities(
    val supportsVision: Boolean,
    val supportsStreaming: Boolean,
    val supportsToolCalling: Boolean,
    val supportsJsonMode: Boolean,
    val maxFrames: Int,
    val isFreeTierAvailable: Boolean,
    val requiresPaidConsent: Boolean,
    val contextWindowTokens: Int,
)
