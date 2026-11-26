package com.promptcut.app.agent

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.EditPlan
import com.promptcut.app.audio.SfxType
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.intelligence.CaptionTools
import com.promptcut.app.intelligence.StoryAnalyzer
import com.promptcut.app.round1
import com.promptcut.app.timeline.AudioClipItem
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

data class ExecutionOutcome(
    val steps: List<AgentStep>,
    val updatedProject: TimelineProject,
    val musicRequested: Boolean? = null,
    val musicQuery: String? = null,
    val musicVolume: Float? = null,
)

/**
 * Executes validated [AgentToolInvocation] lists against [TimelineEngine] inside a single
 * atomic undoable [TimelineCommand.Batch].
 */
class AgentExecutor(
    private val engine: TimelineEngine,
) {
    fun execute(
        invocations: List<AgentToolInvocation>,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        clipUris: Map<Int, String>,
        replacementPlan: EditPlan? = null,
    ): AppResult<ExecutionOutcome> {
        val commands = mutableListOf<TimelineCommand>()
        val steps = mutableListOf<AgentStep>()
        var musicRequested: Boolean? = null
        var musicQuery: String? = null
        var musicVolume: Float? = null

        val metasByIdx = clips.associateBy { it.index }

        if (replacementPlan != null) {
            val next = TimelineProject.fromEditPlan(
                plan = replacementPlan,
                clips = clips,
                clipUris = clipUris,
                projectId = engine.project.id,
                projectName = engine.project.name,
                preserveLegacyJson = replacementPlan.toJson(),
            ).copy(
                fps = engine.project.fps,
                resolution = engine.project.resolution,
                videoBitrateBps = engine.project.videoBitrateBps,
                audioBitrateBps = engine.project.audioBitrateBps,
                preferredHevc = engine.project.preferredHevc,
                audioTracks = engine.project.audioTracks,
                captionTracks = engine.project.captionTracks,
                beatMarkersUs = engine.project.beatMarkersUs,
            )
            commands += TimelineCommand.ReplaceProject(next, label = "AI Edit", byUser = false)
            steps += AgentStep("apply_edit_plan", "Built timeline (${replacementPlan.segments.size} shots, ${round1(replacementPlan.totalSec)}s)", true)
            musicRequested = replacementPlan.musicOn
            if (replacementPlan.musicQuery.isNotBlank()) musicQuery = replacementPlan.musicQuery
            musicVolume = replacementPlan.musicVolume.toFloat()
        }

        val referenceProject = if (replacementPlan != null) {
            (commands.last() as TimelineCommand.ReplaceProject).nextProject
        } else {
            engine.project
        }

        for (tool in invocations) {
            when (tool) {
                is AgentToolInvocation.InspectClips -> {
                    val count = if (tool.clipIndex != null) 1 else clips.size
                    steps += AgentStep(tool.toolName, "Inspected $count clip(s)", true)
                }

                is AgentToolInvocation.DetectHighlights -> {
                    val beats = StoryAnalyzer.analyzeStoryArc(clips, insights)
                    steps += AgentStep(tool.toolName, "Identified ${beats.size} story beat(s) across ${clips.size} clip(s)", true)
                }

                is AgentToolInvocation.TrimSegment -> {
                    val item = referenceProject.mainVideoTrack.items.firstOrNull { it.id == tool.itemId }
                    val maxSrcUs = item?.let { metasByIdx[it.sourceClipIndex]?.durationSec?.let(::secToUs) } ?: Long.MAX_VALUE
                    commands += TimelineCommand.TrimVideoClip(
                        itemId = tool.itemId,
                        newSourceStartUs = secToUs(tool.startSec),
                        newSourceEndUs = secToUs(tool.endSec),
                        maxSourceDurationUs = maxSrcUs,
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Trimmed segment to ${round1(tool.startSec)}–${round1(tool.endSec)}s", true)
                }

                is AgentToolInvocation.SplitSegment -> {
                    commands += TimelineCommand.SplitVideoClip(
                        itemId = tool.itemId,
                        splitAtTimelineUs = secToUs(tool.splitAtSec),
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Split segment at ${round1(tool.splitAtSec)}s", true)
                }

                is AgentToolInvocation.DeleteSegment -> {
                    commands += TimelineCommand.DeleteVideoClip(itemId = tool.itemId, byUser = false)
                    steps += AgentStep(tool.toolName, "Removed segment from timeline", true)
                }

                is AgentToolInvocation.DuplicateSegment -> {
                    commands += TimelineCommand.DuplicateVideoClip(itemId = tool.itemId, byUser = false)
                    steps += AgentStep(tool.toolName, "Duplicated segment", true)
                }

                is AgentToolInvocation.ReorderSegments -> {
                    commands += TimelineCommand.ReorderVideoClips(tool.fromIndex, tool.toIndex, byUser = false)
                    steps += AgentStep(tool.toolName, "Reordered segment #${tool.fromIndex + 1} → #${tool.toIndex + 1}", true)
                }

                is AgentToolInvocation.SetCropResize -> {
                    if (tool.aspect != null || tool.customWidth != null || tool.customHeight != null) {
                        commands += TimelineCommand.SetProjectStyle(
                            aspect = tool.aspect,
                            customAspectWidth = tool.customWidth,
                            customAspectHeight = tool.customHeight,
                            byUser = false,
                        )
                    }
                    if (tool.crop != null) {
                        val targets = if (tool.itemId != null) listOf(tool.itemId) else referenceProject.mainVideoTrack.items.map { it.id }
                        targets.forEach { id ->
                            commands += TimelineCommand.SetClipCrop(itemId = id, crop = tool.crop, byUser = false)
                        }
                    }
                    steps += AgentStep(tool.toolName, "Updated frame aspect/crop", true)
                }

                is AgentToolInvocation.GenerateCaptions -> {
                    val generated = StoryAnalyzer.generateCaptionsForTimeline(referenceProject, insights, tool.style)
                        .map { CaptionTools.enrichCaption(it, applyPunctuation = true, highlightKeywords = true) }
                    if (generated.isNotEmpty()) {
                        commands += TimelineCommand.ReplaceCaptions(generated, byUser = false)
                        steps += AgentStep(tool.toolName, "Generated ${generated.size} synchronized caption(s)", true)
                    } else {
                        steps += AgentStep(tool.toolName, "No speech spans detected for captions", true)
                    }
                }

                is AgentToolInvocation.EditCaption -> {
                    val cap = CaptionTools.enrichCaption(
                        CaptionItem(
                            id = tool.captionId ?: UUID.randomUUID().toString(),
                            startUs = secToUs(tool.startSec),
                            endUs = secToUs(tool.endSec),
                            text = tool.text,
                            style = tool.style,
                            editedByUser = false,
                        ),
                        applyPunctuation = false,
                        highlightKeywords = true,
                    )
                    commands += TimelineCommand.AddOrUpdateCaption(cap, byUser = false)
                    steps += AgentStep(tool.toolName, "Updated caption \"${cap.text.take(24)}\"", true)
                }

                is AgentToolInvocation.StyleCaptions -> {
                    val existing = referenceProject.mainCaptionTrack.items
                    if (existing.isEmpty()) {
                        steps += AgentStep(tool.toolName, "No captions on track to style", true)
                    } else {
                        val styled = existing.map { c ->
                            val updated = c.copy(
                                style = tool.style ?: c.style,
                                font = tool.font ?: c.font,
                                fontSizeSp = tool.fontSizeSp ?: c.fontSizeSp,
                                positionYFraction = tool.positionYFraction?.let(CaptionTools::clampSafeAreaY) ?: c.positionYFraction,
                                animation = tool.animation ?: c.animation,
                            )
                            CaptionTools.enrichCaption(
                                updated,
                                applyPunctuation = tool.autoPunctuate,
                                highlightKeywords = tool.highlightKeywords ?: updated.highlightKeywords.isNotEmpty(),
                            )
                        }
                        commands += TimelineCommand.ReplaceCaptions(styled, byUser = false)
                        steps += AgentStep(tool.toolName, "Styled ${styled.size} caption(s)", true)
                    }
                }

                is AgentToolInvocation.SetTransition -> {
                    val targets = if (tool.itemId != null) listOf(tool.itemId) else referenceProject.mainVideoTrack.items.map { it.id }
                    targets.forEach { id ->
                        commands += TimelineCommand.SetClipTransition(
                            itemId = id,
                            transition = tool.transition,
                            durationUs = secToUs(tool.durationSec),
                            byUser = false,
                        )
                    }
                    steps += AgentStep(tool.toolName, "Set ${tool.transition.label} transition", true)
                }

                is AgentToolInvocation.SetLook -> {
                    commands += TimelineCommand.SetProjectStyle(
                        colorLook = tool.colorLook,
                        titleOverlay = tool.titleOverlay,
                        clearTitle = tool.clearTitle,
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Applied ${tool.colorLook.label} look", true)
                }

                is AgentToolInvocation.SetSpeed -> {
                    commands += TimelineCommand.SetClipSpeed(itemId = tool.itemId, speed = tool.speed, byUser = false)
                    steps += AgentStep(tool.toolName, "Set speed ${tool.speed}x", true)
                }

                is AgentToolInvocation.SetEffects -> {
                    val targets = if (tool.itemId != null) listOf(tool.itemId) else referenceProject.mainVideoTrack.items.map { it.id }
                    targets.forEach { id ->
                        commands += TimelineCommand.SetClipEffects(
                            itemId = id,
                            effects = tool.effects,
                            byUser = false,
                        )
                    }
                    steps += AgentStep(tool.toolName, "Applied shader effects to ${targets.size} clip(s)", true)
                }

                is AgentToolInvocation.AddKeyframe -> {
                    commands += TimelineCommand.UpsertKeyframe(
                        itemId = tool.itemId,
                        keyframe = tool.keyframe,
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Added ${tool.keyframe.property.label} keyframe", true)
                }

                is AgentToolInvocation.AddOverlayClip -> {
                    val item = VideoClipItem(
                        sourceClipIndex = tool.sourceClipIndex,
                        sourceUri = clipUris[tool.sourceClipIndex] ?: "",
                        timelineStartUs = secToUs(tool.timelineStartSec),
                        sourceStartUs = secToUs(tool.startSec),
                        sourceEndUs = secToUs(tool.endSec),
                        muted = true,
                        overlayBounds = tool.bounds,
                        why = "Overlay / PIP",
                        editedByUser = false,
                    )
                    commands += TimelineCommand.AddVideoClip(
                        item = item,
                        trackId = "video_overlay",
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Added overlay clip #${tool.sourceClipIndex + 1} at ${round1(tool.timelineStartSec)}s", true)
                }

                is AgentToolInvocation.AddSfx -> {
                    val sfx = SfxType.fromKey(tool.sfxType)
                    val sfxItem = AudioClipItem(
                        title = sfx.label,
                        sourceUri = "sfx://${sfx.key}",
                        trackType = AudioTrackType.SFX,
                        timelineStartUs = secToUs(tool.timelineSec),
                        durationUs = secToUs(sfx.durationSec),
                        volume = tool.volume,
                        fadeInUs = 10_000L,
                        fadeOutUs = 50_000L,
                        duckUnderSpeech = false,
                        editedByUser = false,
                    )
                    commands += TimelineCommand.AddAudioItem(
                        item = sfxItem,
                        trackId = "audio_sfx",
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Added ${sfx.label} SFX at ${round1(tool.timelineSec)}s", true)
                }

                is AgentToolInvocation.TrimSilence -> {
                    var trimmedCount = 0
                    referenceProject.mainVideoTrack.items.forEach { item ->
                        if (tool.clipIndex != null && item.sourceClipIndex != tool.clipIndex) return@forEach
                        val silences = insights[item.sourceClipIndex]?.silenceSpans ?: return@forEach
                        val srcStartSec = usToSec(item.sourceStartUs)
                        val srcEndSec = usToSec(item.sourceEndUs)
                        var newStart = srcStartSec
                        var newEnd = srcEndSec
                        silences.firstOrNull { srcStartSec in it.start..it.end }?.let { s ->
                            if (s.end - newStart >= 0.45 && newEnd - s.end >= 0.4) {
                                newStart = (s.end - 0.12).coerceAtLeast(srcStartSec)
                            }
                        }
                        silences.lastOrNull { srcEndSec in it.start..it.end }?.let { s ->
                            if (newEnd - s.start >= 0.45 && s.start - newStart >= 0.4) {
                                newEnd = (s.start + 0.12).coerceAtMost(srcEndSec)
                            }
                        }
                        if (newStart > srcStartSec + 0.08 || newEnd < srcEndSec - 0.08) {
                            val maxSrcUs = metasByIdx[item.sourceClipIndex]?.durationSec?.let(::secToUs) ?: Long.MAX_VALUE
                            commands += TimelineCommand.TrimVideoClip(
                                itemId = item.id,
                                newSourceStartUs = secToUs(newStart),
                                newSourceEndUs = secToUs(newEnd),
                                maxSourceDurationUs = maxSrcUs,
                                byUser = false,
                            )
                            trimmedCount++
                        }
                    }
                    steps += AgentStep(
                        tool.toolName,
                        if (trimmedCount > 0) "Trimmed trailing/leading silence on $trimmedCount clip(s)" else "No trimmable edge silences found",
                        true,
                    )
                }

                is AgentToolInvocation.SyncToBeats -> {
                    val bpm = tool.bpm ?: referenceProject.mood.bpm.toDouble()
                    val beatUs = ((60.0 / bpm) * 1_000_000.0).roundToLong().coerceAtLeast(250_000L)
                    val totalUs = referenceProject.videoDurationUs.coerceAtLeast(1_000_000L)
                    val markers = mutableListOf<Long>()
                    var t = 0L
                    while (t <= totalUs + beatUs) {
                        markers += t
                        t += beatUs
                    }
                    commands += TimelineCommand.SetBeatMarkers(markers, byUser = false)
                    var snappedCount = 0
                    val items = referenceProject.mainVideoTrack.items.sortedBy { it.timelineStartUs }
                    items.dropLast(1).forEach { item ->
                        val nearestBeat = markers.minByOrNull { abs(it - item.timelineEndUs) } ?: return@forEach
                        val deltaUs = nearestBeat - item.timelineEndUs
                        if (abs(deltaUs) in 40_000L..350_000L) {
                            val newSrcEndUs = item.sourceEndUs + (deltaUs * item.effectiveSpeed).roundToLong()
                            val maxSrcUs = metasByIdx[item.sourceClipIndex]?.durationSec?.let(::secToUs) ?: Long.MAX_VALUE
                            if (newSrcEndUs - item.sourceStartUs >= 300_000L && newSrcEndUs <= maxSrcUs) {
                                commands += TimelineCommand.TrimVideoClip(
                                    itemId = item.id,
                                    newSourceStartUs = item.sourceStartUs,
                                    newSourceEndUs = newSrcEndUs,
                                    maxSourceDurationUs = maxSrcUs,
                                    byUser = false,
                                )
                                snappedCount++
                            }
                        }
                    }
                    steps += AgentStep(tool.toolName, "Synced cut markers at ${bpm.toInt()} BPM ($snappedCount cut(s) snapped)", true)
                }

                is AgentToolInvocation.ConfigureAudioMusic -> {
                    musicRequested = tool.musicOn
                    if (tool.musicQuery != null) musicQuery = tool.musicQuery
                    if (tool.musicVolume != null) musicVolume = tool.musicVolume
                    val musicTrack = referenceProject.audioTracks.firstOrNull { it.type == AudioTrackType.MUSIC } ?: referenceProject.mainAudioTrack
                    if (!tool.musicOn) {
                        musicTrack.items.filter { it.trackType == AudioTrackType.MUSIC }.forEach {
                            commands += TimelineCommand.DeleteAudioItem(it.id, trackId = musicTrack.id, byUser = false)
                        }
                    } else if (tool.musicVolume != null) {
                        musicTrack.items.firstOrNull { it.trackType == AudioTrackType.MUSIC }?.let { existing ->
                            commands += TimelineCommand.AddAudioItem(existing.copy(volume = tool.musicVolume, editedByUser = false), trackId = musicTrack.id, byUser = false)
                        }
                    }
                    if (tool.clipId != null && (tool.clipVolume != null || tool.clipMuted != null)) {
                        val existing = referenceProject.mainVideoTrack.items.firstOrNull { it.id == tool.clipId }
                        if (existing != null) {
                            commands += TimelineCommand.SetClipVolume(
                                itemId = tool.clipId,
                                volume = tool.clipVolume ?: existing.volume,
                                muted = tool.clipMuted ?: existing.muted,
                                byUser = false,
                            )
                        }
                    }
                    steps += AgentStep(tool.toolName, if (tool.musicOn) "Configured music & audio" else "Muted background music", true)
                }

                is AgentToolInvocation.InspectTimelineQa -> {
                    val dur = round1(usToSec(referenceProject.totalDurationUs))
                    steps += AgentStep(tool.toolName, "Timeline duration ${dur}s across ${referenceProject.mainVideoTrack.items.size} clip(s)", true)
                }
            }
        }

        if (commands.isEmpty()) {
            return AppResult.Success(ExecutionOutcome(steps, engine.project, musicRequested, musicQuery, musicVolume))
        }

        return when (val res = engine.execute(TimelineCommand.Batch(commands, label = "AI Agent Edit", byUser = false))) {
            is AppResult.Success -> AppResult.Success(
                ExecutionOutcome(
                    steps = steps,
                    updatedProject = res.value,
                    musicRequested = musicRequested,
                    musicQuery = musicQuery,
                    musicVolume = musicVolume,
                )
            )
            is AppResult.Failure -> AppResult.Failure(
                AppError.Validation(res.error.message, cause = res.error.cause)
            )
        }
    }
}
