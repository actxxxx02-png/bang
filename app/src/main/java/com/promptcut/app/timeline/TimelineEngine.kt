package com.promptcut.app.timeline

import com.promptcut.app.Aspect
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.ExportResolution
import com.promptcut.app.Mood
import com.promptcut.app.Transition
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import java.util.UUID
import kotlin.math.abs

sealed class TimelineCommand(open val label: String, open val byUser: Boolean = true) {
    data class AddVideoClip(
        val item: VideoClipItem,
        val trackId: String = "video_main",
        val insertIndex: Int? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Add clip", byUser)

    data class SplitVideoClip(
        val itemId: String,
        val splitAtTimelineUs: Long,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Split clip", byUser)

    data class TrimVideoClip(
        val itemId: String,
        val newSourceStartUs: Long,
        val newSourceEndUs: Long,
        val maxSourceDurationUs: Long = Long.MAX_VALUE,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Trim clip", byUser)

    data class DeleteVideoClip(
        val itemId: String,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Delete clip", byUser)

    data class DuplicateVideoClip(
        val itemId: String,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Duplicate clip", byUser)

    data class ReorderVideoClips(
        val fromIndex: Int,
        val toIndex: Int,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Reorder clips", byUser)

    data class MoveVideoClip(
        val itemId: String,
        val newStartUs: Long,
        val targetTrackId: String? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Move clip", byUser)

    data class SetClipSpeed(
        val itemId: String,
        val speed: Double,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set speed", byUser)

    data class SetClipSpeedRamp(
        val itemId: String,
        val points: List<SpeedRampPoint>,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set speed ramp", byUser)

    data class SetClipVolume(
        val itemId: String,
        val volume: Float,
        val muted: Boolean,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set clip audio", byUser)

    data class SetClipCrop(
        val itemId: String,
        val crop: CropBox?,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set crop", byUser)

    data class SetClipTransition(
        val itemId: String,
        val transition: Transition,
        val durationUs: Long = 350_000L,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set transition", byUser)

    data class SetClipEffects(
        val itemId: String,
        val effects: EffectSettings,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set effects", byUser)

    data class SetOverlayBounds(
        val itemId: String,
        val bounds: OverlayBounds,
        val trackId: String = "video_overlay",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Set overlay layout", byUser)

    data class UpsertKeyframe(
        val itemId: String,
        val keyframe: Keyframe,
        val trackId: String = "video_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Update keyframe", byUser)

    data class AddAudioItem(
        val item: AudioClipItem,
        val trackId: String = defaultAudioTrackId(item.trackType),
        override val byUser: Boolean = true,
    ) : TimelineCommand("Add audio", byUser)

    data class UpdateAudioItem(
        val itemId: String,
        val trackId: String = "audio_music",
        val volume: Float? = null,
        val fadeInUs: Long? = null,
        val fadeOutUs: Long? = null,
        val duckUnderSpeech: Boolean? = null,
        val timelineStartUs: Long? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Update audio", byUser)

    data class DeleteAudioItem(
        val itemId: String,
        val trackId: String? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Remove audio", byUser)

    data class SetAudioTrackMix(
        val trackId: String,
        val volume: Float,
        val muted: Boolean,
        val voiceEnhance: Boolean? = null,
        val normalizeTargetDb: Float? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Mix audio track", byUser)

    data class AddOrUpdateCaption(
        val caption: CaptionItem,
        val trackId: String = "captions_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Edit caption", byUser)

    data class DeleteCaption(
        val captionId: String,
        val trackId: String = "captions_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Delete caption", byUser)

    data class ReplaceCaptions(
        val captions: List<CaptionItem>,
        val trackId: String = "captions_main",
        override val byUser: Boolean = true,
    ) : TimelineCommand("Update captions", byUser)

    data class ToggleTrackLock(
        val trackId: String,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Toggle track lock", byUser)

    data class ToggleTrackVisibility(
        val trackId: String,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Toggle track visibility", byUser)

    data class SetProjectStyle(
        val aspect: Aspect? = null,
        val customAspectWidth: Int? = null,
        val customAspectHeight: Int? = null,
        val colorLook: ColorLook? = null,
        val mood: Mood? = null,
        val titleOverlay: String? = null,
        val clearTitle: Boolean = false,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Update style", byUser)

    data class SetExportConfig(
        val resolution: ExportResolution,
        val fps: Int,
        val videoBitrateBps: Int,
        val audioBitrateBps: Int? = null,
        val preferredHevc: Boolean? = null,
        override val byUser: Boolean = true,
    ) : TimelineCommand("Export settings", byUser)

    data class SetBeatMarkers(
        val beatMarkersUs: List<Long>,
        override val byUser: Boolean = false,
    ) : TimelineCommand("Sync beat markers", byUser)

    data class ReplaceProject(
        val nextProject: TimelineProject,
        override val label: String = "Apply edit",
        override val byUser: Boolean = false,
    ) : TimelineCommand(label, byUser)

    data class Batch(
        val commands: List<TimelineCommand>,
        override val label: String = "AI Edit",
        override val byUser: Boolean = false,
    ) : TimelineCommand(label, byUser)

    companion object {
        fun defaultAudioTrackId(type: AudioTrackType): String = when (type) {
            AudioTrackType.MUSIC -> "audio_music"
            AudioTrackType.VOICEOVER -> "audio_vo"
            AudioTrackType.SFX -> "audio_sfx"
        }
    }
}

/**
 * Deterministic, pure timeline state machine with typed validation, track lock enforcement,
 * multi-track V1/V2 + A1/A2/A3 + T1 support, and full Undo / Redo history.
 */
class TimelineEngine(initial: TimelineProject = TimelineProject.empty()) {
    private val undoStack = ArrayDeque<Pair<String, TimelineProject>>()
    private val redoStack = ArrayDeque<Pair<String, TimelineProject>>()

    var project: TimelineProject = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.first
    val redoLabel: String? get() = redoStack.lastOrNull()?.first
    val historyDepth: Int get() = undoStack.size

    fun historyEntries(): List<String> = undoStack.map { it.first }.reversed()

    fun reset(newProject: TimelineProject) {
        undoStack.clear()
        redoStack.clear()
        project = newProject
    }

    fun execute(command: TimelineCommand): AppResult<TimelineProject> {
        val next = applyCommand(project, command)
        return when (next) {
            is AppResult.Success -> {
                undoStack.addLast(command.label to project)
                while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
                redoStack.clear()
                project = next.value.copy(updatedAtMs = System.currentTimeMillis())
                AppResult.Success(project)
            }
            is AppResult.Failure -> next
        }
    }

    fun undo(): AppResult<TimelineProject> {
        val prev = undoStack.removeLastOrNull()
            ?: return AppResult.Failure(AppError.Validation("Nothing to undo."))
        redoStack.addLast(prev.first to project)
        project = prev.second
        return AppResult.Success(project)
    }

    fun redo(): AppResult<TimelineProject> {
        val next = redoStack.removeLastOrNull()
            ?: return AppResult.Failure(AppError.Validation("Nothing to redo."))
        undoStack.addLast(next.first to project)
        project = next.second
        return AppResult.Success(project)
    }

    private fun checkVideoTrackUnlocked(state: TimelineProject, trackId: String): AppError? {
        val track = state.videoTracks.firstOrNull { it.id == trackId } ?: return null
        return if (track.locked) AppError.Validation("Track '${track.name}' is locked. Unlock it first.") else null
    }

    private fun checkAudioTrackUnlocked(state: TimelineProject, trackId: String): AppError? {
        val track = state.audioTracks.firstOrNull { it.id == trackId } ?: return null
        return if (track.locked) AppError.Validation("Track '${track.name}' is locked. Unlock it first.") else null
    }

    private fun checkCaptionTrackUnlocked(state: TimelineProject, trackId: String): AppError? {
        val track = state.captionTracks.firstOrNull { it.id == trackId } ?: return null
        return if (track.locked) AppError.Validation("Track '${track.name}' is locked. Unlock it first.") else null
    }

    private fun applyCommand(state: TimelineProject, cmd: TimelineCommand): AppResult<TimelineProject> {
        return when (cmd) {
            is TimelineCommand.Batch -> {
                var cur = state
                for (sub in cmd.commands) {
                    when (val r = applyCommand(cur, sub)) {
                        is AppResult.Success -> cur = r.value
                        is AppResult.Failure -> return r
                    }
                }
                AppResult.Success(cur)
            }

            is TimelineCommand.ReplaceProject -> AppResult.Success(cmd.nextProject)

            is TimelineCommand.AddVideoClip -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                if (cmd.item.sourceEndUs - cmd.item.sourceStartUs < MIN_CLIP_US) {
                    return AppResult.Failure(AppError.Validation("Clip duration must be at least 0.2 seconds."))
                }
                val tracks = ensureVideoTrack(state.videoTracks, cmd.trackId)
                val updated = tracks.map { t ->
                    if (t.id != cmd.trackId) t else {
                        val list = t.items.toMutableList()
                        val pos = cmd.insertIndex?.coerceIn(0, list.size) ?: list.size
                        val placedItem = if (!t.isMain && cmd.item.overlayBounds == null) {
                            cmd.item.copy(overlayBounds = OverlayBounds(), editedByUser = cmd.item.editedByUser || cmd.byUser)
                        } else {
                            cmd.item.copy(editedByUser = cmd.item.editedByUser || cmd.byUser)
                        }
                        list.add(pos, placedItem)
                        t.copy(items = reflowMainIfNeeded(t.isMain, list))
                    }
                }
                AppResult.Success(state.copy(videoTracks = updated))
            }

            is TimelineCommand.SplitVideoClip -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val track = state.videoTracks.firstOrNull { it.id == cmd.trackId }
                    ?: return AppResult.Failure(AppError.Validation("Track ${cmd.trackId} not found."))
                val idx = track.items.indexOfFirst { it.id == cmd.itemId }
                if (idx < 0) return AppResult.Failure(AppError.Validation("Clip not found on timeline."))
                val target = track.items[idx]
                val offsetOutUs = cmd.splitAtTimelineUs - target.timelineStartUs
                val offsetSrcUs = (offsetOutUs * target.effectiveSpeed).toLong()
                val splitSrcUs = target.sourceStartUs + offsetSrcUs
                if (splitSrcUs - target.sourceStartUs < MIN_CLIP_US || target.sourceEndUs - splitSrcUs < MIN_CLIP_US) {
                    return AppResult.Failure(AppError.Validation("Split point is too close to the edge of the clip."))
                }
                val left = target.copy(sourceEndUs = splitSrcUs, speedRamp = emptyList(), editedByUser = target.editedByUser || cmd.byUser)
                val right = target.copy(
                    id = UUID.randomUUID().toString(),
                    timelineStartUs = left.timelineEndUs,
                    sourceStartUs = splitSrcUs,
                    speedRamp = emptyList(),
                    transitionIn = Transition.CUT,
                    editedByUser = target.editedByUser || cmd.byUser,
                )
                val list = track.items.toMutableList().apply {
                    removeAt(idx)
                    add(idx, left)
                    add(idx + 1, right)
                }
                val updatedTracks = state.videoTracks.map { if (it.id == track.id) it.copy(items = reflowMainIfNeeded(it.isMain, list)) else it }
                AppResult.Success(state.copy(videoTracks = updatedTracks))
            }

            is TimelineCommand.TrimVideoClip -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val s = cmd.newSourceStartUs.coerceAtLeast(0L)
                val e = cmd.newSourceEndUs.coerceAtMost(cmd.maxSourceDurationUs)
                if (e - s < MIN_CLIP_US) {
                    return AppResult.Failure(AppError.Validation("Trimmed clip cannot be shorter than 0.2 seconds."))
                }
                mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                    it.copy(sourceStartUs = s, sourceEndUs = e, editedByUser = it.editedByUser || cmd.byUser)
                }
            }

            is TimelineCommand.DeleteVideoClip -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val track = state.videoTracks.firstOrNull { it.id == cmd.trackId }
                    ?: return AppResult.Failure(AppError.Validation("Track not found."))
                if (track.items.none { it.id == cmd.itemId }) {
                    return AppResult.Failure(AppError.Validation("Clip not found."))
                }
                val list = reflowMainIfNeeded(track.isMain, track.items.filterNot { it.id == cmd.itemId })
                AppResult.Success(state.copy(videoTracks = state.videoTracks.map { if (it.id == track.id) it.copy(items = list) else it }))
            }

            is TimelineCommand.DuplicateVideoClip -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val track = state.videoTracks.firstOrNull { it.id == cmd.trackId }
                    ?: return AppResult.Failure(AppError.Validation("Track not found."))
                val idx = track.items.indexOfFirst { it.id == cmd.itemId }
                if (idx < 0) return AppResult.Failure(AppError.Validation("Clip not found."))
                val copy = track.items[idx].copy(
                    id = UUID.randomUUID().toString(),
                    timelineStartUs = if (track.isMain) track.items[idx].timelineStartUs else track.items[idx].timelineEndUs,
                    editedByUser = cmd.byUser,
                )
                val list = track.items.toMutableList().apply { add(idx + 1, copy) }
                val updated = state.videoTracks.map { if (it.id == track.id) it.copy(items = reflowMainIfNeeded(it.isMain, list)) else it }
                AppResult.Success(state.copy(videoTracks = updated))
            }

            is TimelineCommand.ReorderVideoClips -> {
                checkVideoTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val track = state.videoTracks.firstOrNull { it.id == cmd.trackId }
                    ?: return AppResult.Failure(AppError.Validation("Track not found."))
                if (cmd.fromIndex !in track.items.indices || cmd.toIndex !in track.items.indices) {
                    return AppResult.Failure(AppError.Validation("Invalid clip reorder position."))
                }
                val list = track.items.toMutableList()
                val moved = list.removeAt(cmd.fromIndex).let { it.copy(editedByUser = it.editedByUser || cmd.byUser) }
                list.add(cmd.toIndex, moved)
                val updated = state.videoTracks.map { if (it.id == track.id) it.copy(items = reflowMainIfNeeded(it.isMain, list)) else it }
                AppResult.Success(state.copy(videoTracks = updated))
            }

            is TimelineCommand.MoveVideoClip -> {
                val clampedStart = cmd.newStartUs.coerceAtLeast(0L)
                val srcTrack = state.videoTracks.firstOrNull { t -> t.items.any { it.id == cmd.itemId } }
                    ?: return AppResult.Failure(AppError.Validation("Clip not found."))
                if (srcTrack.locked) return AppResult.Failure(AppError.Validation("Track '${srcTrack.name}' is locked. Unlock it first."))
                val dstTrackId = cmd.targetTrackId ?: srcTrack.id
                checkVideoTrackUnlocked(state, dstTrackId)?.let { return AppResult.Failure(it) }
                val item = srcTrack.items.first { it.id == cmd.itemId }
                val updated = if (dstTrackId == srcTrack.id) {
                    state.videoTracks.map { t ->
                        if (t.id != srcTrack.id) t else {
                            val mapped = t.items.map {
                                if (it.id == item.id) it.copy(timelineStartUs = clampedStart, editedByUser = it.editedByUser || cmd.byUser) else it
                            }.sortedBy { it.timelineStartUs }
                            t.copy(items = reflowMainIfNeeded(t.isMain, mapped))
                        }
                    }
                } else {
                    val withTarget = ensureVideoTrack(state.videoTracks, dstTrackId)
                    withTarget.map { t ->
                        when (t.id) {
                            srcTrack.id -> t.copy(items = reflowMainIfNeeded(t.isMain, t.items.filterNot { it.id == item.id }))
                            dstTrackId -> {
                                val moved = item.copy(
                                    timelineStartUs = clampedStart,
                                    overlayBounds = if (!t.isMain) (item.overlayBounds ?: OverlayBounds()) else null,
                                    editedByUser = item.editedByUser || cmd.byUser,
                                )
                                val added = (t.items + moved).sortedBy { it.timelineStartUs }
                                t.copy(items = reflowMainIfNeeded(t.isMain, added))
                            }
                            else -> t
                        }
                    }
                }
                AppResult.Success(state.copy(videoTracks = updated))
            }

            is TimelineCommand.SetClipSpeed -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(speed = cmd.speed.coerceIn(0.25, 4.0), speedRamp = emptyList(), editedByUser = it.editedByUser || cmd.byUser)
            }

            is TimelineCommand.SetClipSpeedRamp -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                val clean = cmd.points.map { p ->
                    SpeedRampPoint(p.progress.coerceIn(0f, 1f), p.speed.coerceIn(0.25, 4.0))
                }.sortedBy { p -> p.progress }
                it.copy(speedRamp = clean, editedByUser = it.editedByUser || cmd.byUser)
            }

            is TimelineCommand.SetClipVolume -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(volume = cmd.volume.coerceIn(0f, 1.5f), muted = cmd.muted, editedByUser = it.editedByUser || cmd.byUser)
            }

            is TimelineCommand.SetClipCrop -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(crop = cmd.crop?.normalized(), editedByUser = it.editedByUser || cmd.byUser)
            }

            is TimelineCommand.SetClipTransition -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(transitionIn = cmd.transition, transitionDurationUs = cmd.durationUs.coerceIn(100_000L, 1_500_000L), editedByUser = it.editedByUser || cmd.byUser)
            }

            is TimelineCommand.SetClipEffects -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(
                    effects = cmd.effects.copy(
                        brightness = cmd.effects.brightness.coerceIn(-0.5f, 0.5f),
                        contrast = cmd.effects.contrast.coerceIn(-0.5f, 0.5f),
                        saturation = cmd.effects.saturation.coerceIn(0f, 2f),
                        blur = cmd.effects.blur.coerceIn(0f, 1f),
                        sharpen = cmd.effects.sharpen.coerceIn(0f, 1f),
                        vignette = cmd.effects.vignette.coerceIn(0f, 1f),
                        filmGrain = cmd.effects.filmGrain.coerceIn(0f, 1f),
                        glow = cmd.effects.glow.coerceIn(0f, 1f),
                        shake = cmd.effects.shake.coerceIn(0f, 1f),
                        fadeInUs = cmd.effects.fadeInUs.coerceIn(0L, 3_000_000L),
                        fadeOutUs = cmd.effects.fadeOutUs.coerceIn(0L, 3_000_000L),
                    ),
                    editedByUser = it.editedByUser || cmd.byUser,
                )
            }

            is TimelineCommand.SetOverlayBounds -> mutateVideoItem(state, cmd.trackId, cmd.itemId) {
                it.copy(
                    overlayBounds = cmd.bounds.copy(
                        x = cmd.bounds.x.coerceIn(0f, 0.85f),
                        y = cmd.bounds.y.coerceIn(0f, 0.85f),
                        widthFraction = cmd.bounds.widthFraction.coerceIn(0.15f, 0.85f),
                        opacity = cmd.bounds.opacity.coerceIn(0.1f, 1f),
                    ),
                    editedByUser = it.editedByUser || cmd.byUser,
                )
            }

            is TimelineCommand.UpsertKeyframe -> mutateVideoItem(state, cmd.trackId, cmd.itemId) { item ->
                val cleanOffset = cmd.keyframe.timeOffsetUs.coerceIn(0L, item.timelineDurationUs)
                val rest = item.keyframes.filterNot { it.property == cmd.keyframe.property && abs(it.timeOffsetUs - cleanOffset) < 50_000L }
                item.copy(
                    keyframes = (rest + cmd.keyframe.copy(timeOffsetUs = cleanOffset)).sortedBy { it.timeOffsetUs },
                    editedByUser = item.editedByUser || cmd.byUser,
                )
            }

            is TimelineCommand.AddAudioItem -> {
                checkAudioTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val tracks = ensureAudioTrack(state.audioTracks, cmd.trackId, cmd.item.trackType)
                val updated = tracks.map { t ->
                    if (t.id != cmd.trackId) t else {
                        val base = if (cmd.item.trackType == AudioTrackType.MUSIC) t.items.filterNot { it.trackType == AudioTrackType.MUSIC } else t.items
                        t.copy(items = (base + cmd.item.copy(editedByUser = cmd.item.editedByUser || cmd.byUser)).sortedBy { it.timelineStartUs })
                    }
                }
                AppResult.Success(state.copy(audioTracks = updated))
            }

            is TimelineCommand.UpdateAudioItem -> {
                checkAudioTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val updated = state.audioTracks.map { t ->
                    if (t.id != cmd.trackId && t.items.none { it.id == cmd.itemId }) t else {
                        if (t.locked) return AppResult.Failure(AppError.Validation("Track '${t.name}' is locked. Unlock it first."))
                        t.copy(items = t.items.map { item ->
                            if (item.id != cmd.itemId) item else item.copy(
                                volume = cmd.volume?.coerceIn(0f, 1.5f) ?: item.volume,
                                fadeInUs = cmd.fadeInUs?.coerceIn(0L, 5_000_000L) ?: item.fadeInUs,
                                fadeOutUs = cmd.fadeOutUs?.coerceIn(0L, 5_000_000L) ?: item.fadeOutUs,
                                duckUnderSpeech = cmd.duckUnderSpeech ?: item.duckUnderSpeech,
                                timelineStartUs = cmd.timelineStartUs?.coerceAtLeast(0L) ?: item.timelineStartUs,
                                editedByUser = item.editedByUser || cmd.byUser,
                            )
                        })
                    }
                }
                AppResult.Success(state.copy(audioTracks = updated))
            }

            is TimelineCommand.DeleteAudioItem -> {
                val targetTrack = cmd.trackId?.let { id -> state.audioTracks.firstOrNull { it.id == id } }
                    ?: state.audioTracks.firstOrNull { t -> t.items.any { it.id == cmd.itemId } }
                if (targetTrack?.locked == true) {
                    return AppResult.Failure(AppError.Validation("Track '${targetTrack.name}' is locked. Unlock it first."))
                }
                val updated = state.audioTracks.map { t ->
                    if (cmd.trackId != null && t.id != cmd.trackId) t else t.copy(items = t.items.filterNot { it.id == cmd.itemId })
                }
                AppResult.Success(state.copy(audioTracks = updated))
            }

            is TimelineCommand.SetAudioTrackMix -> {
                checkAudioTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val tracks = ensureAudioTrack(state.audioTracks, cmd.trackId, AudioTrackType.MUSIC)
                val updated = tracks.map { t ->
                    if (t.id != cmd.trackId) t else t.copy(
                        volume = cmd.volume.coerceIn(0f, 1.5f),
                        muted = cmd.muted,
                        voiceEnhance = cmd.voiceEnhance ?: t.voiceEnhance,
                        normalizeTargetDb = cmd.normalizeTargetDb ?: t.normalizeTargetDb,
                    )
                }
                AppResult.Success(state.copy(audioTracks = updated))
            }

            is TimelineCommand.AddOrUpdateCaption -> {
                checkCaptionTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                if (cmd.caption.endUs <= cmd.caption.startUs || cmd.caption.text.isBlank()) {
                    return AppResult.Failure(AppError.Validation("Caption requires non-empty text and valid start/end times."))
                }
                val tracks = if (state.captionTracks.any { it.id == cmd.trackId }) state.captionTracks else state.captionTracks + CaptionTrack(id = cmd.trackId)
                val updated = tracks.map { t ->
                    if (t.id != cmd.trackId) t else {
                        val rest = t.items.filterNot { it.id == cmd.caption.id }
                        t.copy(items = (rest + cmd.caption.copy(editedByUser = cmd.caption.editedByUser || cmd.byUser)).sortedBy { it.startUs })
                    }
                }
                AppResult.Success(state.copy(captionTracks = updated))
            }

            is TimelineCommand.DeleteCaption -> {
                checkCaptionTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val updated = state.captionTracks.map { t ->
                    if (t.id != cmd.trackId) t else t.copy(items = t.items.filterNot { it.id == cmd.captionId })
                }
                AppResult.Success(state.copy(captionTracks = updated))
            }

            is TimelineCommand.ReplaceCaptions -> {
                checkCaptionTrackUnlocked(state, cmd.trackId)?.let { return AppResult.Failure(it) }
                val clean = cmd.captions.filter { it.endUs > it.startUs && it.text.isNotBlank() }
                    .sortedBy { it.startUs }
                val resolved = resolveCaptionOverlaps(clean)
                val tracks = if (state.captionTracks.any { it.id == cmd.trackId }) state.captionTracks else state.captionTracks + CaptionTrack(id = cmd.trackId)
                val updated = tracks.map { t -> if (t.id == cmd.trackId) t.copy(items = resolved) else t }
                AppResult.Success(state.copy(captionTracks = updated))
            }

            is TimelineCommand.ToggleTrackLock -> AppResult.Success(
                state.copy(
                    videoTracks = state.videoTracks.map { if (it.id == cmd.trackId) it.copy(locked = !it.locked) else it },
                    audioTracks = state.audioTracks.map { if (it.id == cmd.trackId) it.copy(locked = !it.locked) else it },
                    captionTracks = state.captionTracks.map { if (it.id == cmd.trackId) it.copy(locked = !it.locked) else it },
                )
            )

            is TimelineCommand.ToggleTrackVisibility -> AppResult.Success(
                state.copy(
                    videoTracks = state.videoTracks.map { if (it.id == cmd.trackId) it.copy(visible = !it.visible) else it },
                    audioTracks = state.audioTracks.map { if (it.id == cmd.trackId) it.copy(muted = !it.muted) else it },
                    captionTracks = state.captionTracks.map { if (it.id == cmd.trackId) it.copy(visible = !it.visible) else it },
                )
            )

            is TimelineCommand.SetProjectStyle -> AppResult.Success(
                state.copy(
                    aspect = cmd.aspect ?: state.aspect,
                    customAspectWidth = cmd.customAspectWidth?.coerceIn(100, 4096) ?: state.customAspectWidth,
                    customAspectHeight = cmd.customAspectHeight?.coerceIn(100, 4096) ?: state.customAspectHeight,
                    colorLook = cmd.colorLook ?: state.colorLook,
                    mood = cmd.mood ?: state.mood,
                    titleOverlay = if (cmd.clearTitle) null else (cmd.titleOverlay ?: state.titleOverlay),
                )
            )

            is TimelineCommand.SetExportConfig -> AppResult.Success(
                state.copy(
                    resolution = cmd.resolution,
                    fps = cmd.fps.coerceIn(24, 60),
                    videoBitrateBps = cmd.videoBitrateBps.coerceIn(2_000_000, 50_000_000),
                    audioBitrateBps = cmd.audioBitrateBps?.coerceIn(96_000, 320_000) ?: state.audioBitrateBps,
                    preferredHevc = cmd.preferredHevc ?: state.preferredHevc,
                )
            )

            is TimelineCommand.SetBeatMarkers -> AppResult.Success(
                state.copy(beatMarkersUs = cmd.beatMarkersUs.distinct().sorted())
            )
        }
    }

    private fun mutateVideoItem(
        state: TimelineProject,
        trackId: String,
        itemId: String,
        transform: (VideoClipItem) -> VideoClipItem,
    ): AppResult<TimelineProject> {
        val track = state.videoTracks.firstOrNull { it.id == trackId }
            ?: state.videoTracks.firstOrNull { t -> t.items.any { it.id == itemId } }
            ?: return AppResult.Failure(AppError.Validation("Track $trackId not found."))
        if (track.locked) {
            return AppResult.Failure(AppError.Validation("Track '${track.name}' is locked. Unlock it first."))
        }
        if (track.items.none { it.id == itemId }) {
            return AppResult.Failure(AppError.Validation("Clip not found on timeline."))
        }
        val mapped = track.items.map { if (it.id == itemId) transform(it) else it }
        val updated = state.videoTracks.map {
            if (it.id == track.id) it.copy(items = reflowMainIfNeeded(it.isMain, mapped)) else it
        }
        return AppResult.Success(state.copy(videoTracks = updated))
    }

    private fun ensureVideoTrack(tracks: List<VideoTrack>, trackId: String): List<VideoTrack> {
        if (tracks.any { it.id == trackId }) return tracks
        return tracks + VideoTrack(
            id = trackId,
            name = if (trackId == "video_main") "Video 1" else "Video 2 (Overlay)",
            isMain = trackId == "video_main",
        )
    }

    private fun ensureAudioTrack(tracks: List<AudioTrack>, trackId: String, fallbackType: AudioTrackType): List<AudioTrack> {
        if (tracks.any { it.id == trackId }) return tracks
        val label = when (trackId) {
            "audio_music" -> "A1 · Music"
            "audio_vo" -> "A2 · Voiceover"
            "audio_sfx" -> "A3 · SFX"
            else -> fallbackType.label
        }
        val type = when (trackId) {
            "audio_vo" -> AudioTrackType.VOICEOVER
            "audio_sfx" -> AudioTrackType.SFX
            else -> fallbackType
        }
        return tracks + AudioTrack(id = trackId, name = label, type = type)
    }

    private fun reflowMainIfNeeded(isMain: Boolean, items: List<VideoClipItem>): List<VideoClipItem> {
        if (!isMain) return items
        var cursor = 0L
        return items.map { item ->
            val placed = item.copy(timelineStartUs = cursor)
            cursor += placed.timelineDurationUs
            placed
        }
    }

    private fun resolveCaptionOverlaps(sorted: List<CaptionItem>): List<CaptionItem> {
        if (sorted.size <= 1) return sorted
        val out = mutableListOf<CaptionItem>()
        for (c in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && c.startUs < prev.endUs) {
                val adjustedStart = prev.endUs
                if (c.endUs - adjustedStart >= 250_000L) {
                    out += c.copy(startUs = adjustedStart)
                }
            } else {
                out += c
            }
        }
        return out
    }

    companion object {
        const val MIN_CLIP_US = 200_000L
        const val MAX_HISTORY = 60
    }
}
