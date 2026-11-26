package com.promptcut.app.ai.providers

import android.util.Base64
import com.promptcut.app.ApiException
import com.promptcut.app.ModelPicker
import com.promptcut.app.ai.AiCapabilities
import com.promptcut.app.ai.AiProvider
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiRole
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiToolCall
import com.promptcut.app.ai.AiUsage
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.core.runCatchingResult
import com.promptcut.app.tryModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

class GeminiProvider(
    private val requestFn: suspend (
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ) -> String = { method, url, headers, body, timeoutMs ->
        ProviderHttp.request(
            method = method,
            url = url,
            headers = headers,
            body = body,
            providerName = "Gemini",
            timeoutMs = timeoutMs,
        )
    },
    private val streamFn: suspend (
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
        onEvent: suspend (event: String?, data: String) -> Unit,
    ) -> Unit = { url, headers, body, timeoutMs, onEvent ->
        ProviderHttp.streamSse(
            url = url,
            headers = headers,
            body = body,
            providerName = "Gemini",
            timeoutMs = timeoutMs,
            onEvent = onEvent,
        )
    },
) : AiProvider {
    override val id: ProviderId = ProviderId.GEMINI

    override val capabilities = AiCapabilities(
        supportsVision = true,
        supportsStreaming = true,
        supportsToolCalling = true,
        supportsJsonMode = true,
        maxFrames = 36,
        isFreeTierAvailable = true,
        requiresPaidConsent = false,
        contextWindowTokens = 1_000_000,
    )

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun listModels(apiKey: String): AppResult<List<String>> = runCatchingResult("Gemini") {
        val collected = LinkedHashSet<String>()
        val seenTokens = HashSet<String>()
        var pageToken: String? = null
        var pages = 0

        while (pages < MAX_MODEL_PAGES) {
            pages++
            val url = buildString {
                append("$base/models?pageSize=200")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=")
                    append(URLEncoder.encode(pageToken, "UTF-8"))
                }
            }
            val raw = requestFn("GET", url, mapOf("x-goog-api-key" to apiKey), null, 30_000L)
            val root = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw ApiException("Gemini returned malformed model list JSON.", 422)
            }
            val models = root.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsGenerate = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
                if (supportsGenerate) {
                    val cleanName = m.optString("name", "").removePrefix("models/").trim()
                    if (cleanName.isNotEmpty()) collected += cleanName
                }
            }
            val next = root.optString("nextPageToken", "").trim()
            if (next.isEmpty() || !seenTokens.add(next)) break
            pageToken = next
        }

        ModelPicker.geminiRanked(collected.toList())
    }

    override suspend fun testConnection(apiKey: String, preferredModel: String?): AppResult<String> {
        return listModels(apiKey).flatMap { ranked ->
            val chosen = preferredModel?.takeIf { it in ranked } ?: ranked.firstOrNull()
            if (chosen == null) AppResult.Failure(AppError.ProviderUnavailable("Gemini", 404, "No compatible Gemini models available."))
            else AppResult.Success(chosen)
        }
    }

    override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> =
        runCatchingResult("Gemini") {
            val bodyText = buildPayload(request).toString()
            val rankedFn: suspend () -> List<String> = {
                when (val res = listModels(apiKey)) {
                    is AppResult.Success -> res.value
                    is AppResult.Failure -> throw appErrorToThrowable(res.error)
                }
            }
            var parsedResp: AiResponse? = null
            val (usedModel, _) = tryModels(preferredModel, rankedFn, "Gemini") { m ->
                val raw = requestFn(
                    "POST",
                    "$base/models/$m:generateContent",
                    mapOf("x-goog-api-key" to apiKey),
                    bodyText,
                    request.timeoutMs,
                )
                parsedResp = parseResponse(raw, m, allowEmpty = false)
                parsedResp!!.text
            }
            parsedResp ?: throw ApiException("Gemini returned no response for $usedModel.", 422)
        }

    override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flow {
        val bodyText = try {
            buildPayload(request).toString()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(AiStreamEvent.Error(AppError.fromThrowable(t, "Gemini")))
            return@flow
        }

        val candidates = LinkedHashSet<String>()
        var listed = false
        suspend fun ensureListed(): AppError? {
            if (listed) return null
            listed = true
            return when (val res = listModels(apiKey)) {
                is AppResult.Success -> {
                    candidates += res.value
                    null
                }
                is AppResult.Failure -> res.error
            }
        }

        if (!preferredModel.isNullOrBlank()) {
            candidates += preferredModel
        } else {
            val listErr = ensureListed()
            if (listErr != null) {
                emit(AiStreamEvent.Error(listErr))
                return@flow
            }
        }

        val tried = mutableListOf<String>()
        var candidateIdx = 0
        var lastError: AppError? = null

        while (tried.size < MAX_STREAM_MODELS) {
            if (candidateIdx >= candidates.size) {
                if (listed) break
                val listErr = ensureListed()
                if (listErr != null) {
                    if (lastError == null) {
                        emit(AiStreamEvent.Error(listErr))
                        return@flow
                    }
                    break
                }
                if (candidateIdx >= candidates.size) break
            }

            val model = candidates.elementAt(candidateIdx++)
            tried += model

            var emittedOutput = false
            val fullText = StringBuilder()
            val toolCalls = mutableListOf<AiToolCall>()
            val seenToolSignatures = HashSet<String>()
            var lastUsage: AiUsage? = null

            try {
                streamFn(
                    "$base/models/$model:streamGenerateContent?alt=sse",
                    mapOf("x-goog-api-key" to apiKey),
                    bodyText,
                    request.timeoutMs,
                ) { _, data ->
                    val chunk = parseResponse(data, model, allowEmpty = true)
                    if (chunk.text.isNotEmpty()) {
                        emittedOutput = true
                        fullText.append(chunk.text)
                        emit(AiStreamEvent.Delta(chunk.text))
                    }
                    chunk.toolCalls.forEach { tc ->
                        val sig = "${tc.name}|${tc.argumentsJson}"
                        if (seenToolSignatures.add(sig)) {
                            emittedOutput = true
                            toolCalls += tc
                            emit(AiStreamEvent.ToolCallReady(tc))
                        }
                    }
                    if (chunk.usage != null) lastUsage = chunk.usage
                }

                if (fullText.isEmpty() && toolCalls.isEmpty()) {
                    throw ApiException("Gemini returned an empty stream response for $model.", 422)
                }

                emit(AiStreamEvent.Completed(AiResponse(fullText.toString(), model, ProviderId.GEMINI, toolCalls, lastUsage)))
                return@flow
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val err = AppError.fromThrowable(t, "Gemini")
                lastError = err
                val canFallbackModel = !emittedOutput && (
                    (err is AppError.ProviderUnavailable) ||
                        (err is AppError.Validation && (t is ApiException && (t.code == 404 || t.code == 422))) ||
                        err is AppError.RateLimit
                    )
                if (!canFallbackModel) {
                    emit(AiStreamEvent.Error(err))
                    return@flow
                }
            }
        }

        emit(
            AiStreamEvent.Error(
                lastError ?: AppError.ProviderUnavailable("Gemini", 404, "No compatible Gemini model available.")
            )
        )
    }

    internal fun buildPayload(request: AiRequest): JSONObject {
        val systemParts = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) systemParts += request.systemPrompt.trim()
        request.messages.filter { it.role == AiRole.SYSTEM && it.content.isNotBlank() }
            .forEach { systemParts += it.content.trim() }

        val contents = JSONArray()
        val lastUserIdx = request.messages.indexOfLast { it.role == AiRole.USER }
        var attachedFrames = false

        request.messages.forEachIndexed { idx, msg ->
            if (msg.role == AiRole.SYSTEM) return@forEachIndexed
            val parts = JSONArray()
            if (msg.role == AiRole.TOOL) {
                val toolName = msg.toolName?.trim().orEmpty().ifBlank {
                    throw IllegalArgumentException("Gemini tool response requires a non-blank toolName.")
                }
                val responseObj = runCatching { JSONObject(msg.content) }
                    .getOrElse { JSONObject().put("result", msg.content) }
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", toolName)
                            .put("response", responseObj),
                    )
                )
            } else {
                if (msg.content.isNotBlank()) parts.put(JSONObject().put("text", msg.content))
                msg.toolCalls.forEach { tc ->
                    val argsObj = try {
                        if (tc.argumentsJson.isBlank()) JSONObject() else JSONObject(tc.argumentsJson)
                    } catch (e: JSONException) {
                        throw IllegalArgumentException("Invalid JSON arguments for tool call '${tc.name}'.", e)
                    }
                    parts.put(
                        JSONObject().put(
                            "functionCall",
                            JSONObject()
                                .put("name", tc.name)
                                .put("args", argsObj),
                        )
                    )
                }
                if (msg.role == AiRole.USER && idx == lastUserIdx && request.frames.isNotEmpty()) {
                    appendFrameParts(parts, request)
                    attachedFrames = true
                }
            }
            val role = if (msg.role == AiRole.ASSISTANT) "model" else "user"
            if (parts.length() > 0) {
                contents.put(JSONObject().put("role", role).put("parts", parts))
            }
        }

        if (!attachedFrames && request.frames.isNotEmpty()) {
            val frameParts = JSONArray()
            appendFrameParts(frameParts, request)
            contents.put(JSONObject().put("role", "user").put("parts", frameParts))
        }

        val genConfig = JSONObject()
            .put("temperature", request.temperature)
            .put("maxOutputTokens", request.maxTokens)
        if (request.jsonMode && request.tools.isEmpty()) {
            genConfig.put("responseMimeType", "application/json")
        }

        val payload = JSONObject()
            .put("contents", contents)
            .put("generationConfig", genConfig)

        if (systemParts.isNotEmpty()) {
            payload.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemParts.joinToString("\n\n"))),
                ),
            )
        }

        if (request.tools.isNotEmpty()) {
            val decls = JSONArray()
            request.tools.forEach { t ->
                require(t.name.isNotBlank()) { "Tool declaration name must not be blank." }
                val schema = parseStrictToolSchema(t.name, t.parametersJsonSchema)
                decls.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", schema)
                )
            }
            payload.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        return payload
    }

    private fun appendFrameParts(parts: JSONArray, request: AiRequest) {
        request.frames.take(capabilities.maxFrames).forEach { f ->
            if (f.label.isNotBlank()) {
                parts.put(JSONObject().put("text", f.label))
            }
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(f.jpeg, Base64.NO_WRAP)),
                )
            )
        }
    }

    internal fun parseResponse(raw: String, model: String, allowEmpty: Boolean = false): AiResponse {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ApiException("Gemini returned malformed JSON.", 422)
        }

        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "")?.trim().orEmpty()
        if (blockReason.isNotEmpty()) {
            throw ApiException("Gemini blocked the prompt ($blockReason). Try rephrasing.", 422)
        }

        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        val finishReason = cand?.optString("finishReason", "")?.trim().orEmpty()
        if (finishReason in BLOCKING_FINISH_REASONS) {
            throw ApiException("Gemini stopped due to $finishReason. Try rephrasing.", 422)
        }

        if (cand == null && !allowEmpty) {
            throw ApiException("Gemini returned no answer (it may have been blocked). Try rephrasing.", 422)
        }

        val outParts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()
        for (i in 0 until outParts.length()) {
            val part = outParts.optJSONObject(i) ?: continue
            val txt = part.optString("text", "")
            if (txt.isNotEmpty()) sb.append(txt)
            val fc = part.optJSONObject("functionCall")
            if (fc != null) {
                val name = fc.optString("name", "").trim()
                val args = fc.optJSONObject("args")?.toString() ?: "{}"
                if (name.isNotBlank()) {
                    toolCalls += AiToolCall(id = "gemini_${System.nanoTime()}_$i", name = name, argumentsJson = args)
                }
            }
        }

        if (!allowEmpty && sb.isEmpty() && toolCalls.isEmpty()) {
            throw ApiException("Gemini returned an empty response.", 422)
        }

        val um = root.optJSONObject("usageMetadata")
        val usage = um?.let {
            AiUsage(
                inputTokens = it.optInt("promptTokenCount", 0),
                outputTokens = it.optInt("candidatesTokenCount", 0),
                estimatedCostUsd = 0.0,
            )
        }
        return AiResponse(sb.toString(), model, ProviderId.GEMINI, toolCalls, usage)
    }

    private fun appErrorToThrowable(err: AppError): Throwable = when (err) {
        is AppError.Auth -> ApiException(err.message, err.httpCode, err.detail)
        is AppError.RateLimit -> ApiException(err.message, err.httpCode, err.detail)
        is AppError.QuotaExhausted -> ApiException(err.message, err.httpCode, err.detail)
        is AppError.ProviderUnavailable -> ApiException(err.message, err.httpCode, err.detail)
        is AppError.Timeout -> SocketTimeoutException(err.message)
        is AppError.Network -> IOException(err.message)
        else -> ApiException(err.message, 422, err.detail)
    }

    companion object {
        private const val MAX_MODEL_PAGES = 10
        private const val MAX_STREAM_MODELS = 4
        private val BLOCKING_FINISH_REASONS = setOf(
            "SAFETY",
            "RECITATION",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY",
            "MALFORMED_FUNCTION_CALL",
        )

        internal fun parseStrictToolSchema(toolName: String, rawSchema: String): JSONObject {
            if (rawSchema.isBlank()) {
                throw IllegalArgumentException("Tool '$toolName' must define a non-empty JSON schema.")
            }
            val parsed = try {
                JSONObject(rawSchema)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Tool '$toolName' has an invalid JSON schema.", e)
            }
            if (parsed.length() == 0) {
                throw IllegalArgumentException("Tool '$toolName' JSON schema cannot be empty.")
            }
            return parsed
        }
    }
}
