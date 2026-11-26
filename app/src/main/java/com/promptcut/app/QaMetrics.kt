package com.promptcut.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Colour statistics of a frame (pure maths on ARGB ints, so it's unit-tested). */
data class ColorStats(
    val luma: Double,          // 0..255
    val greyFraction: Double,  // share of nearly colourless pixels
    val meanSat: Double,       // 0..1
    val meanHue: Double,       // degrees, circular mean over coloured pixels (NaN if none)
    val speckle: Double,       // how much neighbouring pixels jump between different hues (rainbow noise)
)

object QaMetrics {

    fun stats(px: IntArray, w: Int, h: Int): ColorStats {
        var lumaSum = 0.0; var grey = 0; var satSum = 0.0
        var hx = 0.0; var hy = 0.0; var coloured = 0
        val hue = DoubleArray(px.size) { Double.NaN }
        px.forEachIndexed { i, c ->
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            satSum += sat
            if (sat < 0.12 || mx < 30) grey++ else {
                // hue via the chroma plane
                val a = r - 0.5 * (g + b); val bb = (sqrt(3.0) / 2) * (g - b)
                val hdeg = Math.toDegrees(atan2(bb, a))
                hue[i] = hdeg
                hx += kotlin.math.cos(Math.toRadians(hdeg)); hy += kotlin.math.sin(Math.toRadians(hdeg)); coloured++
            }
        }
        // Speckle: neighbours that are both coloured but ~opposite hues.
        var jumps = 0; var pairs = 0
        for (y in 0 until h) for (x in 0 until w - 1) {
            val a = hue[y * w + x]; val b = hue[y * w + x + 1]
            if (!a.isNaN() && !b.isNaN()) { pairs++; if (hueDist(a, b) > 90) jumps++ }
        }
        return ColorStats(
            luma = lumaSum / px.size,
            greyFraction = grey.toDouble() / px.size,
            meanSat = satSum / px.size,
            meanHue = if (coloured == 0) Double.NaN else Math.toDegrees(atan2(hy, hx)),
            speckle = if (pairs == 0) 0.0 else jumps.toDouble() / pairs,
        )
    }

    fun hueDist(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }

    /**
     * Does the output frame look broken compared with the source frame?
     * Looks (warm, vivid, B&W…) are allowed to change colour a bit; this only flags big, unnatural changes.
     */
    fun colourProblem(src: ColorStats, out: ColorStats, look: ColorLook): String? {
        if (look == ColorLook.BW) return null
        if (src.luma > 40 && out.luma < 12) return "black picture"
        if (out.speckle > 0.18 && out.speckle > src.speckle * 2 + 0.05) return "rainbow speckle"
        if (src.greyFraction > 0.25 && out.greyFraction < src.greyFraction * 0.5) return "grey areas turned colourful"
        if (!src.meanHue.isNaN() && !out.meanHue.isNaN() && out.meanSat > 0.15 && src.meanSat > 0.15 &&
            hueDist(src.meanHue, out.meanHue) > 45) return "colours shifted"
        if (out.meanSat > src.meanSat * 1.5 + 0.05) return "oversaturated"
        return null
    }

    data class AudioStats(
        val peak: Float,
        val rms: Float,
        val clippedFraction: Double,
    )

    fun analyzeAudioEnvelope(env: FloatArray, clipThreshold: Float = 0.98f): AudioStats {
        if (env.isEmpty()) return AudioStats(0f, 0f, 0.0)
        var peak = 0f
        var sumSq = 0.0
        var clipped = 0
        for (v in env) {
            val av = abs(v)
            if (av > peak) peak = av
            sumSq += av * av
            if (av >= clipThreshold) clipped++
        }
        return AudioStats(
            peak = peak,
            rms = sqrt(sumSq / env.size).toFloat(),
            clippedFraction = clipped.toDouble() / env.size,
        )
    }

    fun hasAudioClipping(env: FloatArray): Boolean {
        val st = analyzeAudioEnvelope(env)
        return st.peak >= 0.985f && st.clippedFraction >= 0.04
    }
}
