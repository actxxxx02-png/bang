package com.promptcut.app.ai.providers

import com.promptcut.app.ApiException
import com.promptcut.app.security.KeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP & SSE transport with strict timeout, coroutine cancellation, and secret redaction.
 */
internal object ProviderHttp {

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        providerName: String,
        timeoutMs: Long = 90_000L,
    ): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = timeoutMs.toInt().coerceIn(15_000, 180_000)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    val safeBody = KeyVault.redactSecrets(raw)
                    throw ApiException(friendlyHttpError(providerName, code, safeBody), code, safeBody)
                }
                raw
            } catch (c: CancellationException) {
                throw c
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                val safeMsg = KeyVault.redactSecrets(e.message ?: "no connection")
                throw ApiException("Network problem reaching $providerName: $safeMsg.")
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun streamSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        providerName: String,
        timeoutMs: Long = 120_000L,
        onEvent: suspend (eventName: String?, data: String) -> Unit,
    ) = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = timeoutMs.toInt().coerceIn(20_000, 180_000)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val safeBody = KeyVault.redactSecrets(conn.errorStream?.bufferedReader()?.readText().orEmpty())
                    throw ApiException(friendlyHttpError(providerName, code, safeBody), code, safeBody)
                }
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var currentEvent: String? = null
                    val dataBuf = StringBuilder()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                if (dataBuf.isNotEmpty()) {
                                    onEvent(currentEvent, dataBuf.toString().trimEnd())
                                    dataBuf.clear()
                                    currentEvent = null
                                }
                            }
                            line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                val d = line.removePrefix("data:").trimStart()
                                if (d == "[DONE]") break
                                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                dataBuf.append(d)
                            }
                        }
                    }
                    if (dataBuf.isNotEmpty()) {
                        onEvent(currentEvent, dataBuf.toString().trimEnd())
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                val safeMsg = KeyVault.redactSecrets(e.message ?: "network error")
                throw ApiException("Streaming interrupted for $providerName: $safeMsg.")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun friendlyHttpError(providerName: String, code: Int, body: String): String {
        val detail = KeyVault.redactSecrets(
            runCatching {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: o.optString("message")
            }.getOrNull().orEmpty().take(200)
        )
        val lower = detail.lowercase()
        val isQuota = "quota" in lower || "billing" in lower || "credit" in lower || "daily limit" in lower
        return when (code) {
            400 -> if ("api key" in lower || "api_key" in lower || "invalid x-api-key" in lower) {
                "Your $providerName API key isn't valid. Open Settings to check or rotate keys."
            } else "$providerName rejected the request: $detail"
            401 -> "Your $providerName API key isn't valid or lacks access. Open Settings and check it."
            403 -> if (isQuota) {
                "Quota or billing credit exhausted on $providerName."
            } else {
                "Your $providerName API key isn't valid or lacks access. Open Settings and check it."
            }
            402 -> "Quota or billing credit exhausted on $providerName."
            404 -> "The selected $providerName model isn't available. Test the connection in Settings to pick a current model."
            429 -> if (isQuota) {
                "Quota exhausted on $providerName key. Add or select another key in Settings."
            } else {
                "Rate limit reached on $providerName. Waiting for cooldown or rotating to another eligible key."
            }
            in 500..599 -> "$providerName servers are busy ($code). Try again in a moment."
            else -> "$providerName error $code: $detail"
        }
    }
}
