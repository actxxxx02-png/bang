package com.promptcut.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.ai.AiCapabilities
import com.promptcut.app.ai.AiMessage
import com.promptcut.app.ai.AiProvider
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiRole
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiUsage
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.ai.TokenBudgetManager
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.SoftwareAesGcmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
class ProvidersAndRouterTest {

    private lateinit var settings: Settings
    private lateinit var fakeGemini: FakeProvider
    private lateinit var fakeGroq: FakeProvider
    private lateinit var fakeClaude: FakeProvider
    private lateinit var router: ProviderRouter

    private class FakeProvider(
        override val id: ProviderId,
        override val capabilities: AiCapabilities,
        var handler: (AiRequest) -> AppResult<AiResponse> = {
            AppResult.Success(AiResponse("ok", "${id.shortName}-model", id, usage = AiUsage(10, 5)))
        },
    ) : AiProvider {
        var calls = 0
        override suspend fun listModels(apiKey: String) = AppResult.Success(listOf("${id.shortName}-model"))
        override suspend fun testConnection(apiKey: String, preferredModel: String?) = AppResult.Success("${id.shortName}-model")
        override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> {
            calls++
            return handler(request)
        }
        override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flowOf()
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("promptcut", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(ctx, KeyVault(SoftwareAesGcmEngine()))
        fakeGemini = FakeProvider(
            ProviderId.GEMINI,
            AiCapabilities(true, true, true, true, 16, true, false, 128_000),
        )
        fakeGroq = FakeProvider(
            ProviderId.GROQ,
            AiCapabilities(false, true, true, true, 0, true, false, 32_000),
        )
        fakeClaude = FakeProvider(
            ProviderId.CLAUDE,
            AiCapabilities(true, true, true, true, 12, false, true, 200_000),
        )
        router = ProviderRouter(
            settings = settings,
            providers = mapOf(
                ProviderId.GEMINI to fakeGemini,
                ProviderId.GROQ to fakeGroq,
                ProviderId.CLAUDE to fakeClaude,
            ),
        )
    }

    @Test
    fun autoRouting_prefersFreeGeminiForVisionAndFreeGroqForText() {
        settings.setKeyFor(ProviderId.GEMINI, "AIzaSyGeminiKey123456")
        settings.setKeyFor(ProviderId.GROQ, "gsk_GroqKey123456")
        settings.setKeyFor(ProviderId.CLAUDE, "sk-ant-ClaudeKey123456")

        val vis = router.chooseProvider(needsVision = true).getOrNull()!!
        assertEquals(ProviderId.GEMINI, vis.providerId)
        assertFalse(vis.isPaid)

        val txt = router.chooseProvider(needsVision = false).getOrNull()!!
        assertEquals(ProviderId.GROQ, txt.providerId)
        assertFalse(txt.isPaid)
    }

    @Test
    fun autoRouting_neverSilentlySwitchesToPaidClaudeWhenFreeProvidersFailOrMissing() = runTest {
        settings.setKeyFor(ProviderId.CLAUDE, "sk-ant-ClaudeKey123456")
        // Even with consent granted, Auto mode must never route silently to Claude
        settings.privacy.claudePaidConsentGranted = true

        val res = router.chooseProvider(needsVision = false)
        assertTrue(res is AppResult.Failure)
        assertEquals(0, fakeClaude.calls)

        // Configure Groq & Gemini, rate-limit Groq -> fails over to free Gemini, NEVER Claude
        settings.setKeyFor(ProviderId.GROQ, "gsk_GroqKey123456")
        settings.setKeyFor(ProviderId.GEMINI, "AIzaSyGeminiKey123456")
        fakeGroq.handler = { AppResult.Failure(AppError.RateLimit("Groq", 429)) }

        val completeRes = router.complete(AiRequest("sys", "plan edit"), needsVision = false)
        assertTrue(completeRes is AppResult.Success)
        assertEquals(ProviderId.GEMINI, (completeRes as AppResult.Success).value.provider)
        assertEquals(0, fakeClaude.calls)
    }

    @Test
    fun manualClaude_requiresExplicitPaidConsent() = runTest {
        settings.setKeyFor(ProviderId.CLAUDE, "sk-ant-ClaudeKey123456")
        settings.routingModeAuto = false
        settings.manualProviderId = ProviderId.CLAUDE

        val blocked = router.complete(AiRequest("sys", "edit"), needsVision = false)
        assertTrue(blocked is AppResult.Failure)
        assertTrue((blocked as AppResult.Failure).error is AppError.PaidConsentRequired)
        assertEquals(0, fakeClaude.calls)

        settings.privacy.claudePaidConsentGranted = true
        val ok = router.complete(AiRequest("sys", "edit"), needsVision = false)
        assertTrue(ok is AppResult.Success)
        assertEquals(1, fakeClaude.calls)
    }

    @Test
    fun tokenBudgetAndConversationManager_compactAndRestoreMemory() {
        val conv = ConversationManager(TokenBudgetManager())
        conv.projectInstructions = "Fast gaming cuts, portrait 9:16"
        conv.selectedClipIndices = setOf(0, 2)
        conv.selectedSegmentId = "seg_9"
        conv.recordDecision("Use bold box captions")
        conv.recordOperation("split_segment: split clip 0 at 3.2s")

        repeat(20) { idx ->
            conv.addUserMessage("User instruction number $idx " + "detail ".repeat(40))
            conv.addAssistantMessage("Applied step $idx")
        }

        assertTrue(conv.rollingSummary.isNotBlank())
        val (msgs, report) = conv.buildBudgetedMessages(
            systemPrompt = "System prompt",
            latestUserPrompt = "Make the opening faster and add subtitles",
            frames = emptyList(),
            provider = ProviderId.GROQ,
        )
        assertTrue(report.estimatedTokens <= report.maxBudgetTokens)
        assertTrue(msgs.last().content.contains("PROJECT INSTRUCTIONS"))
        assertTrue(msgs.last().content.contains("seg_9"))

        val json = conv.toJson()
        val restored = ConversationManager()
        restored.restoreFromJson(json)
        assertEquals("Fast gaming cuts, portrait 9:16", restored.projectInstructions)
        assertEquals(setOf(0, 2), restored.selectedClipIndices)
        assertEquals("seg_9", restored.selectedSegmentId)
        assertTrue(restored.editingDecisions.contains("Use bold box captions"))
    }
}
