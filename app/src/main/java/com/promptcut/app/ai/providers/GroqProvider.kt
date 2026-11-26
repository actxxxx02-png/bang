package com.promptcut.app.ai.providers

import com.promptcut.app.ApiException
import com.promptcut.app.JsonRepair
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
import org.json.JSONObject

class GroqProvider : AiProvider {
    override val id: ProviderId = ProviderId.GROQ

    override val capabilities = AiCapabilities(
        supportsVision = false,
        supportsStreaming = true,
        supportsToolCalling = true,
        supportsJsonMode = true,
        maxFrames = 0,
        isFreeTierAvailable = true,
        requiresPaidConsent = false,
        contextWindowTokens = 128_000,
    )

    private val base = "https://api.groq.com/openai/v1"

    override suspend fun listModels(apiKey: String): AppResult<List<String>> = runCatchingResult("Groq") {
        val json = ProviderHttp.request(
            method = "GET",
            url = "$base/models",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = null,
            providerName = "Groq",
        )
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).mapNotNull {
            data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
        }
        ModelPicker.groqRanked(ids)
    }

    override suspend fun testConnection(apiKey: String, preferredModel: String?): AppResult<String> {
        return listModels(apiKey).flatMap { ranked ->
            val chosen = preferredModel?.takeIf { it in ranked } ?: ranked.firstOrNull()
            if (chosen == null) AppResult.Failure(AppError.ProviderUnavailable("Groq", 404, "No compatible Groq models available."))
            else AppResult.Success(chosen)
        }
    }

    override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> =
        runCatchingResult("Groq") {
            val rankedFn: suspend () -> List<String> = {
                when (val res = listModels(apiKey)) {
                    is AppResult.Success -> res.value
                    is AppResult.Failure -> throw when (val e = res.error) {
                        is AppError.Auth -> ApiException(e.message, e.httpCode, e.detail)
                        is AppError.RateLimit -> ApiException(e.message, e.httpCode, e.detail)
                        is AppError.QuotaExhausted -> ApiException(e.message, e.httpCode, e.detail)
                        is AppError.ProviderUnavailable -> ApiException(e.message, e.httpCode, e.detail)
                        is AppError.Network -> java.io.IOException(e.message)
                        is AppError.Timeout -> java.net.SocketTimeoutException(e.message)
                        else -> ApiException(e.message, 422, e.detail)
                    }
                }
            }
            var finalResp: AiResponse? = null
            val (usedModel, _) = tryModels(preferredModel, rankedFn, "Groq") { m ->
                val resp = completeSingle(apiKey, m, request)
                finalResp = resp
                resp.text
            }
            finalResp ?: AiResponse("", usedModel, ProviderId.GROQ)
        }

    override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flow {
        val model = preferredModel ?: when (val res = listModels(apiKey)) {
            is AppResult.Success -> res.value.firstOrNull() ?: "llama-3.3-70b-versatile"
            is AppResult.Failure -> {
                emit(AiStreamEvent.Error(res.error))
                return@flow
            }
        }
        val payload = buildPayload(model, request, strictJson = request.jsonMode && request.tools.isEmpty(), stream = true)
        val textBuf = StringBuilder()
        val toolAccum = linkedMapOf<Int, Triple<String, String, StringBuilder>>()
        var lastUsage: AiUsage? = null
        try {
            ProviderHttp.streamSse(
                url = "$base/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = payload.toString(),
                providerName = "Groq",
                timeoutMs = request.timeoutMs,
            ) { _, data ->
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: return@streamSse
                obj.optJSONObject("usage")?.let { u ->
                    lastUsage = AiUsage(u.optInt("prompt_tokens", 0), u.optInt("completion_tokens", 0), 0.0)
                }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return@streamSse
                val delta = choice.optJSONObject("delta") ?: return@streamSse
                val piece = delta.optString("content", "")
                if (piece.isNotEmpty()) {
                    textBuf.append(piece)
                    emit(AiStreamEvent.Delta(piece))
                }
                val tcs = delta.optJSONArray("tool_calls")
                if (tcs != null) {
                    for (i in 0 until tcs.length()) {
                        val tc = tcs.getJSONObject(i)
                        val idx = tc.optInt("index", i)
                        val fn = tc.optJSONObject("function")
                        val cur = toolAccum[idx]
                        val id = tc.optString("id", cur?.first ?: "groq_${System.nanoTime()}_$idx")
                        val name = fn?.optString("name", "")?.ifBlank { cur?.second.orEmpty() } ?: cur?.second.orEmpty()
                        val argBuf = cur?.third ?: StringBuilder()
                        argBuf.append(fn?.optString("arguments", "").orEmpty())
                        toolAccum[idx] = Triple(id, name, argBuf)
                    }
                }
            }
            val readyTools = toolAccum.values.mapNotNull { (id, name, args) ->
                if (name.isBlank()) null else AiToolCall(id, name, args.toString()).also { emit(AiStreamEvent.ToolCallReady(it)) }
            }
            emit(AiStreamEvent.Completed(AiResponse(textBuf.toString(), model, ProviderId.GROQ, readyTools, lastUsage)))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(AiStreamEvent.Error(AppError.fromThrowable(t, "Groq")))
        }
    }

    private suspend fun completeSingle(apiKey: String, model: String, request: AiRequest): AiResponse {
        val strict = request.jsonMode && request.tools.isEmpty()
        try {
            val raw = ProviderHttp.request(
                method = "POST",
                url = "$base/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = buildPayload(model, request, strictJson = strict, stream = false).toString(),
                providerName = "Groq",
                timeoutMs = request.timeoutMs,
            )
            return parseResponse(raw, model)
        } catch (e: ApiException) {
            if (!strict || e.code != 400 || ("json_validate_failed" !in e.body && "Failed to validate JSON" !in e.body)) {
                throw e
            }
            JsonRepair.failedGeneration(e.body)?.let { attempt ->
                JsonRepair.repairObject(attempt)?.let { repaired ->
                    return AiResponse(repaired, model, ProviderId.GROQ)
                }
            }
        }
        val plainRaw = try {
            val patched = request.copy(
                messages = request.messages.dropLast(1) + request.messages.last().let {
                    it.copy(content = it.content + "\n\nReply with ONE valid JSON object only. No comments, no trailing commas, no text outside it.")
                }
            )
            ProviderHttp.request(
                method = "POST",
                url = "$base/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = buildPayload(model, patched, strictJson = false, stream = false).toString(),
                providerName = "Groq",
                timeoutMs = request.timeoutMs,
            )
        } catch (e: ApiException) {
            if (e.code == 400) throw ApiException("Model $model couldn't produce valid JSON.", 422) else throw e
        }
        val parsed = parseResponse(plainRaw, model)
        val repaired = JsonRepair.repairObject(parsed.text)
            ?: throw ApiException("Model $model couldn't produce valid JSON.", 422)
        return parsed.copy(text = repaired)
    }

    private fun buildPayload(model: String, request: AiRequest, strictJson: Boolean, stream: Boolean): JSONObject {
        val msgs = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        }
        request.messages.forEach { m ->
            val obj = JSONObject()
            obj.put(
                "role",
                when (m.role) {
                    AiRole.SYSTEM -> "system"
                    AiRole.USER -> "user"
                    AiRole.ASSISTANT -> "assistant"
                    AiRole.TOOL -> "tool"
                }
            )
            if (m.role == AiRole.TOOL) {
                obj.put("tool_call_id", m.toolCallId ?: "")
                obj.put("content", m.content)
            } else {
                obj.put("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    val arr = JSONArray()
                    m.toolCalls.forEach { tc ->
                        arr.put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson))
                        )
                    }
                    obj.put("tool_calls", arr)
                }
            }
            msgs.put(obj)
        }

        val body = JSONObject()
            .put("model", model)
            .put("temperature", request.temperature)
            .put("max_completion_tokens", request.maxTokens.coerceAtLeast(512))
            .put("messages", msgs)
            .put("stream", stream)
        if (strictJson) body.put("response_format", JSONObject().put("type", "json_object"))
        if ("gpt-oss" in model) body.put("reasoning_effort", "low")
        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            request.tools.forEach { t ->
                require(t.name.isNotBlank()) { "Tool declaration name must not be blank." }
                val schema = GeminiProvider.parseStrictToolSchema(t.name, t.parametersJsonSchema)
                tools.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", schema)
                        )
                )
            }
            body.put("tools", tools)
        }
        return body
    }

    private fun parseResponse(raw: String, model: String): AiResponse {
        val root = JSONObject(raw)
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ApiException("Groq returned no choices.", 422)
        val text = msg.optString("content", "")
        val tcs = mutableListOf<AiToolCall>()
        val tcArr = msg.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.getJSONObject(i)
                val fn = tc.optJSONObject("function") ?: continue
                tcs += AiToolCall(
                    id = tc.optString("id", "groq_$i"),
                    name = fn.optString("name", ""),
                    argumentsJson = fn.optString("arguments", "{}"),
                )
            }
        }
        val usage = root.optJSONObject("usage")?.let { u ->
            AiUsage(u.optInt("prompt_tokens", 0), u.optInt("completion_tokens", 0), 0.0)
        }
        return AiResponse(text, model, ProviderId.GROQ, tcs, usage)
    }
}
