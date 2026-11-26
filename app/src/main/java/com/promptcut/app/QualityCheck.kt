package com.promptcut.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.promptcut.app.intelligence.CaptionTools
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

enum class QaIssue {
    ROTATION,
    DURATION,
    FPS,
    NO_SOUND,
    MUSIC_MISSING,
    COLOUR,
    BLACK,
    DULL_OPENING,
    DULL_SHOTS,
    ABRUPT_ENDING,
    CAPTION_OVERLAP,
    CAPTION_READABILITY,
    REPEATED_SCENES,
    AUDIO_CLIPPING,
    AUDIO_SYNC,
    FROZEN_FRAMES,
    EXPORT_CORRUPTION,
}

data class SuggestedRepair(
    val id: String,
    val issue: QaIssue,
    val label: String,
    val description: String,
)

/** One line of the checklist shown to the user. */
data class QaItem(val label: String, val ok: Boolean, val detail: String = "", val fixed: Boolean = false)

data class QaReport(
    val items: List<QaItem>,
    val issues: Map<QaIssue, String>,
    val colourSegments: List<Int> = emptyList(),
    val dullSegments: List<Int> = emptyList(),
    val rotationFix: Int = 0,
    val suggestedRepairs: List<SuggestedRepair> = emptyList(),
) {
    val ok get() = issues.isEmpty()

    /** Problems that need the AI to change the story (not just a technical fix). */
    val storyIssues get() = issues.keys.filter {
        it in setOf(QaIssue.DULL_OPENING, QaIssue.DULL_SHOTS, QaIssue.BLACK, QaIssue.DURATION, QaIssue.ABRUPT_ENDING)
    }

    fun notesForAi(plan: EditPlan): String = buildString {
        appendLine("A quality check of the rendered video found these problems. Fix them:")
        issues.forEach { (k, v) -> appendLine("- $k: $v") }
        if (dullSegments.isNotEmpty()) appendLine(
            "Dull segments (replace them with more interesting moments or remove them): " +
                dullSegments.joinToString { i ->
                    plan.segments.getOrNull(i)?.let { "#${i + 1} clip ${it.clip} ${round1(it.start)}-${round1(it.end)} s" } ?: ""
                }
        )
        appendLine("Keep everything else about the edit the same.")
    }
}

/**
 * Inspects rendered output & timeline structure for container integrity, rotation, duration, FPS,
 * frozen frames, audio sync/clipping, color fidelity, black frames, caption overlap & readability,
 * repeated scenes, weak openings, and abrupt endings.
 */
object QualityCheck {

    suspend fun check(
        context: Context, video: File, plan: EditPlan, clips: List<Clip>, insights: Map<Int, ClipInsight>,
        expectMusic: Boolean, expectSound: Boolean,
        targetSec: Double? = null, usableSec: Double = Double.MAX_VALUE,
    ): QaReport = checkInternal(
        context = context,
        video = video,
        plan = plan,
        project = null,
        clips = clips,
        insights = insights,
        expectMusic = expectMusic,
        expectSound = expectSound,
        targetSec = targetSec,
        usableSec = usableSec,
    )

    suspend fun checkTimeline(
        context: Context,
        video: File,
        project: TimelineProject,
        clips: List<Clip>,
        insights: Map<Int, ClipInsight>,
        expectMusic: Boolean,
        expectSound: Boolean,
        targetSec: Double? = null,
        usableSec: Double = Double.MAX_VALUE,
    ): QaReport = checkInternal(
        context = context,
        video = video,
        plan = project.toEditPlan(),
        project = project,
        clips = clips,
        insights = insights,
        expectMusic = expectMusic,
        expectSound = expectSound,
        targetSec = targetSec,
        usableSec = usableSec,
    )

    private suspend fun checkInternal(
        context: Context,
        video: File,
        plan: EditPlan,
        project: TimelineProject?,
        clips: List<Clip>,
        insights: Map<Int, ClipInsight>,
        expectMusic: Boolean,
        expectSound: Boolean,
        targetSec: Double?,
        usableSec: Double,
    ): QaReport = withContext(Dispatchers.Default) {
        val items = mutableListOf<QaItem>()
        val issues = linkedMapOf<QaIssue, String>()
        val repairs = mutableListOf<SuggestedRepair>()
        var rotationFix = 0
        var outDurationSec = 0.0

        val verified = Renderer.verifyExportedMp4(video, expectAudio = expectMusic || expectSound)
        if (!verified.valid) {
            issues[QaIssue.EXPORT_CORRUPTION] = verified.errorReason ?: "MP4 container verification failed"
            items += QaItem("MP4 container integrity", false, verified.errorReason ?: "corrupt output")
            repairs += SuggestedRepair("safe_rerender", QaIssue.EXPORT_CORRUPTION, "Re-render in Safe Mode", "Export shot-by-shot to avoid encoder instability.")
            return@withContext QaReport(items, issues, suggestedRepairs = repairs)
        } else {
            items += QaItem("MP4 verified (${verified.width}x${verified.height})", true)
        }

        // ---- format: orientation, length, frame rate
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            val wantPortrait = if (project != null) project.resolvedAspectRatio() < 0.95f else plan.aspect.height > plan.aspect.width
            val wantLandscape = if (project != null) project.resolvedAspectRatio() > 1.05f else plan.aspect.width > plan.aspect.height
            val upright = when {
                wantPortrait -> dh > dw
                wantLandscape -> dw > dh
                else -> true
            }
            if (upright) items += QaItem("Upright, ${plan.aspect.key}", true)
            else {
                issues[QaIssue.ROTATION] = "picture is sideways (${dw}x$dh shown, ${plan.aspect.key} wanted)"
                items += QaItem("Upright", false, "picture was sideways")
                rotationFix = (rot + 90) % 360
                repairs += SuggestedRepair("fix_rotation", QaIssue.ROTATION, "Rotate upright", "Losslessly rotate container orientation.")
            }

            outDurationSec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
            val tol = maxOf(0.7, plan.totalSec * 0.06)
            val want = targetSec?.takeIf { it <= usableSec - 0.5 }
            val wantTol = want?.let { maxOf(1.5, it * 0.1) }
            when {
                want != null && abs(outDurationSec - want) > wantTol!! -> {
                    issues[QaIssue.DURATION] = "video is ${round1(outDurationSec)} s but ${round1(want)} s was asked for"
                    items += QaItem("Length you asked for", false, "${round1(outDurationSec)} s instead of ${round1(want)} s")
                }
                abs(outDurationSec - plan.totalSec) > tol -> {
                    issues[QaIssue.DURATION] = "video is ${round1(outDurationSec)} s but the edit is ${round1(plan.totalSec)} s"
                    items += QaItem("Length", false, "${round1(outDurationSec)} s instead of ${round1(plan.totalSec)} s")
                }
                want != null -> items += QaItem("Length ${round1(outDurationSec)} s (asked ${round1(want)} s)", true)
                else -> items += QaItem("Length ${round1(outDurationSec)} s", true)
            }
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            if (frames != null && outDurationSec > 0) {
                val fps = frames / outDurationSec
                val maxAllowedFps = (project?.fps ?: 30) + 4
                if (fps <= maxAllowedFps) items += QaItem("Smooth ${fps.toInt()} fps", true)
                else {
                    issues[QaIssue.FPS] = "${fps.toInt()} fps"
                    items += QaItem("Frame rate", false, "${fps.toInt()} fps (above target ${project?.fps ?: 30} fps)")
                }
            }
        }

        // ---- sound, clipping, sync & music
        val env = AudioTools.envelope(context, Uri.fromFile(video), 0.05, plan.totalSec + 2)
        if (env == null) {
            if (expectSound || expectMusic) {
                issues[QaIssue.NO_SOUND] = "video has no sound"
                items += QaItem("Sound", false, "no sound track")
            }
        } else {
            val envDurSec = env.size * 0.05
            if (outDurationSec > 2.0 && abs(envDurSec - outDurationSec) > 1.2) {
                issues[QaIssue.AUDIO_SYNC] = "audio track (${round1(envDurSec)}s) differs from video (${round1(outDurationSec)}s)"
                items += QaItem("Audio sync", false, "${round1(envDurSec)}s audio vs ${round1(outDurationSec)}s video")
            } else {
                items += QaItem("Audio sync", true)
            }
            if (QaMetrics.hasAudioClipping(env)) {
                issues[QaIssue.AUDIO_CLIPPING] = "audio peaks hit 0dB clipping threshold"
                items += QaItem("Clean audio levels", false, "clipping detected")
                repairs += SuggestedRepair("lower_audio_gain", QaIssue.AUDIO_CLIPPING, "Reduce gain", "Lower music and clip volume slightly to remove clipping.")
            } else {
                items += QaItem("Clean audio levels", true)
            }
            if (expectMusic) {
                val win = 10
                val windows = env.toList().chunked(win).filter { it.size == win }
                val silent = windows.count { w -> w.average() < 0.006 }
                val share = if (windows.isEmpty()) 1.0 else silent.toDouble() / windows.size
                if (share > 0.2) {
                    issues[QaIssue.MUSIC_MISSING] = "${(share * 100).toInt()}% of the video is silent"
                    items += QaItem("Music", false, "too quiet or missing in places")
                } else items += QaItem("Music plays throughout", true)
            }
        }

        // ---- picture & frozen-frame check
        val colourSegs = mutableListOf<Int>()
        val problems = mutableListOf<String>()
        var black = false
        var frozenPairs = 0
        val byIndex = clips.associateBy { it.meta.index }
        val targetAspect = if (project != null) project.resolvedAspectRatio().toDouble() else plan.aspect.width.toDouble() / plan.aspect.height
        MediaMetadataRetriever().use { outR ->
            outR.setDataSource(video.absolutePath)
            var t = 0.0
            val sampleEvery = maxOf(1, plan.segments.size / 12)
            plan.segments.forEachIndexed { i, seg ->
                val mid = t + seg.outputSec / 2
                t += seg.outputSec
                if (i % sampleEvery != 0) return@forEachIndexed
                val clip = byIndex[seg.clip] ?: return@forEachIndexed
                val outBmp = frame(outR, mid) ?: return@forEachIndexed
                if (seg.outputSec >= 1.2) {
                    val outBmp2 = frame(outR, mid + 0.5)
                    if (outBmp2 != null) {
                        val diff = frameMeanAbsDiff(outBmp, outBmp2)
                        val expectedMotion = (insights[seg.clip]?.avgMotion ?: 2.0) > 2.5
                        if (diff < 0.25 && expectedMotion) frozenPairs++
                        outBmp2.recycle()
                    }
                }
                val srcBmp = MediaMetadataRetriever().use { sr ->
                    runCatching { sr.setDataSource(context, clip.uri); frame(sr, seg.start + seg.outputSec / 2 * seg.speed) }.getOrNull()
                } ?: run { outBmp.recycle(); return@forEachIndexed }
                val so = statsOf(outBmp, targetAspect)
                val srcArea = clip.meta.crop?.let { c ->
                    val x = (c.x0 * srcBmp.width).toInt().coerceIn(0, (srcBmp.width - 8).coerceAtLeast(0))
                    val y = (c.y0 * srcBmp.height).toInt().coerceIn(0, (srcBmp.height - 8).coerceAtLeast(0))
                    Bitmap.createBitmap(
                        srcBmp, x, y,
                        (c.width * srcBmp.width).toInt().coerceIn(8, (srcBmp.width - x).coerceAtLeast(1)),
                        (c.height * srcBmp.height).toInt().coerceIn(8, (srcBmp.height - y).coerceAtLeast(1))
                    )
                } ?: srcBmp
                val ss = statsOf(srcArea, if (clip.meta.crop != null) srcArea.width.toDouble() / srcArea.height else targetAspect)
                if (srcArea !== srcBmp) srcArea.recycle()
                outBmp.recycle(); srcBmp.recycle()
                QaMetrics.colourProblem(ss, so, plan.color)?.let { p ->
                    if (p == "black picture") black = true else { colourSegs += i; problems += p }
                }
            }
        }
        if (frozenPairs >= 2) {
            issues[QaIssue.FROZEN_FRAMES] = "detected stalled frames across $frozenPairs shot(s)"
            items += QaItem("Motion continuity", false, "$frozenPairs stalled segment(s)")
            repairs += SuggestedRepair("safe_rerender", QaIssue.FROZEN_FRAMES, "Re-render Safe Mode", "Re-encode shots individually to prevent frame stalls.")
        } else if (plan.segments.isNotEmpty()) {
            items += QaItem("Motion continuity", true)
        }
        if (colourSegs.isNotEmpty()) {
            issues[QaIssue.COLOUR] = problems.distinct().joinToString()
            items += QaItem("Natural colours", false, problems.distinct().joinToString() + " in ${colourSegs.size} shot(s)")
            repairs += SuggestedRepair("natural_color", QaIssue.COLOUR, "Switch to Natural look", "Reset color look to Natural to preserve original color accuracy.")
        } else items += QaItem("Natural colours", true)
        if (black) {
            issues[QaIssue.BLACK] = "a shot is black in the middle"
            items += QaItem("No black shots", false)
        }

        // ---- story & structure
        val dull = plan.segments.mapIndexedNotNull { i, seg ->
            val mid = seg.start + (seg.end - seg.start) / 2
            val shot = insights[seg.clip]?.shots?.firstOrNull { mid >= it.start && mid <= it.end }
            if (shot?.interest == 0) i else null
        }
        val anyGoodShot = insights.values.any { ins -> ins.shots.any { it.interest > 0 } }
        if (anyGoodShot && dull.contains(0)) {
            issues[QaIssue.DULL_OPENING] = "the video opens on a dull shot"
            repairs += SuggestedRepair("fix_opening", QaIssue.DULL_OPENING, "Lead with best moment", "Move your strongest highlight to open the video.")
        }
        if (anyGoodShot && dull.size > maxOf(1, plan.segments.size / 4)) {
            issues[QaIssue.DULL_SHOTS] = "${dull.size} of ${plan.segments.size} shots are dull"
        }
        items += if (dull.isEmpty() || !anyGoodShot) QaItem("Interesting shots", true)
        else QaItem("Interesting shots", false, if (dull.contains(0)) "opens on a dull shot" else "${dull.size} dull shot(s)")

        val repeatedCount = detectRepeatedAdjacentSegments(plan.segments)
        if (repeatedCount > 0) {
            issues[QaIssue.REPEATED_SCENES] = "$repeatedCount near-duplicate adjacent segment(s)"
            items += QaItem("Scene variety", false, "$repeatedCount repeated shot(s)")
            repairs += SuggestedRepair("dedupe_scenes", QaIssue.REPEATED_SCENES, "Remove repeats", "Drop duplicate back-to-back segments.")
        } else if (plan.segments.size >= 2) {
            items += QaItem("Scene variety", true)
        }

        val lastSeg = plan.segments.lastOrNull()
        if (lastSeg != null && (lastSeg.outputSec < 0.7 || (anyGoodShot && dull.contains(plan.segments.lastIndex)))) {
            issues[QaIssue.ABRUPT_ENDING] = "ending shot is abrupt or low-interest"
            items += QaItem("Strong ending", false, "ending feels abrupt")
            repairs += SuggestedRepair("polish_ending", QaIssue.ABRUPT_ENDING, "Smooth ending", "Add dip-to-black ending and extend final shot slightly.")
        } else if (lastSeg != null) {
            items += QaItem("Strong ending", true)
        }

        // ---- caption timing & readability
        if (project != null && project.mainCaptionTrack.items.isNotEmpty()) {
            val overlaps = detectCaptionOverlaps(project)
            if (overlaps > 0) {
                issues[QaIssue.CAPTION_OVERLAP] = "$overlaps overlapping caption(s)"
                items += QaItem("Caption timing", false, "$overlaps overlap(s)")
                repairs += SuggestedRepair("fix_captions", QaIssue.CAPTION_OVERLAP, "Fix caption timing", "Automatically stagger overlapping subtitles.")
            } else {
                items += QaItem("Caption timing", true)
            }

            val readabilityIssues = detectCaptionReadabilityIssues(project)
            if (readabilityIssues > 0) {
                issues[QaIssue.CAPTION_READABILITY] = "$readabilityIssues caption(s) too small, clipped outside safe zone, or missing contrast"
                items += QaItem("Caption readability", false, "$readabilityIssues caption(s) need contrast/safe-zone fix")
                repairs += SuggestedRepair("fix_caption_readability", QaIssue.CAPTION_READABILITY, "Fix caption readability", "Clamp captions into safe area, boost size >=16sp, and add stroke/backing.")
            } else {
                items += QaItem("Caption readability", true)
            }
        }

        QaReport(items, issues, colourSegs, dull, rotationFix, repairs)
    }

    fun detectRepeatedAdjacentSegments(segments: List<Segment>): Int {
        if (segments.size < 2) return 0
        var count = 0
        for (i in 0 until segments.lastIndex) {
            val a = segments[i]
            val b = segments[i + 1]
            if (a.clip == b.clip && abs(a.start - b.start) < 0.6 && abs(a.end - b.end) < 0.6) {
                count++
            }
        }
        return count
    }

    fun detectCaptionOverlaps(project: TimelineProject): Int {
        val sorted = project.mainCaptionTrack.items.sortedBy { it.startUs }
        var overlaps = 0
        for (i in 0 until sorted.lastIndex) {
            if (sorted[i].endUs > sorted[i + 1].startUs) overlaps++
        }
        return overlaps
    }

    fun detectCaptionReadabilityIssues(project: TimelineProject): Int {
        return project.mainCaptionTrack.items.count { c ->
            val outOfSafeArea = c.positionYFraction < 0.12f || c.positionYFraction > 0.88f
            val tooSmall = c.fontSizeSp < 14
            val lowContrastMinimal = c.style == CaptionStyle.MINIMAL && c.strokeWidth < 1.0f && !c.shadowEnabled
            val tooLong = c.text.length > 84
            outOfSafeArea || tooSmall || lowContrastMinimal || tooLong
        }
    }

    fun repairCaptionReadability(project: TimelineProject): List<com.promptcut.app.timeline.CaptionItem> {
        return project.mainCaptionTrack.items.map { c ->
            val fixed = c.copy(
                positionYFraction = CaptionTools.clampSafeAreaY(c.positionYFraction),
                fontSizeSp = c.fontSizeSp.coerceAtLeast(16),
                strokeWidth = if (c.style == CaptionStyle.MINIMAL) c.strokeWidth.coerceAtLeast(2.0f) else c.strokeWidth,
                shadowEnabled = true,
                bgAlpha = if (c.style != CaptionStyle.MINIMAL) c.bgAlpha.coerceAtLeast(0.55f) else c.bgAlpha,
            )
            CaptionTools.enrichCaption(fixed, applyPunctuation = false, highlightKeywords = fixed.highlightKeywords.isNotEmpty())
        }
    }

    private fun frameMeanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val sa = Bitmap.createScaledBitmap(a, 48, 48, true)
        val sb = Bitmap.createScaledBitmap(b, 48, 48, true)
        val pa = IntArray(48 * 48)
        val pb = IntArray(48 * 48)
        sa.getPixels(pa, 0, 48, 0, 0, 48, 48)
        sb.getPixels(pb, 0, 48, 0, 0, 48, 48)
        sa.recycle(); sb.recycle()
        var sum = 0.0
        for (i in pa.indices) {
            val ya = 0.299 * Color.red(pa[i]) + 0.587 * Color.green(pa[i]) + 0.114 * Color.blue(pa[i])
            val yb = 0.299 * Color.red(pb[i]) + 0.587 * Color.green(pb[i]) + 0.114 * Color.blue(pb[i])
            sum += abs(ya - yb)
        }
        return sum / pa.size
    }

    private fun frame(r: MediaMetadataRetriever, tSec: Double): Bitmap? = runCatching {
        r.getScaledFrameAtTime((tSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
    }.getOrNull()

    private fun statsOf(b: Bitmap, aspect: Double): ColorStats {
        var cw = b.width; var ch = b.height
        if (cw.toDouble() / ch > aspect) cw = (ch * aspect).toInt() else ch = (cw / aspect).toInt()
        val crop = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(crop, 96, (96 * ch / cw).coerceAtLeast(8), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val s = QaMetrics.stats(px, small.width, small.height)
        if (crop !== b) crop.recycle(); small.recycle()
        return s
    }

    fun fixRotation(video: File, degrees: Int, out: File) {
        val ex = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(video.absolutePath)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) { ex.selectTrack(i); map[i] = muxer.addTrack(ex.getTrackFormat(i)) }
            muxer.setOrientationHint(degrees)
            muxer.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map[ex.sampleTrackIndex]!!, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }
}
