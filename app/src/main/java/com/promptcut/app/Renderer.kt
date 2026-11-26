package com.promptcut.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.promptcut.app.audio.AudioStudioProcessor
import com.promptcut.app.intelligence.CaptionTools
import com.promptcut.app.render.DeviceCapabilities
import com.promptcut.app.render.ExportDiagnostics
import com.promptcut.app.render.ExportSettings
import com.promptcut.app.render.ExportVerificationResult
import com.promptcut.app.render.RenderCheckpoint
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionAnimation
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RenderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns an [EditPlan] or [TimelineProject] into a verified MP4 on the phone using Media3 Transformer.
 * Supports V1/V2 video compositing, OpenGL ES per-clip shader effects, speed ramps, keyframes,
 * rich bitmap-rendered captions, multi-track A1/A2/A3 audio mixing, HEVC/H.264, and Safe Render recovery.
 */
@OptIn(UnstableApi::class)
object Renderer {
    private const val TAG = "PromptCut"
    private const val MUXER_PATIENCE_MS = 45_000L

    suspend fun render(
        context: Context,
        plan: EditPlan,
        clips: List<Clip>,
        music: MusicMux.Source?,
        forceSafeMode: Boolean = false,
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): File {
        val project = TimelineProject.fromEditPlan(plan, clips.map { it.meta })
        return renderProject(
            context = context,
            project = project,
            clips = clips,
            music = music,
            exportSettings = ExportSettings(forceSafeMode = forceSafeMode, burnCaptions = false),
            onStatus = onStatus,
            onProgress = onProgress,
        )
    }

    suspend fun renderProject(
        context: Context,
        project: TimelineProject,
        clips: List<Clip>,
        music: MusicMux.Source?,
        exportSettings: ExportSettings = ExportSettings(),
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): File = renderProjectWithDiagnostics(
        context = context,
        project = project,
        clips = clips,
        music = music,
        exportSettings = exportSettings,
        onStatus = onStatus,
        onProgress = onProgress,
    ).first

    suspend fun renderProjectWithDiagnostics(
        context: Context,
        project: TimelineProject,
        clips: List<Clip>,
        music: MusicMux.Source?,
        exportSettings: ExportSettings = ExportSettings(),
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): Pair<File, ExportDiagnostics> {
        val startMs = System.currentTimeMillis()
        val plan = project.toEditPlan()
        val effectiveSettings = exportSettings.copy(
            customAspectWidth = project.customAspectWidth,
            customAspectHeight = project.customAspectHeight,
        )
        val caps = DeviceCapabilities.probe(context)
        val useHevc = effectiveSettings.useHevc && caps.supportsHevc
        val codecLabel = if (useHevc) "H.265 (HEVC)" else "H.264 (AVC)"
        val (outW, outH) = effectiveSettings.outputDimensions(plan.aspect)

        val dir = File(context.cacheDir, "render").apply { mkdirs() }
        val checkpointFile = File(dir, "checkpoint.json")
        val planHash = "${project.id}:${plan.toJson().hashCode()}:${effectiveSettings.resolution}:${effectiveSettings.fps}:${effectiveSettings.videoBitrateBps}:$useHevc"
        val out = File(dir, "promptcut_${System.currentTimeMillis()}.mp4")

        val existingCp = RenderCheckpoint.loadFrom(checkpointFile, project.id, planHash)
        if (existingCp == null) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.listFiles()?.filter { it.name.startsWith("promptcut_") || it.name.startsWith("final_") }?.forEach { it.delete() }
        }

        val hasExtraAudio = music != null || project.audioTracks.any { !it.muted && it.items.isNotEmpty() }
        var usedSafeMode = effectiveSettings.forceSafeMode

        val video = run {
            if (!effectiveSettings.forceSafeMode) {
                try {
                    export(
                        context = context,
                        composition = fullComposition(plan, project, clips, effectiveSettings),
                        out = out,
                        exportSettings = effectiveSettings,
                        useHevc = useHevc,
                    ) { p -> onProgress(if (hasExtraAudio) p * 9 / 10 else p) }
                    checkpointFile.delete()
                    return@run out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "One-pass render failed, switching to safe mode", e)
                    out.delete()
                }
            }
            usedSafeMode = true
            onStatus("Rendering shot by shot (safe mode)…")
            renderSafe(
                context = context,
                plan = plan,
                project = project,
                clips = clips,
                dir = dir,
                out = out,
                exportSettings = effectiveSettings,
                useHevc = useHevc,
                checkpointFile = checkpointFile,
                initialCheckpoint = existingCp ?: RenderCheckpoint(project.id, planHash, plan.segments.size),
                onStatus = onStatus,
            ) { p -> onProgress(if (hasExtraAudio) p * 9 / 10 else p) }
        }

        val finalVideo = if (!hasExtraAudio) {
            video
        } else {
            onStatus("Mixing audio tracks…")
            val mixedOut = File(dir, "final_${System.currentTimeMillis()}.mp4")
            try {
                withContext(Dispatchers.Default) {
                    MusicMux.mixTimelineAudio(
                        context = context,
                        video = video,
                        project = project,
                        music = music,
                        audioBitrateBps = effectiveSettings.audioBitrateBps,
                        out = mixedOut,
                    )
                }
                onProgress(100)
                video.delete()
                mixedOut
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Audio mixing failed, falling back to base audio", e)
                mixedOut.delete()
                onStatus("Couldn't mix external audio on this device; kept clip audio.")
                video
            }
        }

        // Clean up any leftover intermediate artifacts
        dir.listFiles()?.filter { it != finalVideo && (it.name.startsWith("part_") || it.name.startsWith("promptcut_") && it != finalVideo) }
            ?.forEach { it.delete() }

        val verification = verifyExportedMp4(
            file = finalVideo,
            expectAudio = hasExtraAudio || project.mainVideoTrack.items.any { !it.muted && it.volume > 0.02f },
        )
        if (!verification.valid) {
            throw RenderException(verification.errorReason ?: "Exported MP4 failed verification.")
        }

        val diag = ExportDiagnostics(
            codec = codecLabel,
            width = verification.width.takeIf { it > 0 } ?: outW,
            height = verification.height.takeIf { it > 0 } ?: outH,
            fps = effectiveSettings.fps,
            videoBitrateBps = effectiveSettings.videoBitrateBps,
            audioBitrateBps = effectiveSettings.audioBitrateBps,
            durationMs = verification.durationMs,
            fileSizeBytes = verification.fileSizeBytes,
            hasVideoTrack = verification.hasVideoTrack,
            hasAudioTrack = verification.hasAudioTrack,
            renderTimeMs = System.currentTimeMillis() - startMs,
            usedSafeMode = usedSafeMode,
            verifiedOk = verification.valid,
        )
        return finalVideo to diag
    }

    fun verifyExportedMp4(file: File, expectAudio: Boolean): ExportVerificationResult {
        if (!file.exists() || file.length() < 8_192L) {
            return ExportVerificationResult(false, false, false, 0L, 0, 0, file.length(), "Output file is empty or corrupted.")
        }
        val ex = MediaExtractor()
        var hasVideo = false
        var hasAudio = false
        var durationMs = 0L
        var width = 0
        var height = 0
        try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (fmt.containsKey(MediaFormat.KEY_WIDTH)) width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = maxOf(durationMs, fmt.getLong(MediaFormat.KEY_DURATION) / 1000L)
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
            if (!hasVideo) {
                return ExportVerificationResult(false, false, hasAudio, durationMs, width, height, file.length(), "Rendered MP4 contains no video track.")
            }
            if (durationMs <= 100L) {
                runCatching {
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(file.absolutePath)
                        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: durationMs
                    }
                }
            }
            if (expectAudio && !hasAudio) {
                Log.w(TAG, "Verified MP4 missing optional audio track")
            }
            return ExportVerificationResult(
                valid = hasVideo && durationMs > 0L,
                hasVideoTrack = hasVideo,
                hasAudioTrack = hasAudio,
                durationMs = durationMs,
                width = width,
                height = height,
                fileSizeBytes = file.length(),
            )
        } catch (e: Exception) {
            return ExportVerificationResult(false, false, false, 0L, 0, 0, file.length(), "Could not read exported MP4 container: ${e.message}")
        } finally {
            ex.release()
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun speedProviderFor(item: VideoClipItem?, fallbackSpeed: Double): SpeedProvider {
        if (item != null && item.speedRamp.size >= 2) {
            return SpeedRampProvider(fallbackSpeed, item.speedRamp, item.sourceDurationUs)
        }
        return object : SpeedProvider {
            override fun getSpeed(timeUs: Long) = fallbackSpeed.toFloat().coerceIn(0.25f, 4.0f)
            override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
        }
    }

    private fun clipped(uri: Uri, startSec: Double, endSec: Double?): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((startSec * 1000).toLong())
                    .apply { if (endSec != null) setEndPositionMs((endSec * 1000).toLong()) }
                    .build()
            )
            .build()

    private fun shotItem(
        plan: EditPlan,
        project: TimelineProject,
        seg: Segment,
        clip: Clip,
        index: Int,
        timelineStartUs: Long,
        keepClipSound: Boolean,
        exportSettings: ExportSettings,
        includeShaders: Boolean = true,
    ): EditedMediaItem {
        val video = mutableListOf<Effect>()
        val audio = mutableListOf<AudioProcessor>()
        val timelineItem = project.mainVideoTrack.items.getOrNull(index)
        val soundOn = keepClipSound && clip.meta.hasAudio && !project.mainVideoTrack.muted

        if (soundOn) {
            val g = (plan.originalVolume * seg.soundGain).toFloat()
            val v1Track = project.mainVideoTrack
            val audioTrackForGain = project.audioTracks.firstOrNull { it.type == AudioTrackType.VOICEOVER }
            if (g < 0.99f || audioTrackForGain?.voiceEnhance == true) {
                audio += AudioStudioProcessor(
                    gain = g.coerceIn(0f, 1.5f),
                    voiceEnhance = audioTrackForGain?.voiceEnhance == true,
                    normalizeTargetDb = audioTrackForGain?.normalizeTargetDb,
                )
            }
            audio += FadeProcessor(seg.outputSec, 0.03, 0.06)
        }

        video += FrameDropEffect.createDefaultFrameDropEffect(exportSettings.fps.toFloat())
        val (outW, outH) = exportSettings.outputDimensions(plan.aspect)
        val userCrop = timelineItem?.crop ?: clip.meta.crop
        if (userCrop != null) {
            video += androidx.media3.effect.Crop(
                (2 * userCrop.x0 - 1).toFloat(), (2 * userCrop.x1 - 1).toFloat(),
                (1 - 2 * userCrop.y1).toFloat(), (1 - 2 * userCrop.y0).toFloat(),
            )
            video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        } else {
            reframe(clip, seg, plan.aspect)?.let { video += it }
            video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }

        val look = timelineItem?.colorLookOverride ?: plan.color
        video += Looks.effects(look)
        if (timelineItem != null) {
            video += Looks.buildClipAdjustments(timelineItem, includeShaders = includeShaders)
        }

        fun continues(a: Segment?, b: Segment?) =
            a != null && b != null && a.clip == b.clip && b.start >= a.end - 0.01 && b.start - a.end < 8.0

        val transition = timelineItem?.transitionIn?.takeIf { index > 0 } ?: plan.effectiveTransition
        video += Transitions.effects(
            transition, index, plan.segments.size, seg.outputSec,
            if (userCrop != null) null else faceInOutput(clip, seg, plan.aspect),
            joinIn = continues(plan.segments.getOrNull(index - 1), seg),
            joinOut = continues(seg, plan.segments.getOrNull(index + 1)),
        )

        if (index == 0 && plan.title != null) video += titleOverlay(plan.title)

        if (exportSettings.burnCaptions && project.mainCaptionTrack.visible) {
            val segEndUs = timelineStartUs + (seg.outputSec * 1_000_000.0).toLong()
            val overlappingCaptions = project.mainCaptionTrack.items.filter { c ->
                c.endUs > timelineStartUs && c.startUs < segEndUs
            }
            if (overlappingCaptions.isNotEmpty()) {
                video += timedCaptionOverlay(timelineStartUs, outW, outH, overlappingCaptions)
            }
        }

        val spProvider = speedProviderFor(timelineItem, seg.speed)
        return EditedMediaItem.Builder(clipped(clip.uri, seg.start, seg.end))
            .apply {
                if (seg.speed != 1.0 || (timelineItem?.speedRamp?.size ?: 0) >= 2) {
                    setSpeed(spProvider)
                }
            }
            .setRemoveAudio(!soundOn)
            .setEffects(Effects(audio, video))
            .build()
    }

    private fun faceInOutput(clip: Clip, seg: Segment, aspect: Aspect): Float? {
        val fx = seg.focusX ?: return null
        val ndc = (2 * fx - 1).toFloat()
        if (clip.meta.width <= 0 || clip.meta.height <= 0) return ndc
        val src = clip.meta.width.toDouble() / clip.meta.height
        val dst = aspect.width.toDouble() / aspect.height
        if (src <= dst * 1.05) return ndc
        val w = (dst / src).toFloat()
        val left = (ndc - w).coerceIn(-1f, 1f - 2 * w)
        return ((ndc - left) / w - 1f).coerceIn(-1f, 1f)
    }

    private fun reframe(clip: Clip, seg: Segment, aspect: Aspect): Effect? {
        val fx = seg.focusX ?: return null
        if (clip.meta.width <= 0 || clip.meta.height <= 0) return null
        val src = clip.meta.width.toDouble() / clip.meta.height
        val dst = aspect.width.toDouble() / aspect.height
        if (src <= dst * 1.05) return null
        val w = (dst / src).toFloat()
        val left = ((2 * fx - 1).toFloat() - w).coerceIn(-1f, 1f - 2 * w)
        return androidx.media3.effect.Crop(left, left + 2 * w, -1f, 1f)
    }

    private fun fullComposition(
        plan: EditPlan,
        project: TimelineProject,
        clips: List<Clip>,
        exportSettings: ExportSettings,
    ): Composition {
        val byIndex = clips.associateBy { it.meta.index }
        val keepClipSound = plan.segments.any { byIndex[it.clip]?.meta?.hasAudio == true && plan.originalVolume * it.soundGain > 0.02 }
        var cursorUs = 0L
        val items = plan.segments.mapIndexedNotNull { i, seg ->
            val clip = byIndex[seg.clip] ?: return@mapIndexedNotNull null
            val item = shotItem(plan, project, seg, clip, i, cursorUs, keepClipSound, exportSettings, includeShaders = true)
            cursorUs += (seg.outputSec * 1_000_000.0).toLong()
            item
        }
        val mainSeq = EditedMediaItemSequence.Builder(items)
            .experimentalSetForceAudioTrack(keepClipSound)
            .build()

        val sequences = mutableListOf(mainSeq)

        // Optional V2 Picture-in-Picture / B-Roll overlay sequence
        val v2Track = project.overlayVideoTrack
        if (v2Track != null && v2Track.visible && v2Track.items.isNotEmpty()) {
            val (outW, outH) = exportSettings.outputDimensions(plan.aspect)
            val v2Builder = EditedMediaItemSequence.Builder(emptyList())
            var v2Cursor = 0L
            var addedCount = 0
            v2Track.items.sortedBy { it.timelineStartUs }.forEach { ov ->
                val clip = byIndex[ov.sourceClipIndex] ?: return@forEach
                val gap = (ov.timelineStartUs - v2Cursor).coerceAtLeast(0L)
                if (gap > 10_000L) v2Builder.addGap(gap)
                val bounds = ov.overlayBounds ?: com.promptcut.app.timeline.OverlayBounds()
                val ovW = ((outW * bounds.widthFraction).toInt().coerceIn(64, outW) / 2) * 2
                val ovH = ((outH * bounds.widthFraction).toInt().coerceIn(64, outH) / 2) * 2
                val ovEffects = mutableListOf<Effect>(
                    Presentation.createForWidthAndHeight(ovW, ovH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
                )
                ovEffects += Looks.buildClipAdjustments(ov, includeShaders = true)
                val editedOv = EditedMediaItem.Builder(
                    clipped(clip.uri, usToSec(ov.sourceStartUs), usToSec(ov.sourceEndUs))
                )
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), ovEffects))
                    .build()
                v2Builder.addItem(editedOv)
                v2Cursor = ov.timelineEndUs
                addedCount++
            }
            if (addedCount > 0) {
                sequences += v2Builder.build()
            }
        }

        val compBuilder = Composition.Builder(sequences)
            .apply { if (clips.any { it.isHdr }) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .setEffects(Effects(listOf(FadeProcessor(plan.totalSec)), emptyList()))

        if (sequences.size > 1) {
            compBuilder.setVideoCompositorSettings(object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()
                override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                    if (inputId == 0) return StaticOverlaySettings.Builder().build()
                    val ov = project.activeOverlaysAt(presentationTimeUs).firstOrNull()
                        ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                    val b = ov.overlayBounds ?: com.promptcut.app.timeline.OverlayBounds()
                    val anchorX = ((b.x + b.widthFraction * 0.5f) * 2f - 1f).coerceIn(-0.95f, 0.95f)
                    val anchorY = (1f - (b.y + b.widthFraction * 0.35f) * 2f).coerceIn(-0.95f, 0.95f)
                    return StaticOverlaySettings.Builder()
                        .setBackgroundFrameAnchor(anchorX, anchorY)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setAlphaScale(b.opacity.coerceIn(0.05f, 1f))
                        .build()
                }
            })
        }
        return compBuilder.build()
    }

    // ------------------------------------------------------------------ safe mode + checkpoint

    private suspend fun renderSafe(
        context: Context,
        plan: EditPlan,
        project: TimelineProject,
        clips: List<Clip>,
        dir: File,
        out: File,
        exportSettings: ExportSettings,
        useHevc: Boolean,
        checkpointFile: File,
        initialCheckpoint: RenderCheckpoint,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): File {
        val byIndex = clips.associateBy { it.meta.index }
        val keepClipSound = plan.segments.any { byIndex[it.clip]?.meta?.hasAudio == true && plan.originalVolume * it.soundGain > 0.02 }
        val parts = mutableListOf<File>()
        val n = plan.segments.size
        var skipped = 0
        var checkpoint = initialCheckpoint
        var cursorUs = 0L

        plan.segments.forEachIndexed { i, seg0 ->
            val clip = byIndex[seg0.clip] ?: return@forEachIndexed
            val segStartUs = cursorUs
            cursorUs += (seg0.outputSec * 1_000_000.0).toLong()

            val part = File(dir, "part_$i.mp4")
            if (checkpoint.isCompleteFor(i, part)) {
                parts += part
                onProgress(((i + 1) * 80) / n.coerceAtLeast(1))
                return@forEachIndexed
            }

            onStatus("Rendering shot ${i + 1} of $n (safe mode)…")
            val scale: (Int) -> Unit = { p -> onProgress((i * 100 + p) * 80 / (n * 100).coerceAtLeast(1)) }
            fun single(seg: Segment, withShaders: Boolean) = Composition.Builder(
                EditedMediaItemSequence.Builder(
                    listOf(shotItem(plan, project, seg, clip, i, segStartUs, keepClipSound && clip.meta.hasAudio, exportSettings, includeShaders = withShaders))
                )
                    .experimentalSetForceAudioTrack(true)
                    .build()
            ).apply { if (clip.isHdr) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }.build()

            val ok = try {
                export(context, single(seg0, withShaders = true), part, exportSettings, useHevc = false, scale); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shot $i failed, retrying safe fallback without GL shaders", e)
                val plain = seg0.copy(end = (seg0.start + seg0.outputSec).coerceAtMost(clip.meta.durationSec), speed = 1.0)
                try {
                    export(context, single(plain, withShaders = false), part, exportSettings, useHevc = false, scale); true
                } catch (e2: CancellationException) {
                    throw e2
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                parts += part
                checkpoint = checkpoint.markCompleted(i)
                checkpoint.saveTo(checkpointFile)
            } else skipped++
        }
        if (parts.isEmpty()) throw RenderException("Couldn't render any of the shots on this phone. Try fewer or shorter clips.")

        onStatus("Joining ${parts.size} shots…")
        val joined = parts.map { f ->
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).build()
        }
        val finalComp = Composition.Builder(EditedMediaItemSequence.Builder(joined).experimentalSetForceAudioTrack(true).build())
            .setEffects(Effects(listOf(FadeProcessor(plan.totalSec)), emptyList()))
            .build()
        try {
            export(context, finalComp, out, exportSettings, useHevc = false) { p -> onProgress(80 + p / 5) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RenderException("Joining the shots failed: ${(e as? ExportException)?.errorCodeName ?: e.message}", e)
        }
        parts.forEach { it.delete() }
        checkpointFile.delete()
        if (skipped > 0) onStatus("Done. $skipped shot(s) couldn't be rendered and were left out.")
        return out
    }

    // ------------------------------------------------------------------ export

    private suspend fun export(
        context: Context,
        composition: Composition,
        out: File,
        exportSettings: ExportSettings,
        useHevc: Boolean,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                val holder = ProgressHolder()
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, 300)
                }
            }
            val mime = if (useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
            transformer = Transformer.Builder(context)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(exportSettings.videoBitrateBps)
                                .setMaxBFrames(0)
                                .build()
                        )
                        .build()
                )
                .setVideoMimeType(mime)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setMaxDelayBetweenMuxerSamplesMs(MUXER_PATIENCE_MS)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(
                            RenderException("Rendering failed: ${exportException.errorCodeName}", exportException)
                        )
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); transformer.cancel() } }
        }
    }

    private fun titleOverlay(title: String): OverlayEffect {
        val text = SpannableString(" $title ")
        val all = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        text.setSpan(AbsoluteSizeSpan(88), 0, text.length, all)
        text.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, all)
        text.setSpan(BackgroundColorSpan(Color.argb(140, 0, 0, 0)), 0, text.length, all)
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.6f)
            .build()
        return OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(text, settings)))
    }

    private fun timedCaptionOverlay(
        shotTimelineStartUs: Long,
        outWidth: Int,
        outHeight: Int,
        captions: List<CaptionItem>,
    ): OverlayEffect {
        val emptyBmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bitmapCache = HashMap<String, Bitmap>()
        val overlay = object : BitmapOverlay() {
            private var firstPtsUs = Long.MIN_VALUE

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                if (firstPtsUs == Long.MIN_VALUE || presentationTimeUs < firstPtsUs) {
                    firstPtsUs = presentationTimeUs
                }
                val globalUs = shotTimelineStartUs + (presentationTimeUs - firstPtsUs)
                val active = captions.firstOrNull { globalUs in it.startUs..it.endUs } ?: return emptyBmp
                val key = "${active.id}_${active.text}_${active.style}_${active.font}_${active.fontSizeSp}_${active.bgAlpha}"
                return bitmapCache.getOrPut(key) {
                    renderCaptionBitmap(active, outWidth)
                }
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                if (firstPtsUs == Long.MIN_VALUE || presentationTimeUs < firstPtsUs) {
                    firstPtsUs = presentationTimeUs
                }
                val globalUs = shotTimelineStartUs + (presentationTimeUs - firstPtsUs)
                val active = captions.firstOrNull { globalUs in it.startUs..it.endUs }
                    ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                val elapsed = ((globalUs - active.startUs).coerceAtLeast(0L) / 1_000_000.0).toFloat()
                val animFrac = (elapsed / 0.18f).coerceIn(0f, 1f)
                val yFrac = CaptionTools.clampSafeAreaY(active.positionYFraction)
                val baseAnchorY = 1f - yFrac * 2f // +1 top .. -1 bottom
                val anchorY = when (active.animation) {
                    CaptionAnimation.SLIDE_UP -> baseAnchorY - (1f - animFrac) * 0.08f
                    else -> baseAnchorY
                }.coerceIn(-0.92f, 0.92f)
                val scale = when (active.animation) {
                    CaptionAnimation.POP -> 0.85f + 0.15f * animFrac
                    else -> 1f
                }
                val alpha = when (active.animation) {
                    CaptionAnimation.FADE -> animFrac
                    else -> 1f
                }
                return StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, anchorY)
                    .setOverlayFrameAnchor(0f, 0f)
                    .setScale(scale, scale)
                    .setAlphaScale(alpha)
                    .build()
            }
        }
        return OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
    }

    private fun renderCaptionBitmap(caption: CaptionItem, outWidth: Int): Bitmap {
        val maxTextWidth = (outWidth * 0.86f).toInt().coerceAtLeast(200)
        val scaleFactor = (outWidth / 1080f).coerceIn(0.6f, 2.2f)
        val pxSize = (caption.fontSizeSp * 2.6f * scaleFactor).coerceIn(28f, 110f)
        val tf = when (caption.font) {
            CaptionFont.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            CaptionFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            CaptionFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = pxSize
            typeface = tf
            if (caption.shadowEnabled) setShadowLayer(4f * scaleFactor, 0f, 2f * scaleFactor, Color.argb(200, 0, 0, 0))
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = (caption.strokeWidth * 1.6f * scaleFactor).coerceAtLeast(1f)
            color = Color.BLACK
            clearShadowLayer()
        }

        // Word-wrap into up to 3 balanced lines
        val words = caption.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val cand = if (cur.isEmpty()) w else "$cur $w"
            if (fillPaint.measureText(cand) <= maxTextWidth - 48) {
                cur = StringBuilder(cand)
            } else {
                if (cur.isNotEmpty()) lines += cur.toString()
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        if (lines.isEmpty()) return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val fm = fillPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.18f
        val textW = lines.maxOf { fillPaint.measureText(it) }.toInt()
        val padH = (28 * scaleFactor).toInt()
        val padV = (16 * scaleFactor).toInt()
        val bmpW = (textW + padH * 2).coerceIn(32, outWidth)
        val bmpH = (lines.size * lineHeight + padV * 2).toInt().coerceAtLeast(32)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (caption.style != CaptionStyle.MINIMAL && caption.bgAlpha > 0.02f) {
            val alpha = if (caption.style == CaptionStyle.BOLD_BOX) {
                (caption.bgAlpha * 255).toInt().coerceIn(80, 240)
            } else {
                (caption.bgAlpha * 195).toInt().coerceIn(60, 210)
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(alpha, 12, 12, 12) }
            val radius = (14f * scaleFactor)
            canvas.drawRoundRect(RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()), radius, radius, bgPaint)
        }

        val keywords = caption.highlightKeywords.map { it.lowercase() }.toSet()
        lines.forEachIndexed { idx, line ->
            val y = padV - fm.ascent + idx * lineHeight
            val lineW = fillPaint.measureText(line)
            var x = (bmpW - lineW) / 2f
            val lineWords = line.split(" ")
            val spaceW = fillPaint.measureText(" ")
            lineWords.forEachIndexed { wi, w ->
                val clean = w.trim(',', '.', '!', '?', ':', ';').lowercase()
                val isKw = clean in keywords
                if (isKw) {
                    val wWidth = fillPaint.measureText(w)
                    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    val invPaint = Paint(fillPaint).apply {
                        color = Color.BLACK
                        clearShadowLayer()
                    }
                    canvas.drawRoundRect(
                        RectF(x - 6f * scaleFactor, y + fm.ascent * 0.9f, x + wWidth + 6f * scaleFactor, y + fm.descent * 0.85f),
                        8f * scaleFactor,
                        8f * scaleFactor,
                        pillPaint,
                    )
                    canvas.drawText(w, x, y, invPaint)
                } else {
                    if (caption.strokeWidth > 0.2f) canvas.drawText(w, x, y, strokePaint)
                    canvas.drawText(w, x, y, fillPaint)
                }
                x += fillPaint.measureText(w) + if (wi < lineWords.lastIndex) spaceW else 0f
            }
        }
        return bmp
    }

    suspend fun saveToGallery(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "PromptCut_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PromptCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw RenderException("Couldn't create a file in your gallery.")
        try {
            val os = resolver.openOutputStream(uri)
                ?: throw RenderException("Couldn't open gallery output stream.")
            os.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
