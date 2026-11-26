package com.promptcut.app.agent

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.EditPlan
import com.promptcut.app.Frame
import com.promptcut.app.JsonRepair
import com.promptcut.app.PlanCodec
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiRole
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.round1
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import org.json.JSONObject

data class AgentPlanResult(
    val explanation: String,
    val editPlan: EditPlan? = null,
    val toolCalls: List<AgentToolInvocation> = emptyList(),
    val providerUsed: ProviderId? = null,
    val modelUsed: String? = null,
)

/**
 * Translates natural language user instructions + timeline state + clip intelligence
 * into typed tool calls and/or an [EditPlan].
 */
class AgentPlanner(
    private val router: ProviderRouter,
    private val conversation: ConversationManager,
) {
    suspend fun plan(
        userPrompt: String,
        project: TimelineProject,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        frames: List<Frame>,
        freshEdit: Boolean,
        manualProvider: ProviderId? = null,
    ): AppResult<AgentPlanResult> {
        val timelineSummary = describeTimeline(project)
        val clipSummary = PlanCodec.describeClips(clips)
        val storyNotes = describeStoryAndGaming(clips, insights)
        val wantCaptions = Regex("\\b(caption|subtitles?|subtitle|text overlay)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(userPrompt)

        val systemPrompt = if (freshEdit || project.mainVideoTrack.items.isEmpty()) {
            PlanCodec.SYSTEM_PROMPT + "\n\n" +
                "Additionally, if captions/subtitles are requested, you understand the video has synchronized speech transcription."
        } else {
            AGENT_TOOLS_SYSTEM_PROMPT + "\n\nIf a complete re-cut is better than small edits, return a full EditPlan JSON object instead."
        }

        val fullUserMessage = buildString {
            appendLine(clipSummary)
            if (storyNotes.isNotBlank()) {
                appendLine("HIGHLIGHTS & EVENTS:")
                appendLine(storyNotes)
            }
            if (!freshEdit && project.mainVideoTrack.items.isNotEmpty()) {
                appendLine("CURRENT TIMELINE:")
                appendLine(timelineSummary)
            }
            if (frames.isNotEmpty()) {
                appendLine("Sampled video frames are attached.")
            }
            appendLine()
            appendLine("USER REQUEST: $userPrompt")
        }

        val decision = when (val d = router.chooseProvider(needsVision = frames.isNotEmpty(), manualOverride = manualProvider)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> return d
        }
        val (budgetedMessages, _) = conversation.buildBudgetedMessages(
            systemPrompt = systemPrompt,
            latestUserPrompt = fullUserMessage,
            frames = frames,
            provider = decision.providerId,
        )
        val supportsTools = !freshEdit && router.providerFor(decision.providerId).capabilities.supportsToolCalling

        val req = AiRequest(
            systemPrompt = systemPrompt,
            messages = budgetedMessages.filter { it.role != AiRole.SYSTEM },
            frames = frames,
            tools = if (supportsTools) AgentTools.specs else emptyList(),
            jsonMode = !supportsTools,
            temperature = 0.25,
            maxTokens = 4096,
            timeoutMs = 90_000L,
        )

        val resp = when (val r = router.complete(req, needsVision = frames.isNotEmpty(), manualOverride = manualProvider)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }

        return parseResponse(resp, clips, wantCaptions)
    }

    internal fun parseResponse(
        response: AiResponse,
        clips: List<ClipMeta>,
        wantCaptions: Boolean,
    ): AppResult<AgentPlanResult> {
        val parsedCalls = mutableListOf<AgentToolInvocation>()

        // 1. Native provider tool calls
        for (tc in response.toolCalls) {
            when (val parsed = AgentTools.parseToolCall(tc)) {
                is AppResult.Success -> parsedCalls += parsed.value
                is AppResult.Failure -> return parsed
            }
        }

        // 2. JSON array of tool_calls inside text
        val repaired = JsonRepair.repairObject(response.text) ?: response.text
        if (parsedCalls.isEmpty()) {
            val inlineCalls = AgentTools.parseToolCallsJsonArray(repaired)
            for (tc in inlineCalls) {
                when (val parsed = AgentTools.parseToolCall(tc)) {
                    is AppResult.Success -> parsedCalls += parsed.value
                    is AppResult.Failure -> return parsed
                }
            }
        }

        if (parsedCalls.isNotEmpty()) {
            if (wantCaptions && parsedCalls.none { it is AgentToolInvocation.GenerateCaptions || it is AgentToolInvocation.EditCaption }) {
                parsedCalls += AgentToolInvocation.GenerateCaptions()
            }
            val msg = runCatching { JSONObject(repaired).optString("summary", "") }.getOrDefault("")
                .ifBlank { response.text.takeIf { !it.trim().startsWith("{") } ?: "Planned ${parsedCalls.size} timeline operation(s)." }
            return AppResult.Success(
                AgentPlanResult(
                    explanation = msg,
                    toolCalls = parsedCalls,
                    providerUsed = response.provider,
                    modelUsed = response.model,
                )
            )
        }

        // 3. EditPlan JSON fallback
        val plan = runCatching { PlanCodec.parse(repaired, clips) }.getOrNull()
        if (plan != null) {
            val extra = if (wantCaptions) listOf(AgentToolInvocation.GenerateCaptions()) else emptyList()
            return AppResult.Success(
                AgentPlanResult(
                    explanation = plan.summary,
                    editPlan = plan,
                    toolCalls = extra,
                    providerUsed = response.provider,
                    modelUsed = response.model,
                )
            )
        }

        // 4. Conversational answer only (when user asked a question or inspection)
        val cleanText = response.text.trim()
        if (cleanText.isNotBlank() && !cleanText.startsWith("{")) {
            return AppResult.Success(
                AgentPlanResult(
                    explanation = cleanText,
                    providerUsed = response.provider,
                    modelUsed = response.model,
                )
            )
        }

        return AppResult.Failure(AppError.Validation("The AI reply could not be parsed into valid editing tools or an edit plan."))
    }

    private fun describeTimeline(project: TimelineProject): String = buildString {
        appendLine("Aspect=${project.aspect.key}, Look=${project.colorLook.key}, Mood=${project.mood.key}, Total=${round1(usToSec(project.totalDurationUs))}s, Beats=${project.beatMarkersUs.size}")
        appendLine("V1 (${project.mainVideoTrack.name}, locked=${project.mainVideoTrack.locked}):")
        project.mainVideoTrack.items.forEachIndexed { idx, item ->
            appendLine("[$idx] id=${item.id} clip=${item.sourceClipIndex} src=${round1(usToSec(item.sourceStartUs))}..${round1(usToSec(item.sourceEndUs))}s tl=${round1(usToSec(item.timelineStartUs))}..${round1(usToSec(item.timelineEndUs))}s speed=${item.effectiveSpeed}x muted=${item.muted} vol=${item.volume} transition=${item.transitionIn.key} fx=${item.effects.hasActiveAdjustments} kf=${item.keyframes.size} manual=${item.editedByUser} why=\"${item.why}\"")
        }
        val v2 = project.overlayVideoTrack
        if (v2 != null && v2.items.isNotEmpty()) {
            appendLine("V2 Overlays (locked=${v2.locked}): " + v2.items.joinToString { "id=${it.id} clip=${it.sourceClipIndex} @${round1(usToSec(it.timelineStartUs))}s" })
        }
        project.audioTracks.filter { it.items.isNotEmpty() || it.locked }.forEach { at ->
            appendLine("${at.name} (${at.type.label}, locked=${at.locked}, muted=${at.muted}, vol=${at.volume}): ${at.items.size} item(s)")
        }
        val caps = project.mainCaptionTrack.items
        if (caps.isNotEmpty() || project.mainCaptionTrack.locked) {
            appendLine("Captions (${caps.size}, locked=${project.mainCaptionTrack.locked}): " + caps.take(6).joinToString { "\"${it.text}\" (${round1(usToSec(it.startUs))}s)" })
        }
    }

    private fun describeStoryAndGaming(clips: List<ClipMeta>, insights: Map<Int, ClipInsight>): String = buildString {
        clips.forEach { c ->
            val ins = insights[c.index] ?: return@forEach
            if (ins.gamingEvents.isNotEmpty()) appendLine("Clip ${c.index} gaming: ${ins.gamingEvents.joinToString()}")
            if (ins.ocrHighlights.isNotEmpty()) appendLine("Clip ${c.index} text/HUD: ${ins.ocrHighlights.joinToString()}")
            if (ins.silenceSpans.isNotEmpty()) {
                val totalSilent = ins.silenceSpans.sumOf { it.length }
                appendLine("Clip ${c.index} silence: ${round1(totalSilent)}s across ${ins.silenceSpans.size} pauses")
            }
        }
    }

    companion object {
        private const val AGENT_TOOLS_SYSTEM_PROMPT = """
You are PromptCut's autonomous video editing agent inside a multi-track mobile studio (V1 Main, V2 Overlay/PIP, A1 Music, A2 Voiceover, A3 SFX, Captions).
You operate ONLY via the declared editing tools or by returning a JSON object:
{
  "summary": "short explanation of edits made",
  "tool_calls": [
    { "name": "trim_segment", "arguments": { "item_id": "...", "start_sec": 1.0, "end_sec": 3.5 } }
  ]
}
Rules:
- Never invent segment IDs or clip indices; use exact item_id values from CURRENT TIMELINE.
- Respect locked tracks and manual user edits: only modify segments requested by the user.
- For visual adjustments/grain/vignette/blur/glow/shake use set_effects; for zoom/pan/opacity animations use add_keyframe; for PIP/B-roll use add_overlay_clip.
- For sound effects (whoosh, impact, pop, riser, chime) use add_sfx; for removing dead pauses use trim_silence; for rhythm cuts use sync_to_beats.
- For captions requests use generate_captions, edit_caption, or style_captions.
- Keep responses concise and deterministic.
"""
    }
}
