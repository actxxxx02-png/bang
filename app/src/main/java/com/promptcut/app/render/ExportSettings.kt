package com.promptcut.app.render

import com.promptcut.app.Aspect
import com.promptcut.app.ExportResolution
import org.json.JSONObject
import kotlin.math.roundToInt

enum class ExportPreset(val label: String, val resolution: ExportResolution, val fps: Int, val bitrateMbps: Int) {
    FAST_720P("720p Fast (Preview / Share)", ExportResolution.P720, 30, 5),
    STANDARD_1080P("1080p Standard HD", ExportResolution.P1080, 30, 8),
    SMOOTH_60FPS("1080p Smooth 60fps", ExportResolution.P1080, 60, 14),
    QHD_1440P("1440p QHD", ExportResolution.P1440, 30, 18),
    UHD_4K("4K UHD", ExportResolution.P2160, 30, 30),
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val fps: Int = 30,
    val videoBitrateBps: Int = 8_000_000,
    val audioBitrateBps: Int = 192_000,
    val useHevc: Boolean = false,
    val forceSafeMode: Boolean = false,
    val burnCaptions: Boolean = true,
    val previewOnly: Boolean = false,
    val customAspectWidth: Int = 1080,
    val customAspectHeight: Int = 1920,
) {
    fun outputDimensions(aspect: Aspect): Pair<Int, Int> {
        val short = if (previewOnly) ExportResolution.P720.shortEdge else resolution.shortEdge
        val even = { v: Int -> ((v + 1) / 2) * 2 }
        return when (aspect) {
            Aspect.PORTRAIT -> even(short) to even(short * 16 / 9)
            Aspect.PORTRAIT_4_5 -> even(short) to even(short * 5 / 4)
            Aspect.LANDSCAPE -> even(short * 16 / 9) to even(short)
            Aspect.SQUARE -> even(short) to even(short)
            Aspect.CUSTOM -> {
                val ratio = (customAspectWidth.coerceAtLeast(16).toFloat() / customAspectHeight.coerceAtLeast(16)).coerceIn(0.35f, 3.0f)
                if (ratio >= 1f) even((short * ratio).roundToInt()) to even(short)
                else even(short) to even((short / ratio).roundToInt())
            }
        }
    }

    fun applyPreset(preset: ExportPreset): ExportSettings = copy(
        resolution = preset.resolution,
        fps = preset.fps,
        videoBitrateBps = preset.bitrateMbps * 1_000_000,
    )
}

data class ExportVerificationResult(
    val valid: Boolean,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val errorReason: String? = null,
)

data class ExportDiagnostics(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val renderTimeMs: Long,
    val usedSafeMode: Boolean,
    val verifiedOk: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("codec", codec)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("videoBitrateBps", videoBitrateBps)
        put("audioBitrateBps", audioBitrateBps)
        put("durationMs", durationMs)
        put("fileSizeBytes", fileSizeBytes)
        put("hasVideoTrack", hasVideoTrack)
        put("hasAudioTrack", hasAudioTrack)
        put("renderTimeMs", renderTimeMs)
        put("usedSafeMode", usedSafeMode)
        put("verifiedOk", verifiedOk)
        put("timestampMs", timestampMs)
    }.toString()

    companion object {
        fun fromJson(raw: String?): ExportDiagnostics? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                ExportDiagnostics(
                    codec = o.optString("codec", "H.264"),
                    width = o.optInt("width", 1080),
                    height = o.optInt("height", 1920),
                    fps = o.optInt("fps", 30),
                    videoBitrateBps = o.optInt("videoBitrateBps", 8_000_000),
                    audioBitrateBps = o.optInt("audioBitrateBps", 192_000),
                    durationMs = o.optLong("durationMs", 0L),
                    fileSizeBytes = o.optLong("fileSizeBytes", 0L),
                    hasVideoTrack = o.optBoolean("hasVideoTrack", true),
                    hasAudioTrack = o.optBoolean("hasAudioTrack", false),
                    renderTimeMs = o.optLong("renderTimeMs", 0L),
                    usedSafeMode = o.optBoolean("usedSafeMode", false),
                    verifiedOk = o.optBoolean("verifiedOk", true),
                    timestampMs = o.optLong("timestampMs", 0L),
                )
            }.getOrNull()
        }
    }
}
