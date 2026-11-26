package com.promptcut.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Pure audio mixing (no Android APIs, so it's unit-tested): puts background music under the clips' own sound.
 * - music fades in/out and loops if the song is shorter than the video
 * - music "ducks" (gets quieter) automatically whenever the clips have speech/cheering
 * - a soft limiter prevents clipping
 * Works in chunks ([MixerStream]) so hour-long videos don't need the whole soundtrack in memory.
 * Buffers are interleaved stereo at the same sample rate.
 */
class MixerStream(
    private val totalFrames: Int,
    sampleRate: Int,
    private val musicGain: Float,
    private val clipGain: Float = 1f,
    private val duckTo: Float = 0.35f,
    fadeInSec: Double = 0.4,
    fadeOutSec: Double = 1.8,
) {
    private val attack = coef(0.015, sampleRate)
    private val release = coef(0.45, sampleRate)
    private val duckAttack = coef(0.08, sampleRate)
    private val duckRelease = coef(0.6, sampleRate)
    private val threshold = 0.06f
    private val fadeIn = (fadeInSec * sampleRate).toInt().coerceAtLeast(1)
    private val fadeOut = (fadeOutSec * sampleRate).toInt().coerceAtLeast(1)
    private var env = 0f
    private var duck = 1f
    private var frame = 0

    /** Mixes [frames] frames. [clip]/[music] may be shorter (treated as silence). Writes 16-bit into [out]. */
    fun process(clip: FloatArray?, clipFrames: Int, music: FloatArray?, musicFrames: Int, frames: Int, out: ShortArray) {
        for (f in 0 until frames) {
            var cl = 0f; var cr = 0f
            if (clip != null && f < clipFrames) { cl = clip[2 * f] * clipGain; cr = clip[2 * f + 1] * clipGain }
            val level = max(abs(cl), abs(cr))
            env = if (level > env) env + (level - env) * attack else env + (level - env) * release
            val target = if (env > threshold) duckTo else 1f
            duck = if (target < duck) duck + (target - duck) * duckAttack else duck + (target - duck) * duckRelease

            var ml = 0f; var mr = 0f
            if (music != null && f < musicFrames) {
                val fade = min(1f, min(frame.toFloat() / fadeIn, (totalFrames - frame).toFloat() / fadeOut)).coerceAtLeast(0f)
                val g = musicGain * duck * fade
                ml = music[2 * f] * g; mr = music[2 * f + 1] * g
            }
            out[2 * f] = toShort(limit(cl + ml))
            out[2 * f + 1] = toShort(limit(cr + mr))
            frame++
        }
    }

    private fun coef(timeSec: Double, sr: Int) = (1.0 - kotlin.math.exp(-1.0 / (timeSec * sr))).toFloat()
    private fun limit(x: Float): Float = if (abs(x) <= 0.8f) x else (if (x > 0) 1 else -1) * (0.8f + 0.2f * tanh((abs(x) - 0.8f) / 0.2f))
    private fun toShort(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

/** Streaming linear-interpolation resampler + channel mapping to interleaved stereo. */
class Resampler(private val channels: Int, srcRate: Int, dstRate: Int) {
    private val ratio = srcRate.toDouble() / dstRate
    private var pos = 0.0                      // position in source frames, relative to the carried frame
    private var carryL = 0f
    private var carryR = 0f
    private var hasCarry = false

    /** Feeds interleaved source samples; appends stereo output to [sink]. */
    fun feed(src: FloatArray, srcSamples: Int, sink: (Float, Float) -> Unit) {
        val frames = srcSamples / channels
        if (frames == 0) return
        fun l(i: Int) = if (i < 0) carryL else src[i * channels]
        fun r(i: Int) = if (i < 0) carryR else src[i * channels + if (channels == 1) 0 else 1]
        // Index -1 is the last frame of the previous buffer.
        val base = if (hasCarry) -1 else 0
        var p = pos + base
        while (p <= frames - 1 - 1e-9) {
            val i0 = kotlin.math.floor(p).toInt()
            val i1 = i0 + 1
            val t = (p - i0).toFloat()
            if (i1 > frames - 1) break
            sink(l(i0) + (l(i1) - l(i0)) * t, r(i0) + (r(i1) - r(i0)) * t)
            p += ratio
        }
        // Carry the last frame; keep the fractional position relative to it.
        pos = p - (frames - 1)
        carryL = l(frames - 1); carryR = r(frames - 1); hasCarry = true
    }
}

object Mixer {
    /** Whole-buffer convenience (used by tests and short clips). */
    fun mix(clip: FloatArray?, music: FloatArray, frames: Int, sampleRate: Int, musicGain: Float, clipGain: Float = 1f): ShortArray {
        val out = ShortArray(frames * 2)
        val stream = MixerStream(frames, sampleRate, musicGain, clipGain)
        val chunk = 4096
        val musicFrames = music.size / 2
        val clipFrames = (clip?.size ?: 0) / 2
        val mBuf = FloatArray(chunk * 2)
        val cBuf = FloatArray(chunk * 2)
        val oBuf = ShortArray(chunk * 2)
        var f = 0
        while (f < frames) {
            val n = min(chunk, frames - f)
            for (k in 0 until n) {
                if (musicFrames > 0) { val m = (f + k) % musicFrames; mBuf[2 * k] = music[2 * m]; mBuf[2 * k + 1] = music[2 * m + 1] }
                if (clip != null && f + k < clipFrames) { cBuf[2 * k] = clip[2 * (f + k)]; cBuf[2 * k + 1] = clip[2 * (f + k) + 1] }
                else { cBuf[2 * k] = 0f; cBuf[2 * k + 1] = 0f }
            }
            stream.process(if (clip != null) cBuf else null, n, if (musicFrames > 0) mBuf else null, if (musicFrames > 0) n else 0, n, oBuf)
            System.arraycopy(oBuf, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    fun toStereo(src: FloatArray, channels: Int, srcRate: Int, dstRate: Int): FloatArray {
        val out = ArrayList<Float>(src.size * 2)
        Resampler(channels, srcRate, dstRate).feed(src, src.size) { l, r -> out += l; out += r }
        // Pad the tail so length matches the expected frame count.
        val expected = (src.size / channels).toLong() * dstRate / srcRate * 2
        while (out.size < expected) { out += out.getOrElse(out.size - 2) { 0f } }
        return out.toFloatArray()
    }
}
