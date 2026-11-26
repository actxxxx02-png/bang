package com.promptcut.app.intelligence

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.Span
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import java.util.UUID
import kotlin.math.abs

enum class StoryAct(val label: String) {
    HOOK("Hook"),
    SETUP("Setup"),
    MAIN_ACTION("Main Action"),
    CLIMAX("Climax"),
    ENDING("Ending"),
}

data class StoryBeat(
    val clipIndex: Int,
    val startSec: Double,
    val endSec: Double,
    val act: StoryAct,
    val score: Double,
    val reason: String,
)

data class SfxCue(
    val timelineUs: Long,
    val type: String,
    val reason: String,
)

/**
 * Narrative, captioning, and audio-cue intelligence over analyzed clips & timeline items.
 */
object StoryAnalyzer {

    fun analyzeStoryArc(
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
    ): List<StoryBeat> {
        val candidates = mutableListOf<StoryBeat>()
        clips.forEach { clip ->
            val ins = insights[clip.index]
            if (ins == null || ins.shots.isEmpty()) {
                candidates += StoryBeat(
                    clip.index, 0.0, clip.durationSec.coerceAtMost(4.0), StoryAct.MAIN_ACTION, 1.0, "clip footage"
                )
                return@forEach
            }
            ins.shots.forEach { shot ->
                val mid = (shot.start + shot.end) / 2.0
                val nearbyHl = ins.highlights.filter { it.time in shot.start..shot.end }.maxByOrNull { it.score }
                val hasGamingVictory = ins.gamingEvents.any { "VICTORY" in it || "HEADSHOT" in it || "KILL" in it }
                val score = shot.interest * 2.0 + (nearbyHl?.score ?: 0.0) + (if (shot.smiling) 1.5 else 0.0)
                val reason = nearbyHl?.reason ?: shot.labels.take(2).joinToString(", ").ifBlank { "action" }
                val act = when {
                    hasGamingVictory && mid > clip.durationSec * 0.65 -> StoryAct.CLIMAX
                    shot.interest >= 2 && score >= 4.5 -> StoryAct.CLIMAX
                    mid < clip.durationSec * 0.2 && shot.interest >= 1 -> StoryAct.SETUP
                    mid > clip.durationSec * 0.8 && shot.interest >= 1 -> StoryAct.ENDING
                    else -> StoryAct.MAIN_ACTION
                }
                candidates += StoryBeat(clip.index, shot.start, shot.end, act, score, reason)
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val bestHook = candidates.maxByOrNull { it.score }
        return candidates.map { b ->
            if (b === bestHook) b.copy(act = StoryAct.HOOK, reason = "Best hook: ${b.reason}") else b
        }
    }

    /**
     * Maps clip speech transcripts onto the active timeline segments so captions stay
     * synchronized with cuts, trims, speed changes, and reordering.
     */
    fun generateCaptionsForTimeline(
        project: TimelineProject,
        insights: Map<Int, ClipInsight>,
        style: CaptionStyle = CaptionStyle.SUBTITLE_BAR,
    ): List<CaptionItem> {
        val out = mutableListOf<CaptionItem>()
        project.mainVideoTrack.items.sortedBy { it.timelineStartUs }.forEach { item ->
            val ins = insights[item.sourceClipIndex] ?: return@forEach
            val srcStartSec = usToSec(item.sourceStartUs)
            val srcEndSec = usToSec(item.sourceEndUs)
            ins.speech.forEach { sp ->
                val overlapStart = maxOf(sp.start, srcStartSec)
                val overlapEnd = minOf(sp.end, srcEndSec)
                if (overlapEnd - overlapStart < 0.25 || sp.text.isBlank()) return@forEach
                val relStartSec = (overlapStart - srcStartSec) / item.speed
                val relEndSec = (overlapEnd - srcStartSec) / item.speed
                val tlStart = item.timelineStartUs + secToUs(relStartSec)
                val tlEnd = (item.timelineStartUs + secToUs(relEndSec)).coerceAtLeast(tlStart + 300_000L)
                // Split long sentences into readable 42-char caption chunks.
                val words = sp.text.trim().split(Regex("\\s+"))
                if (words.size <= 8) {
                    out += CaptionItem(
                        id = UUID.randomUUID().toString(),
                        startUs = tlStart,
                        endUs = tlEnd,
                        text = sp.text.trim(),
                        style = style,
                    )
                } else {
                    val chunks = words.chunked(7)
                    val chunkDur = (tlEnd - tlStart) / chunks.size
                    chunks.forEachIndexed { idx, w ->
                        val cs = tlStart + idx * chunkDur
                        val ce = if (idx == chunks.lastIndex) tlEnd else cs + chunkDur
                        out += CaptionItem(
                            id = UUID.randomUUID().toString(),
                            startUs = cs,
                            endUs = ce,
                            text = w.joinToString(" "),
                            style = style,
                        )
                    }
                }
            }
        }
        return resolveOverlaps(out.sortedBy { it.startUs })
    }

    fun suggestSfxCues(project: TimelineProject, insights: Map<Int, ClipInsight>): List<SfxCue> {
        val cues = mutableListOf<SfxCue>()
        val items = project.mainVideoTrack.items.sortedBy { it.timelineStartUs }
        items.forEachIndexed { idx, item ->
            if (idx > 0 && item.transitionIn.key != "cut") {
                cues += SfxCue(item.timelineStartUs, "Whoosh", "Transition into shot ${idx + 1}")
            }
            val ins = insights[item.sourceClipIndex]
            if (ins != null && ins.gamingEvents.any { "KILL" in it || "HEADSHOT" in it || "VICTORY" in it }) {
                cues += SfxCue(item.timelineStartUs + item.timelineDurationUs / 3, "Impact", "Gaming highlight cue")
            }
        }
        return cues
    }

    private fun resolveOverlaps(sorted: List<CaptionItem>): List<CaptionItem> {
        val out = mutableListOf<CaptionItem>()
        for (c in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && c.startUs < prev.endUs) {
                val adj = prev.endUs
                if (c.endUs - adj >= 250_000L) out += c.copy(startUs = adj)
            } else {
                out += c
            }
        }
        return out
    }

    /** Finds quiet stretches in [env] so boring silences can be trimmed or ducked. */
    fun silenceSpans(env: FloatArray, hopSec: Double, threshold: Float = 0.015f, minSilenceSec: Double = 0.7): List<Span> {
        if (env.isEmpty()) return emptyList()
        val spans = mutableListOf<Span>()
        var startI = -1
        for (i in env.indices) {
            val quiet = env[i] < threshold
            if (quiet && startI < 0) startI = i
            if ((!quiet || i == env.lastIndex) && startI >= 0) {
                val endI = if (quiet) i + 1 else i
                val s = Span(startI * hopSec, endI * hopSec, "silence")
                if (s.length >= minSilenceSec) spans += s
                startI = -1
            }
        }
        return spans
    }

    fun nearestBeatOffsetSec(timeSec: Double, beatSec: Double): Double {
        if (beatSec <= 0.05) return timeSec
        val nearest = kotlin.math.round(timeSec / beatSec) * beatSec
        return if (abs(nearest - timeSec) <= beatSec * 0.45) nearest else timeSec
    }
}
