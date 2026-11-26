package com.promptcut.app.core

import com.promptcut.app.security.KeyVault
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Typed error hierarchy across PromptCut: security, networking, AI providers,
 * timeline validation, media analysis, rendering, and project storage.
 */
sealed class AppError(
    open val message: String,
    open val detail: String = "",
    open val retryable: Boolean = false,
    open val cause: Throwable? = null,
) {
    data class Network(
        override val message: String = "Network connection unavailable. Check your internet and try again.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Timeout(
        override val message: String = "The request timed out. Try again or shorten your clips.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Cancelled(
        override val message: String = "Operation cancelled.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Auth(
        val providerName: String,
        val httpCode: Int = 401,
        override val message: String = "Your $providerName API key was rejected. Open Settings and check or rotate your key.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class RateLimit(
        val providerName: String,
        val httpCode: Int = 429,
        override val message: String = "Rate limit reached on $providerName. Waiting for cooldown or rotating to another eligible key.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class QuotaExhausted(
        val providerName: String,
        val httpCode: Int = 429,
        override val message: String = "Quota exhausted for this $providerName key. Switch or add another key in Settings.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class ProviderUnavailable(
        val providerName: String,
        val httpCode: Int = 503,
        override val message: String = "$providerName is temporarily busy ($httpCode). Try again shortly.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class PaidConsentRequired(
        val providerName: String = "Anthropic Claude",
        override val message: String = "$providerName uses paid API credits. Enable paid API consent in Settings first.",
        override val detail: String = "",
    ) : AppError(message, detail, retryable = false)

    data class PrivacyBlocked(
        override val message: String,
        override val detail: String = "",
    ) : AppError(message, detail, retryable = false)

    data class Security(
        override val message: String = "Secure key storage failed. Please re-enter your API key.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Validation(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Media(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Render(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Storage(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Unknown(
        override val message: String = "Something went wrong.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    companion object {
        private val authHints = listOf(
            "api key",
            "api_key",
            "api_key_invalid",
            "invalid_api_key",
            "invalid x-api-key",
            "authentication_error",
            "invalid token",
            "unauthorized",
        )

        private val quotaHints = listOf(
            "insufficient_quota",
            "exceeded your current quota",
            "quota exceeded",
            "credit balance is too low",
            "billing",
            "daily limit",
            "quota",
        )

        fun fromThrowable(t: Throwable, providerName: String = "AI service"): AppError = when (t) {
            is TimeoutCancellationException, is SocketTimeoutException ->
                Timeout(detail = KeyVault.redactSecrets(t.message.orEmpty()), cause = t)
            is CancellationException -> throw t
            is UnknownHostException ->
                Network("No internet connection. Check Wi-Fi or mobile data.", KeyVault.redactSecrets(t.message.orEmpty()), t)
            is IOException ->
                Network("Network error: ${KeyVault.redactSecrets(t.message ?: "connection interrupted")}.", KeyVault.redactSecrets(t.message.orEmpty()), t)
            is com.promptcut.app.ApiException -> {
                val safeMsg = KeyVault.redactSecrets(t.message.orEmpty())
                val safeBody = KeyVault.redactSecrets(t.body.take(240))
                val lower = "${safeMsg.lowercase()} ${safeBody.lowercase()}"
                when {
                    t.code == 0 -> Network(safeMsg.ifBlank { "Network problem reaching $providerName." }, safeBody, t)
                    t.code == 402 || ((t.code == 429 || t.code == 403) && quotaHints.any { it in lower }) ->
                        QuotaExhausted(providerName, t.code, safeMsg.ifBlank { "Quota exhausted for $providerName." }, safeBody, t)
                    t.code == 401 || t.code == 403 || (t.code == 400 && authHints.any { it in lower }) ->
                        Auth(providerName, t.code, safeMsg.ifBlank { "Invalid API key." }, safeBody, t)
                    t.code == 429 ->
                        RateLimit(providerName, 429, safeMsg.ifBlank { "Rate limit reached on $providerName." }, safeBody, t)
                    t.code in 500..599 ->
                        ProviderUnavailable(providerName, t.code, safeMsg.ifBlank { "$providerName is busy." }, safeBody, t)
                    else ->
                        Validation(safeMsg.ifBlank { "Request failed (${t.code})." }, safeBody, t)
                }
            }
            is com.promptcut.app.RenderException ->
                Render(t.message ?: "Video rendering failed.", cause = t)
            is com.promptcut.app.PlanException ->
                Validation(t.message ?: "Invalid edit plan.", cause = t)
            is IllegalArgumentException ->
                Validation(KeyVault.redactSecrets(t.message ?: "Invalid request parameters."), cause = t)
            else -> Unknown(KeyVault.redactSecrets(t.message ?: "Unexpected error."), cause = t)
        }
    }
}
