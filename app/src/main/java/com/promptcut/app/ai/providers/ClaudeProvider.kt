package com.promptcut.app.ai.providers

import android.util.Base64
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Claude API adapter (`/v1/messages`).
 * Paid-API safety: callers MUST enforce explicit paid consent via [PrivacyManager] / [ProviderRouter].
 */
class ClaudeProvider(
    private val paidConsentCheck: () -> Boolean = { false },
) : AiProvider {

    override val id: ProviderId = ProviderId.CLAUDE

    override val capabilities = AiCapabilities(
        supportsVision = true,
        supportsStreaming = true,
        supportsToolCalling = true,
        supportsJsonMode = false,
        maxFrames = 20,
        isFreeTierAvailable = false,
        requiresPaidConsent = true,
        contextWindowTokens = 200_000,
    )

    private val base = "https://api.anthropic.com/v1"
    private val apiVersion = "2023-06-01"

    private fun headers(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to apiVersion,
    )

    override suspend fun listModels(apiKey: String): AppResult<List<String>> = runCatchingResult("Claude") {
        val json = ProviderHttp.request(
            method = "GET",
            url = "$base/models?limit=50",
            headers = headers(apiKey),
            body = null,
            providerName = "Claude",
        )
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        rankClaudeModels(ids)
    }

    override suspend fun testConnection(apiKey: String, preferredModel: String?): AppResult<String> {
        if (!paidConsentCheck()) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        return listModels(apiKey).flatMap { ranked ->
            val chosen = preferredModel?.takeIf { it in ranked } ?: ranked.firstOrNull() ?: DEFAULT_MODEL
            AppResult.Success(chosen)
        }
    }

    override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> {
        if (!paidConsentCheck()) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        return runCatchingResult("Claude") {
            val model = preferredModel ?: when (val r = listModels(apiKey)) {
                is AppResult.Success -> r.value.firstOrNull() ?: DEFAULT_MODEL
                is AppResult.Failure -> DEFAULT_MODEL
            }
            val payload = buildPayload(model, request, stream = false)
            val raw = ProviderHttp.request(
                method = "POST",
                url = "$base/messages",
                headers = headers(apiKey),
                body = payload.toString(),
                providerName = "Claude",
                timeoutMs = request.timeoutMs,
            )
            parseResponse(raw, model)
        }
    }

    override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flow {
        if (!paidConsentCheck()) {
            emit(AiStreamEvent.Error(AppError.PaidConsentRequired("Anthropic Claude")))
            return@flow
        }
        val model = preferredModel ?: when (val r = listModels(apiKey)) {
            is AppResult.Success -> r.value.firstOrNull() ?: DEFAULT_MODEL
            is AppResult.Failure -> DEFAULT_MODEL
        }
        val payload = buildPayload(model, request, stream = true)
        val textBuf = StringBuilder()
        var currentToolId: String? = null
        var currentToolName: String? = null
        val currentToolArgs = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()
        var inTok = 0
        var outTok = 0

        try {
            ProviderHttp.streamSse(
                url = "$base/messages",
                headers = headers(apiKey),
                body = payload.toString(),
                providerName = "Claude",
                timeoutMs = request.timeoutMs,
            ) { eventName, data ->
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: return@streamSse
                when (eventName ?: obj.optString("type")) {
                    "message_start" -> {
                        val u = obj.optJSONObject("message")?.optJSONObject("usage")
                        if (u != null) inTok = u.optInt("input_tokens", inTok)
                    }
                    "content_block_start" -> {
                        val cb = obj.optJSONObject("content_block")
                        if (cb?.optString("type") == "tool_use") {
                            currentToolId = cb.optString("id", "claude_${System.nanoTime()}")
                            currentToolName = cb.optString("name", "")
                            currentToolArgs.clear()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta") ?: return@streamSse
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val t = delta.optString("text", "")
                                if (t.isNotEmpty()) {
                                    textBuf.append(t)
                                    emit(AiStreamEvent.Delta(t))
                                }
                            }
                            "input_json_delta" -> {
                                currentToolArgs.append(delta.optString("partial_json", ""))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val name = currentToolName
                        val id = currentToolId
                        if (!name.isNullOrBlank() && !id.isNullOrBlank()) {
                            val tc = AiToolCall(id, name, currentToolArgs.toString().ifBlank { "{}" })
                            toolCalls += tc
                            emit(AiStreamEvent.ToolCallReady(tc))
                        }
                        currentToolId = null
                        currentToolName = null
                        currentToolArgs.clear()
                    }
                    "message_delta" -> {
                        val u = obj.optJSONObject("usage")
                        if (u != null) outTok = u.optInt("output_tokens", outTok)
                    }
                }
            }
            val usage = AiUsage(inTok, outTok, estimateCostUsd(model, inTok, outTok))
            emit(AiStreamEvent.Completed(AiResponse(textBuf.toString(), model, ProviderId.CLAUDE, toolCalls, usage)))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(AiStreamEvent.Error(AppError.fromThrowable(t, "Claude")))
        }
    }

    private fun buildPayload(model: String, request: AiRequest, stream: Boolean): JSONObject {
        val sysParts = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) sysParts += request.systemPrompt.trim()
        request.messages.filter { it.role == AiRole.SYSTEM && it.content.isNotBlank() }
            .forEach { sysParts += it.content.trim() }

        val msgs = JSONArray()
        val lastUserIdx = request.messages.indexOfLast { it.role == AiRole.USER }
        var attachedFrames = false

        request.messages.forEachIndexed { idx, m ->
            if (m.role == AiRole.SYSTEM) return@forEachIndexed
            val content = JSONArray()
            if (m.role == AiRole.TOOL) {
                content.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId ?: "")
                        .put("content", m.content)
                )
            } else {
                if (m.content.isNotBlank()) {
                    content.put(JSONObject().put("type", "text").put("text", m.content))
                }
                m.toolCalls.forEach { tc ->
                    content.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", tc.id)
                            .put("name", tc.name)
                            .put("input", runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() })
                    )
                }
                if (m.role == AiRole.USER && idx == lastUserIdx && request.frames.isNotEmpty()) {
                    appendFrameBlocks(content, request)
                    attachedFrames = true
                }
            }
            val role = if (m.role == AiRole.ASSISTANT) "assistant" else "user"
            if (content.length() > 0) {
                msgs.put(JSONObject().put("role", role).put("content", content))
            }
        }

        if (!attachedFrames && request.frames.isNotEmpty()) {
            val content = JSONArray()
            appendFrameBlocks(content, request)
            msgs.put(JSONObject().put("role", "user").put("content", content))
        }

        val sys = buildString {
            append(sysParts.joinToString("\n\n"))
            if (request.jsonMode && request.tools.isEmpty()) {
                if (isNotBlank()) append("\n\n")
                append("Return ONLY a valid JSON object without markdown fences or extra commentary.")
            }
        }

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", request.maxTokens.coerceIn(256, 8192))
            .put("temperature", request.temperature.coerceIn(0.0, 1.0))
            .put("messages", msgs)
            .put("stream", stream)
        if (sys.isNotBlank()) payload.put("system", sys)
        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            request.tools.forEach { t ->
                require(t.name.isNotBlank()) { "Tool declaration name must not be blank." }
                val schema = GeminiProvider.parseStrictToolSchema(t.name, t.parametersJsonSchema)
                tools.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("input_schema", schema)
                )
            }
            payload.put("tools", tools)
        }
        return payload
    }

    private fun appendFrameBlocks(content: JSONArray, request: AiRequest) {
        request.frames.take(capabilities.maxFrames).forEach { f ->
            if (f.label.isNotBlank()) {
                content.put(JSONObject().put("type", "text").put("text", f.label))
            }
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", Base64.encodeToString(f.jpeg, Base64.NO_WRAP)),
                    )
            )
        }
    }

    private fun parseResponse(raw: String, model: String): AiResponse {
        val root = JSONObject(raw)
        val content = root.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        val tcs = mutableListOf<AiToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> sb.append(block.optString("text", ""))
                "tool_use" -> tcs += AiToolCall(
                    id = block.optString("id", "claude_$i"),
                    name = block.optString("name", ""),
                    argumentsJson = block.optJSONObject("input")?.toString() ?: "{}",
                )
            }
        }
        val usage = root.optJSONObject("usage")?.let { u ->
            val inp = u.optInt("input_tokens", 0)
            val out = u.optInt("output_tokens", 0)
            AiUsage(inp, out, estimateCostUsd(model, inp, out))
        }
        return AiResponse(sb.toString(), model, ProviderId.CLAUDE, tcs, usage)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-5"

        fun rankClaudeModels(ids: List<String>): List<String> {
            val claude = ids.filter { "claude" in it.lowercase() }
            if (claude.isEmpty()) return listOf("claude-sonnet-4-5", "claude-haiku-4-5")
            return claude.sortedWith(
                compareByDescending<String> { "sonnet" in it.lowercase() }
                    .thenByDescending { "4-5" in it || "4.5" in it || "4-" in it }
                    .thenByDescending { "haiku" in it.lowercase() }
            )
        }

        fun estimateCostUsd(model: String, inputTokens: Int, outputTokens: Int): Double {
            val (inPerM, outPerM) = when {
                "haiku" in model.lowercase() -> 0.80 to 4.00
                "opus" in model.lowercase() -> 15.0 to 75.0
                else -> 3.0 to 15.0
            }
            return (inputTokens * inPerM + outputTokens * outPerM) / 1_000_000.0
        }
    }
}
