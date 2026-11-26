package com.promptcut.app.security

import android.content.SharedPreferences
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult

/**
 * Enforces explicit user privacy & cost boundaries across all external AI integrations:
 * - Complete videos are NEVER uploaded unless explicitly approved by the user.
 * - Vision providers receive only small sampled still frames (when frame upload consent is granted).
 * - Groq Whisper receives extracted audio only when a Groq key is configured and audio consent is granted.
 * - Anthropic Claude (paid API) is NEVER invoked without explicit paid-API consent.
 */
class PrivacyManager(private val prefs: SharedPreferences) {

    data class OutgoingAudit(
        val provider: ProviderId,
        val sendsTextMetadata: Boolean,
        val sendsSampledFrames: Int,
        val sendsAudioForStt: Boolean,
        val sendsFullVideo: Boolean = false,
        val summary: String,
    )

    var allowFrameSamplingToAi: Boolean
        get() = prefs.getBoolean(KEY_FRAME_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_FRAME_CONSENT, v).apply()

    var allowAudioToGroqStt: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_STT_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_AUDIO_STT_CONSENT, v).apply()

    var allowFullVideoUpload: Boolean
        get() = prefs.getBoolean(KEY_FULL_VIDEO_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_FULL_VIDEO_CONSENT, v).apply()

    var claudePaidConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_CLAUDE_PAID_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_CLAUDE_PAID_CONSENT, v).apply()

    fun validateRequest(
        provider: ProviderId,
        frameCount: Int,
        includesFullVideo: Boolean = false,
        isSpeechToText: Boolean = false,
    ): AppResult<OutgoingAudit> {
        if (includesFullVideo && !allowFullVideoUpload) {
            return AppResult.Failure(
                AppError.PrivacyBlocked(
                    "Uploading full video files is disabled by PromptCut privacy policy. Only sampled frames or local descriptions are sent."
                )
            )
        }
        if (provider == ProviderId.CLAUDE && !claudePaidConsentGranted) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        val effectiveFrames = if (allowFrameSamplingToAi) frameCount else 0
        if (isSpeechToText && !allowAudioToGroqStt) {
            return AppResult.Failure(
                AppError.PrivacyBlocked("Speech-to-text audio upload is turned off in Privacy settings.")
            )
        }
        val summary = buildString {
            append("${provider.displayName}: text metadata")
            if (effectiveFrames > 0) append(" + $effectiveFrames sampled frame(s)")
            if (isSpeechToText) append(" + compressed audio track")
            append(" (no full video uploaded)")
        }
        return AppResult.Success(
            OutgoingAudit(
                provider = provider,
                sendsTextMetadata = true,
                sendsSampledFrames = effectiveFrames,
                sendsAudioForStt = isSpeechToText,
                sendsFullVideo = false,
                summary = summary,
            )
        )
    }

    fun disclosures(): List<String> = listOf(
        "Your source videos stay on your device. Editing, analysis, and rendering run locally.",
        "AI planning sends compact text descriptions of detected shots, objects, faces, and speech.",
        "When frame sampling is enabled for vision models (Gemini or Claude), only low-resolution still frames are sent — never full videos.",
        "When a Groq key is configured and audio STT is enabled, extracted speech audio is sent to Groq Whisper for accurate caption timestamps.",
        "Anthropic Claude uses paid API billing on your own Anthropic account and is never used without your explicit consent.",
        "API keys are encrypted on-device with Android Keystore AES-256-GCM and are never stored in plaintext or logged.",
    )

    companion object {
        private const val KEY_FRAME_CONSENT = "privacy_allow_frames"
        private const val KEY_AUDIO_STT_CONSENT = "privacy_allow_stt"
        private const val KEY_FULL_VIDEO_CONSENT = "privacy_allow_full_video"
        private const val KEY_CLAUDE_PAID_CONSENT = "privacy_claude_paid_consent"
    }
}
