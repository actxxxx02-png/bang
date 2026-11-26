package com.promptcut.app.timeline

import com.promptcut.app.Aspect
import com.promptcut.app.ClipMeta
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.EditPlan
import com.promptcut.app.ExportResolution
import com.promptcut.app.Mood
import com.promptcut.app.Segment
import com.promptcut.app.Transition
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val US_PER_SEC = 1_000_000L

fun secToUs(sec: Double): Long = (sec * US_PER_SEC).roundToLong().coerceAtLeast(0L)
fun usToSec(us: Long): Double = us / 1_000_000.0

enum class KeyframeProperty(val label: String) {
    SCALE("Scale"),
    OPACITY("Opacity"),
    VOLUME("Volume"),
    PAN_X("Pan X"),
    PAN_Y("Pan Y"),
}

data class Keyframe(
    val timeOffsetUs: Long,
    val property: KeyframeProperty,
    val value: Float,
)

/**
 * Per-clip renderable visual effect parameters. Every field maps directly to a Media3
 * Effect / GL shader or transition curve inside [Renderer] and [PreviewStage].
 */
data class EffectSettings(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val blur: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
    val filmGrain: Float = 0f,
    val glow: Float = 0f,
    val shake: Float = 0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
) {
    val hasActiveAdjustments: Boolean
        get() = kotlin.math.abs(brightness) > 0.01f ||
            kotlin.math.abs(contrast) > 0.01f ||
            kotlin.math.abs(saturation - 1f) > 0.01f ||
            blur > 0.01f ||
            sharpen > 0.01f ||
            vignette > 0.01f ||
            filmGrain > 0.01f ||
            glow > 0.01f ||
            shake > 0.01f ||
            fadeInUs > 0L ||
            fadeOutUs > 0L
}

data class SpeedRampPoint(
    val progress: Float, // 0f..1f within segment
    val speed: Double,   // 0.25..4.0
)

data class OverlayBounds(
    val x: Float = 0.66f,
    val y: Float = 0.06f,
    val widthFraction: Float = 0.30f,
    val opacity: Float = 1.0f,
)

data class VideoClipItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceClipIndex: Int,
    val sourceUri: String = "",
    val timelineStartUs: Long,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val speed: Double = 1.0,
    val speedRamp: List<SpeedRampPoint> = emptyList(),
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val crop: CropBox? = null,
    val focusX: Double? = null,
    val transitionIn: Transition = Transition.CUT,
    val transitionDurationUs: Long = 350_000L,
    val colorLookOverride: ColorLook? = null,
    val effects: EffectSettings = EffectSettings(),
    val overlayBounds: OverlayBounds? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val why: String = "",
    val editedByUser: Boolean = false,
) {
    val sourceDurationUs: Long get() = (sourceEndUs - sourceStartUs).coerceAtLeast(0L)
    val effectiveSpeed: Double
        get() = if (speedRamp.size >= 2) {
            speedRamp.map { it.speed.coerceIn(0.25, 4.0) }.average()
        } else {
            speed.coerceIn(0.25, 4.0)
        }
    val timelineDurationUs: Long get() = (sourceDurationUs / effectiveSpeed).roundToLong().coerceAtLeast(100_000L)
    val timelineEndUs: Long get() = timelineStartUs + timelineDurationUs
}

data class VideoTrack(
    val id: String = "video_main",
    val name: String = "Video 1",
    val isMain: Boolean = true,
    val muted: Boolean = false,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val items: List<VideoClipItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.timelineEndUs } ?: 0L
}

enum class AudioTrackType(val label: String) {
    MUSIC("Music"),
    SFX("Sound FX"),
    VOICEOVER("Voiceover"),
}

data class AudioClipItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceUri: String,
    val trackType: AudioTrackType,
    val timelineStartUs: Long,
    val sourceStartUs: Long = 0L,
    val durationUs: Long,
    val volume: Float = 0.8f,
    val fadeInUs: Long = 400_000L,
    val fadeOutUs: Long = 1_400_000L,
    val duckUnderSpeech: Boolean = true,
    val bpm: Double? = null,
    val credit: String? = null,
    val editedByUser: Boolean = false,
) {
    val timelineEndUs: Long get() = timelineStartUs + durationUs.coerceAtLeast(100_000L)
}

data class AudioTrack(
    val id: String = "audio_music",
    val name: String = "Audio 1",
    val type: AudioTrackType = AudioTrackType.MUSIC,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val volume: Float = 1.0f,
    val voiceEnhance: Boolean = false,
    val normalizeTargetDb: Float? = null,
    val items: List<AudioClipItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.timelineEndUs } ?: 0L
}

enum class CaptionStyle(val label: String) {
    SUBTITLE_BAR("Subtitle Bar"),
    BOLD_BOX("Bold Box"),
    MINIMAL("Minimal"),
}

enum class CaptionFont(val label: String) {
    SANS_BOLD("Clean Bold"),
    CONDENSED("Condensed"),
    MONO("Monospace"),
}

enum class CaptionAnimation(val label: String) {
    NONE("Static"),
    POP("Pop In"),
    FADE("Fade"),
    SLIDE_UP("Slide Up"),
}

data class WordTiming(
    val word: String,
    val startOffsetUs: Long,
    val endOffsetUs: Long,
)

data class CaptionItem(
    val id: String = UUID.randomUUID().toString(),
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.SUBTITLE_BAR,
    val speaker: String? = null,
    val editedByUser: Boolean = false,
    val wordTimings: List<WordTiming> = emptyList(),
    val highlightKeywords: List<String> = emptyList(),
    val positionYFraction: Float = 0.84f,
    val font: CaptionFont = CaptionFont.SANS_BOLD,
    val fontSizeSp: Int = 18,
    val strokeWidth: Float = 2.0f,
    val shadowEnabled: Boolean = true,
    val bgAlpha: Float = 0.65f,
    val animation: CaptionAnimation = CaptionAnimation.NONE,
) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
}

data class CaptionTrack(
    val id: String = "captions_main",
    val name: String = "Captions",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val items: List<CaptionItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.endUs } ?: 0L
}

data class TimelineProject(
    val id: String = UUID.randomUUID().toString(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String = "Untitled Edit",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val aspect: Aspect = Aspect.PORTRAIT,
    val customAspectWidth: Int = 1080,
    val customAspectHeight: Int = 1920,
    val fps: Int = 30,
    val resolution: ExportResolution = ExportResolution.P1080,
    val videoBitrateBps: Int = 8_000_000,
    val audioBitrateBps: Int = 192_000,
    val preferredHevc: Boolean = false,
    val colorLook: ColorLook = ColorLook.NATURAL,
    val mood: Mood = Mood.HAPPY,
    val titleOverlay: String? = null,
    val summary: String = "",
    val beatMarkersUs: List<Long> = emptyList(),
    val videoTracks: List<VideoTrack> = listOf(VideoTrack()),
    val audioTracks: List<AudioTrack> = listOf(AudioTrack()),
    val captionTracks: List<CaptionTrack> = listOf(CaptionTrack()),
    val legacyPlanJson: String? = null,
) {
    val mainVideoTrack: VideoTrack get() = videoTracks.firstOrNull { it.isMain } ?: videoTracks.firstOrNull() ?: VideoTrack()
    val overlayVideoTrack: VideoTrack? get() = videoTracks.firstOrNull { !it.isMain }
    val mainCaptionTrack: CaptionTrack get() = captionTracks.firstOrNull() ?: CaptionTrack()
    val mainAudioTrack: AudioTrack get() = audioTracks.firstOrNull() ?: AudioTrack()

    fun resolvedAspectRatio(): Float {
        return when (aspect) {
            Aspect.PORTRAIT -> 9f / 16f
            Aspect.PORTRAIT_4_5 -> 4f / 5f
            Aspect.LANDSCAPE -> 16f / 9f
            Aspect.SQUARE -> 1f
            Aspect.CUSTOM -> (customAspectWidth.coerceAtLeast(16).toFloat() / customAspectHeight.coerceAtLeast(16)).coerceIn(0.35f, 3.0f)
        }
    }

    fun resolvedDimensions(shortEdge: Int): Pair<Int, Int> {
        val even = { v: Int -> ((v + 1) / 2) * 2 }
        return when (aspect) {
            Aspect.PORTRAIT -> even(shortEdge) to even(shortEdge * 16 / 9)
            Aspect.PORTRAIT_4_5 -> even(shortEdge) to even(shortEdge * 5 / 4)
            Aspect.LANDSCAPE -> even(shortEdge * 16 / 9) to even(shortEdge)
            Aspect.SQUARE -> even(shortEdge) to even(shortEdge)
            Aspect.CUSTOM -> {
                val ratio = resolvedAspectRatio()
                if (ratio >= 1f) {
                    even((shortEdge * ratio).roundToInt()) to even(shortEdge)
                } else {
                    even(shortEdge) to even((shortEdge / ratio).roundToInt())
                }
            }
        }
    }

    val totalDurationUs: Long
        get() = max(
            videoTracks.maxOfOrNull { it.endUs } ?: 0L,
            max(audioTracks.maxOfOrNull { it.endUs } ?: 0L, captionTracks.maxOfOrNull { it.endUs } ?: 0L),
        )

    val videoDurationUs: Long get() = mainVideoTrack.endUs

    val hasUserManualEdits: Boolean
        get() = videoTracks.any { t -> t.items.any { it.editedByUser } } ||
            audioTracks.any { t -> t.items.any { it.editedByUser } } ||
            captionTracks.any { t -> t.items.any { it.editedByUser } }

    fun activeCaptionsAt(timeUs: Long): List<CaptionItem> =
        captionTracks.filter { it.visible }
            .flatMap { it.items }
            .filter { timeUs in it.startUs..it.endUs }

    fun activeOverlaysAt(timeUs: Long): List<VideoClipItem> =
        videoTracks.filter { !it.isMain && it.visible }
            .flatMap { it.items }
            .filter { timeUs in it.timelineStartUs..it.timelineEndUs }

    /**
     * Converts the main video track + project settings back into a Media3-compatible [EditPlan].
     */
    fun toEditPlan(): EditPlan {
        val segs = mainVideoTrack.items.sortedBy { it.timelineStartUs }.map { item ->
            Segment(
                clip = item.sourceClipIndex,
                start = usToSec(item.sourceStartUs),
                end = usToSec(item.sourceEndUs),
                speed = item.effectiveSpeed,
                why = item.why,
                sound = !item.muted && !mainVideoTrack.muted,
                focusX = item.focusX,
                soundGain = if (item.muted || mainVideoTrack.muted) 0.0 else item.volume.toDouble(),
            )
        }
        val firstTransition = mainVideoTrack.items.drop(1).firstOrNull()?.transitionIn ?: Transition.AUTO
        val musicItem = audioTracks.firstOrNull { !it.muted }?.items?.firstOrNull { it.trackType == AudioTrackType.MUSIC }
        return EditPlan(
            summary = summary.ifBlank { "Timeline edit (${segs.size} clips)" },
            aspect = aspect,
            title = titleOverlay,
            segments = segs,
            mood = mood,
            color = colorLook,
            originalVolume = 1.0,
            musicVolume = musicItem?.volume?.toDouble() ?: 0.8,
            transition = firstTransition,
            musicOn = musicItem != null,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3

        fun empty(name: String = "Untitled Edit"): TimelineProject = TimelineProject(name = name)

        fun fromEditPlan(
            plan: EditPlan,
            clips: List<ClipMeta>,
            clipUris: Map<Int, String> = emptyMap(),
            projectId: String = UUID.randomUUID().toString(),
            projectName: String = "PromptCut Edit",
            preserveLegacyJson: String? = null,
        ): TimelineProject {
            val byIdx = clips.associateBy { it.index }
            var cursorUs = 0L
            val items = plan.segments.mapIndexed { idx, seg ->
                val meta = byIdx[seg.clip]
                val item = VideoClipItem(
                    sourceClipIndex = seg.clip,
                    sourceUri = clipUris[seg.clip] ?: "",
                    timelineStartUs = cursorUs,
                    sourceStartUs = secToUs(seg.start),
                    sourceEndUs = secToUs(seg.end),
                    speed = seg.speed,
                    volume = (seg.soundGain * plan.originalVolume).toFloat().coerceIn(0f, 1.5f),
                    muted = seg.sound == false || seg.soundGain <= 0.01,
                    crop = meta?.crop,
                    focusX = seg.focusX,
                    transitionIn = if (idx == 0) Transition.CUT else plan.effectiveTransition,
                    why = seg.why,
                    editedByUser = false,
                )
                cursorUs += item.timelineDurationUs
                item
            }
            return TimelineProject(
                id = projectId,
                name = projectName,
                aspect = plan.aspect,
                colorLook = plan.color,
                mood = plan.mood,
                titleOverlay = plan.title,
                summary = plan.summary,
                videoTracks = listOf(VideoTrack(items = items)),
                audioTracks = listOf(AudioTrack()),
                captionTracks = listOf(CaptionTrack()),
                legacyPlanJson = preserveLegacyJson ?: plan.toJson(),
            )
        }
    }
}
