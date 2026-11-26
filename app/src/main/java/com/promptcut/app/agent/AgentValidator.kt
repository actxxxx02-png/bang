package com.promptcut.app.agent

import com.promptcut.app.ClipMeta
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.TimelineProject

data class AgentValidationReport(
    val validatedTools: List<AgentToolInvocation>,
    val requiresManualEditApproval: Boolean,
    val affectedManualItemIds: List<String>,
    val approvalReason: String?,
)

/**
 * Validates all proposed AI tool calls against timeline state and enforces:
 * 1) Track lock protection (never mutates locked tracks),
 * 2) Manual-edit protection (prompts when modifying items with editedByUser = true),
 * 3) Destructive-change protection (prompts when batch-deleting >50% of existing clips or replacing the whole timeline).
 */
object AgentValidator {

    fun validate(
        invocations: List<AgentToolInvocation>,
        project: TimelineProject,
        clips: List<ClipMeta>,
        allowOverwriteManualEdits: Boolean,
        replacesWholeTimeline: Boolean = false,
    ): AppResult<AgentValidationReport> {
        val mainVideoTrack = project.mainVideoTrack
        val overlayTrack = project.overlayVideoTrack
        val captionTrack = project.mainCaptionTrack
        val musicTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.MUSIC } ?: project.mainAudioTrack
        val sfxTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.SFX }

        val itemsById = mainVideoTrack.items.associateBy { it.id }
        val clipDurByIndex = clips.associate { it.index to it.durationSec }
        val captionsById = captionTrack.items.associateBy { it.id }
        val touchedManual = linkedSetOf<String>()
        var destructiveCount = 0

        fun requireMainVideoUnlocked(): AppError.Validation? =
            if (mainVideoTrack.locked) AppError.Validation("Track '${mainVideoTrack.name}' is locked. Unlock it first.") else null

        if (replacesWholeTimeline) {
            val manualIds = mainVideoTrack.items.filter { it.editedByUser }.map { it.id } +
                captionTrack.items.filter { it.editedByUser }.map { it.id } +
                musicTrack.items.filter { it.editedByUser }.map { it.id }
            touchedManual.addAll(manualIds)
            if (mainVideoTrack.locked) {
                return AppResult.Failure(AppError.Validation("Track '${mainVideoTrack.name}' is locked. Unlock it before rebuilding the edit."))
            }
            if (mainVideoTrack.items.size >= 2) {
                destructiveCount += mainVideoTrack.items.size
            }
        }

        for (tool in invocations) {
            when (tool) {
                is AgentToolInvocation.TrimSegment -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot trim: segment '${tool.itemId}' not found."))
                    val maxDur = clipDurByIndex[item.sourceClipIndex] ?: Double.MAX_VALUE
                    if (tool.endSec > maxDur + 0.1) {
                        return AppResult.Failure(AppError.Validation("Trim end ${tool.endSec}s exceeds clip duration ${maxDur}s."))
                    }
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.SplitSegment -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot split: segment '${tool.itemId}' not found."))
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.DeleteSegment -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot delete: segment '${tool.itemId}' not found."))
                    destructiveCount++
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.DuplicateSegment -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    if (tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot duplicate: segment '${tool.itemId}' not found."))
                    }
                }
                is AgentToolInvocation.ReorderSegments -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val count = mainVideoTrack.items.size
                    if (tool.fromIndex !in 0 until count || tool.toIndex !in 0 until count) {
                        return AppResult.Failure(AppError.Validation("Reorder indices (${tool.fromIndex} -> ${tool.toIndex}) out of range 0..${count - 1}."))
                    }
                    mainVideoTrack.items.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.SetSpeed -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot set speed: segment '${tool.itemId}' not found."))
                    if (tool.speed !in 0.25..4.0) {
                        return AppResult.Failure(AppError.Validation("Speed ${tool.speed}x must be between 0.25x and 4.0x."))
                    }
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.SetCropResize -> {
                    if (tool.crop != null || tool.itemId != null) {
                        requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    }
                    if (tool.itemId != null && tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot crop: segment '${tool.itemId}' not found."))
                    }
                    val item = tool.itemId?.let { itemsById[it] }
                    if (item?.editedByUser == true) touchedManual += item.id
                }
                is AgentToolInvocation.SetTransition -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    if (tool.itemId != null && tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot set transition: segment '${tool.itemId}' not found."))
                    }
                }
                is AgentToolInvocation.SetEffects -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    if (tool.itemId != null && tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot apply effects: segment '${tool.itemId}' not found."))
                    }
                    val targets = if (tool.itemId != null) listOfNotNull(itemsById[tool.itemId]) else mainVideoTrack.items
                    targets.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.AddKeyframe -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot add keyframe: segment '${tool.itemId}' not found."))
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.AddOverlayClip -> {
                    if (overlayTrack?.locked == true) {
                        return AppResult.Failure(AppError.Validation("Track '${overlayTrack.name}' is locked. Unlock it first."))
                    }
                    val maxDur = clipDurByIndex[tool.sourceClipIndex]
                        ?: return AppResult.Failure(AppError.Validation("Overlay clip #${tool.sourceClipIndex} not found."))
                    if (tool.endSec > maxDur + 0.1) {
                        return AppResult.Failure(AppError.Validation("Overlay clip end ${tool.endSec}s exceeds clip duration ${maxDur}s."))
                    }
                }
                is AgentToolInvocation.AddSfx -> {
                    if (sfxTrack?.locked == true) {
                        return AppResult.Failure(AppError.Validation("Track '${sfxTrack.name}' is locked. Unlock it first."))
                    }
                }
                is AgentToolInvocation.TrimSilence -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    mainVideoTrack.items.filter { it.editedByUser && (tool.clipIndex == null || it.sourceClipIndex == tool.clipIndex) }
                        .forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.SyncToBeats -> {
                    requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                    mainVideoTrack.items.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.EditCaption -> {
                    if (captionTrack.locked) {
                        return AppResult.Failure(AppError.Validation("Track '${captionTrack.name}' is locked. Unlock it first."))
                    }
                    if (tool.captionId != null && captionsById[tool.captionId]?.editedByUser == true) {
                        touchedManual += tool.captionId
                    }
                }
                is AgentToolInvocation.GenerateCaptions,
                is AgentToolInvocation.StyleCaptions -> {
                    if (captionTrack.locked) {
                        return AppResult.Failure(AppError.Validation("Track '${captionTrack.name}' is locked. Unlock it first."))
                    }
                    captionTrack.items.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.ConfigureAudioMusic -> {
                    if (!tool.musicOn || tool.musicVolume != null || tool.musicQuery != null) {
                        if (musicTrack.locked) {
                            return AppResult.Failure(AppError.Validation("Track '${musicTrack.name}' is locked. Unlock it first."))
                        }
                        musicTrack.items.filter { it.editedByUser && !tool.musicOn }.forEach { touchedManual += it.id }
                    }
                    if (tool.clipId != null) {
                        requireMainVideoUnlocked()?.let { return AppResult.Failure(it) }
                        if (tool.clipId !in itemsById) {
                            return AppResult.Failure(AppError.Validation("Cannot configure audio: segment '${tool.clipId}' not found."))
                        }
                    }
                }
                is AgentToolInvocation.InspectClips,
                is AgentToolInvocation.DetectHighlights,
                is AgentToolInvocation.SetLook,
                is AgentToolInvocation.InspectTimelineQa -> Unit
            }
        }

        val majorDestructive = mainVideoTrack.items.size >= 2 && destructiveCount > mainVideoTrack.items.size / 2
        val needsApproval = (touchedManual.isNotEmpty() || majorDestructive) && !allowOverwriteManualEdits
        val reason = when {
            !needsApproval -> null
            touchedManual.isNotEmpty() -> "This change modifies ${touchedManual.size} item(s) you edited manually on the timeline."
            else -> "This change replaces or removes most of your current timeline ($destructiveCount segment(s))."
        }

        return AppResult.Success(
            AgentValidationReport(
                validatedTools = invocations,
                requiresManualEditApproval = needsApproval,
                affectedManualItemIds = touchedManual.toList(),
                approvalReason = reason,
            )
        )
    }
}
