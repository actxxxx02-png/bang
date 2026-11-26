package com.promptcut.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.ai.AiMessage
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiRole
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiToolCall
import com.promptcut.app.ai.AiToolSpec
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.ai.providers.ClaudeProvider
import com.promptcut.app.ai.providers.GeminiProvider
import com.promptcut.app.ai.providers.GroqProvider
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.SoftwareAesGcmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("promptcut", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun listModels_paginatesDeduplicatesAndMatchesExactGenerateContentMethod() = runTest {
        val urls = mutableListOf<String>()
        val provider = GeminiProvider(
            requestFn = { _, url, _, _, _ ->
                urls += url
                if ("pageToken=" !in url) {
                    JSONObject()
                        .put(
                            "models",
                            org.json.JSONArray()
                                .put(
                                    JSONObject()
                                        .put("name", "models/gemini-2.0-flash-batch-only")
                                        .put("supportedGenerationMethods", org.json.JSONArray().put("batchGenerateContent"))
                                )
                                .put(
                                    JSONObject()
                                        .put("name", "models/gemini-2.5-flash-lite")
                                        .put("supportedGenerationMethods", org.json.JSONArray().put("generateContent"))
                                )
                        )
                        .put("nextPageToken", "page 2")
                        .toString()
                } else {
                    JSONObject()
                        .put(
                            "models",
                            org.json.JSONArray()
                                .put(
                                    JSONObject()
                                        .put("name", "models/gemini-2.5-flash-lite")
                                        .put("supportedGenerationMethods", org.json.JSONArray().put("generateContent"))
                                )
                                .put(
                                    JSONObject()
                                        .put("name", "models/gemini-2.5-flash")
                                        .put("supportedGenerationMethods", org.json.JSONArray().put("generateContent"))
                                )
                        )
                        .toString()
                }
            }
        )

        val res = provider.listModels("AIzaSyFakeTestKey123456")
        assertTrue(res is AppResult.Success)
        val ranked = (res as AppResult.Success).value
        assertEquals(2, urls.size)
        assertTrue(urls[1].contains("pageToken=page+2"))
        assertFalse(ranked.contains("gemini-2.0-flash-batch-only"))
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite"), ranked)
    }

    @Test
    fun listModels_malformedJsonFailsCleanly() = runTest {
        val provider = GeminiProvider(requestFn = { _, _, _, _, _ -> "{not-valid-json" })
        val res = provider.listModels("AIzaSyFakeTestKey123456")
        assertTrue(res is AppResult.Failure)
        assertTrue((res as AppResult.Failure).error is AppError.Validation)
    }

    @Test
    fun buildPayload_extractsSystemRoleUsesCamelCaseAndHandlesFramesAndTools() {
        val provider = GeminiProvider()
        val req = AiRequest(
            systemPrompt = "Base system prompt",
            messages = listOf(
                AiMessage(AiRole.SYSTEM, "Extra project rule"),
                AiMessage(AiRole.USER, "Analyze this clip"),
                AiMessage(
                    AiRole.ASSISTANT,
                    "",
                    toolCalls = listOf(AiToolCall("tc1", "inspect_clips", """{"clip":0}""")),
                ),
                AiMessage(AiRole.TOOL, """{"duration":12.5}""", toolCallId = "tc1", toolName = "inspect_clips"),
                AiMessage(AiRole.USER, "Now cut at 3s"),
            ),
            frames = listOf(
                Frame("frame 0 at 1.0s", byteArrayOf(1, 2, 3)),
                Frame("frame 1 at 2.0s", byteArrayOf(4, 5, 6)),
            ),
            tools = listOf(
                AiToolSpec("trim_segment", "Trim", """{"type":"object","properties":{"start":{"type":"number"}}}""")
            ),
        )

        val payload = provider.buildPayload(req)
        assertTrue(payload.has("systemInstruction"))
        assertFalse(payload.has("system_instruction"))
        val sysText = payload.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(sysText.contains("Base system prompt"))
        assertTrue(sysText.contains("Extra project rule"))

        val contents = payload.getJSONArray("contents")
        assertEquals(4, contents.length())
        for (i in 0 until contents.length()) {
            val role = contents.getJSONObject(i).getString("role")
            assertTrue(role == "user" || role == "model")
        }

        // Last user message must hold ordered frame labels + camelCase inlineData
        val lastParts = contents.getJSONObject(3).getJSONArray("parts")
        assertEquals("Now cut at 3s", lastParts.getJSONObject(0).getString("text"))
        assertEquals("frame 0 at 1.0s", lastParts.getJSONObject(1).getString("text"))
        val inline0 = lastParts.getJSONObject(2).getJSONObject("inlineData")
        assertEquals("image/jpeg", inline0.getString("mimeType"))
        assertFalse(lastParts.getJSONObject(2).has("inline_data"))
        assertEquals("frame 1 at 2.0s", lastParts.getJSONObject(3).getString("text"))
        assertTrue(lastParts.getJSONObject(4).has("inlineData"))

        // Tool response must be encoded cleanly as functionResponse
        val toolParts = contents.getJSONObject(2).getJSONArray("parts")
        assertEquals("inspect_clips", toolParts.getJSONObject(0).getJSONObject("functionResponse").getString("name"))
    }

    @Test
    fun providers_rejectMalformedToolSchemaInsteadOfSendingEmptyObject() = runTest {
        val badReq = AiRequest(
            systemPrompt = "sys",
            userPrompt = "hi",
            tools = listOf(AiToolSpec("broken_tool", "Broken", "{not-json")),
        )

        val gemRes = GeminiProvider(requestFn = { _, _, _, _, _ -> "{}" }).complete("AIzaSyKey123", "gemini-2.5-flash", badReq)
        assertTrue(gemRes is AppResult.Failure)
        assertTrue((gemRes as AppResult.Failure).error is AppError.Validation)

        val groqPayloadFailed = runCatching {
            GeminiProvider.parseStrictToolSchema("broken_tool", "{not-json")
        }
        assertTrue(groqPayloadFailed.isFailure)

        val claudeProvider = ClaudeProvider(paidConsentCheck = { true })
        val claudeRes = claudeProvider.complete("sk-ant-Key123", "claude-sonnet-4-5", badReq)
        assertTrue(claudeRes is AppResult.Failure)
        assertTrue((claudeRes as AppResult.Failure).error is AppError.Validation)
    }

    @Test
    fun complete_fallsBackOn404AndNeverRetriesAuthAndDetectsSafetyBlock() = runTest {
        val triedModels = mutableListOf<String>()
        val fallbackProvider = GeminiProvider(
            requestFn = { method, url, _, _, _ ->
                if (method == "GET") {
                    JSONObject().put(
                        "models",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", "models/gemini-2.5-flash")
                                    .put("supportedGenerationMethods", org.json.JSONArray().put("generateContent"))
                            )
                    ).toString()
                } else {
                    triedModels += url
                    if ("missing-model" in url) throw ApiException("Not found", 404)
                    JSONObject()
                        .put(
                            "candidates",
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put("finishReason", "STOP")
                                    .put(
                                        "content",
                                        JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "ok-fallback")))
                                    )
                            )
                        )
                        .toString()
                }
            }
        )
        val ok = fallbackProvider.complete("AIzaSyKey123", "missing-model", AiRequest("sys", "hello"))
        assertTrue(ok is AppResult.Success)
        assertEquals("ok-fallback", (ok as AppResult.Success).value.text)
        assertEquals("gemini-2.5-flash", ok.value.model)
        assertEquals(2, triedModels.size)

        // Auth must never retry
        var authCalls = 0
        val authProvider = GeminiProvider(
            requestFn = { _, _, _, _, _ ->
                authCalls++
                throw ApiException("Invalid key", 401, "API_KEY_INVALID")
            }
        )
        val authFail = authProvider.complete("AIzaSyBadKey", "gemini-2.5-flash", AiRequest("sys", "hello"))
        assertTrue(authFail is AppResult.Failure)
        assertTrue((authFail as AppResult.Failure).error is AppError.Auth)
        assertEquals(1, authCalls)

        // Prompt blockReason / SAFETY finishReason must fail and never map to Network
        val blockedProvider = GeminiProvider(
            requestFn = { _, _, _, _, _ ->
                JSONObject()
                    .put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))
                    .toString()
            }
        )
        val blockedRes = blockedProvider.complete("AIzaSyGoodKey", "gemini-2.5-flash", AiRequest("sys", "blocked"))
        assertTrue(blockedRes is AppResult.Failure)
        assertFalse((blockedRes as AppResult.Failure).error is AppError.Network)
        assertTrue(blockedRes.error is AppError.Validation)
    }

    @Test
    fun stream_deduplicatesToolCallsCapturesUsageAndNeverRestartsAfterOutput() = runTest {
        val provider = GeminiProvider(
            streamFn = { _, _, _, _, onEvent ->
                onEvent(
                    null,
                    JSONObject()
                        .put(
                            "candidates",
                            org.json.JSONArray().put(
                                JSONObject().put(
                                    "content",
                                    JSONObject().put(
                                        "parts",
                                        org.json.JSONArray()
                                            .put(JSONObject().put("text", "Hello "))
                                            .put(
                                                JSONObject().put(
                                                    "functionCall",
                                                    JSONObject().put("name", "split_segment").put("args", JSONObject().put("sec", 2.0))
                                                )
                                            )
                                    )
                                )
                            )
                        )
                        .toString()
                )
                onEvent(
                    null,
                    JSONObject()
                        .put(
                            "candidates",
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put("finishReason", "STOP")
                                    .put(
                                        "content",
                                        JSONObject().put(
                                            "parts",
                                            org.json.JSONArray()
                                                .put(JSONObject().put("text", "world"))
                                                .put(
                                                    JSONObject().put(
                                                        "functionCall",
                                                        JSONObject().put("name", "split_segment").put("args", JSONObject().put("sec", 2.0))
                                                    )
                                                )
                                        )
                                    )
                            )
                        )
                        .put("usageMetadata", JSONObject().put("promptTokenCount", 42).put("candidatesTokenCount", 15))
                        .toString()
                )
            }
        )

        val events = provider.stream("AIzaSyKey123", "gemini-2.5-flash", AiRequest("sys", "split")).toList()
        val deltas = events.filterIsInstance<AiStreamEvent.Delta>()
        val tools = events.filterIsInstance<AiStreamEvent.ToolCallReady>()
        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()

        assertEquals(listOf("Hello ", "world"), deltas.map { it.text })
        assertEquals(1, tools.size)
        assertEquals(1, completed.response.toolCalls.size)
        assertEquals(42, completed.response.usage?.inputTokens)
        assertEquals(15, completed.response.usage?.outputTokens)

        // If stream fails mid-way AFTER emitting output, it MUST emit Error and NEVER restart
        var streamCalls = 0
        val partialFail = GeminiProvider(
            streamFn = { _, _, _, _, onEvent ->
                streamCalls++
                onEvent(
                    null,
                    JSONObject().put(
                        "candidates",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "partial ")))
                            )
                        )
                    ).toString()
                )
                throw ApiException("Overloaded", 503)
            }
        )
        val failEvents = partialFail.stream("AIzaSyKey123", "gemini-2.5-flash", AiRequest("sys", "hi")).toList()
        assertEquals(1, streamCalls)
        assertTrue(failEvents.first() is AiStreamEvent.Delta)
        assertTrue(failEvents.last() is AiStreamEvent.Error)
    }

    @Test
    fun stream_propagatesCancellationAndBlocksClaudeStreamWithoutConsent() = runTest {
        val cancellingProvider = GeminiProvider(
            streamFn = { _, _, _, _, _ -> throw CancellationException("user cancelled") }
        )
        var cancelled = false
        try {
            cancellingProvider.stream("AIzaSyKey123", "gemini-2.5-flash", AiRequest("sys", "hi")).toList()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)

        val settings = Settings(context, KeyVault(SoftwareAesGcmEngine()))
        settings.keyManager.addKey(ProviderId.CLAUDE, "Paid", "sk-ant-TestKey123456", setPreferred = true)
        settings.routingModeAuto = false
        settings.manualProviderId = ProviderId.CLAUDE
        val router = ProviderRouter(settings, retryBackoffBaseMs = 0L)
        val claudeEvents = router.stream(AiRequest("sys", "cut")).toList()
        assertTrue(claudeEvents.single() is AiStreamEvent.Error)
        assertTrue((claudeEvents.single() as AiStreamEvent.Error).error is AppError.PaidConsentRequired)
    }
}
