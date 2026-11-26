package com.promptcut.app.audio

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class SfxType(val key: String, val label: String, val durationSec: Double) {
    WHOOSH("whoosh", "Whoosh", 0.55),
    IMPACT("impact", "Impact Hit", 0.70),
    POP("pop", "UI Pop", 0.22),
    RISER("riser", "Riser Swell", 1.35),
    CHIME("chime", "Bright Chime", 0.90),
    ;

    companion object {
        fun fromKey(raw: String): SfxType {
            val k = raw.trim().lowercase()
            return entries.firstOrNull { it.key == k || k.contains(it.key) } ?: WHOOSH
        }
    }
}

/**
 * Deterministic PCM sound-effect synthesizer producing clean stereo WAV files for
 * timeline SFX tracks, audio preview, and multi-track MediaMuxer/MusicMux export.
 */
object SfxSynth {
    const val SR = 44100
    private const val TWO_PI = 2.0 * PI

    fun synthesizePcm(type: SfxType, sampleRate: Int = SR): FloatArray {
        val frames = (type.durationSec * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * 2)
        val rnd = Random(type.ordinal * 31337 + 7)
        var noiseState = 0.0
        var phase = 0.0

        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val norm = (i.toDouble() / frames).coerceIn(0.0, 1.0)
            var left = 0.0
            var right = 0.0

            when (type) {
                SfxType.WHOOSH -> {
                    // Filtered noise sweep + stereo Doppler pan
                    val white = rnd.nextDouble(-1.0, 1.0)
                    val lpAlpha = 0.04 + 0.35 * sin(PI * norm)
                    noiseState += (white - noiseState) * lpAlpha
                    val env = sin(PI * norm).pow(1.4)
                    val pan = (norm - 0.5) * 0.75
                    val tone = sin(TWO_PI * (140.0 + 380.0 * sin(PI * norm)) * t) * 0.12
                    val sig = (noiseState * 0.85 + tone) * env
                    left = sig * (1.0 - pan * 0.5)
                    right = sig * (1.0 + pan * 0.5)
                }
                SfxType.IMPACT -> {
                    // Sub-bass punch drop + crisp transient click
                    val freq = 38.0 + 170.0 * exp(-t * 24.0)
                    phase += TWO_PI * freq / sampleRate
                    val sub = sin(phase) * exp(-t * 5.2)
                    val click = rnd.nextDouble(-1.0, 1.0) * exp(-t * 55.0) * 0.45
                    val attack = min(1.0, t / 0.003)
                    val sig = (sub * 0.9 + click) * attack
                    left = sig
                    right = sig
                }
                SfxType.POP -> {
                    // Quick pitch-bent bubble pop
                    val freq = 320.0 + 520.0 * (1.0 - norm).pow(2.0)
                    phase += TWO_PI * freq / sampleRate
                    val env = min(1.0, t / 0.004) * exp(-t * 22.0)
                    val sig = sin(phase) * env * 0.85
                    left = sig * 0.96
                    right = sig * 1.04
                }
                SfxType.RISER -> {
                    // Exponential rising harmonic tension swell + air rush
                    val freq = 110.0 * (2.0.pow(norm * 2.4))
                    phase += TWO_PI * freq / sampleRate
                    val white = rnd.nextDouble(-1.0, 1.0)
                    noiseState += (white - noiseState) * (0.05 + 0.30 * norm)
                    val env = norm.pow(1.8) * (1.0 - max(0.0, (norm - 0.95) / 0.05))
                    val harm = 0.55 * sin(phase) + 0.25 * sin(phase * 2.01) + 0.20 * noiseState
                    left = harm * env * (0.9 + 0.1 * sin(t * 9.0))
                    right = harm * env * (0.9 - 0.1 * sin(t * 9.0))
                }
                SfxType.CHIME -> {
                    // Clean C5-G5 bell chord shimmer
                    val env = min(1.0, t / 0.005) * exp(-t * 3.8)
                    val c5 = sin(TWO_PI * 523.25 * t) * 0.50
                    val g5 = sin(TWO_PI * 783.99 * t) * 0.32 * exp(-t * 1.2)
                    val c6 = sin(TWO_PI * 1046.5 * t) * 0.18 * exp(-t * 2.0)
                    left = (c5 + g5 * 0.9 + c6 * 1.1) * env
                    right = (c5 + g5 * 1.1 + c6 * 0.9) * env
                }
            }

            out[2 * i] = softClip(left.toFloat())
            out[2 * i + 1] = softClip(right.toFloat())
        }
        return normalize(out)
    }

    fun writeWav(type: SfxType, outFile: File, sampleRate: Int = SR): File {
        outFile.parentFile?.mkdirs()
        val pcm = synthesizePcm(type, sampleRate)
        val frames = pcm.size / 2
        val dataBytes = frames * 4
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes)
            raf.write(header.array())
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) {
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
        return outFile
    }

    fun ensureCachedWav(context: Context, type: SfxType): File {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        val file = File(dir, "sfx_${type.key}.wav")
        if (!file.exists() || file.length() <= 44L) {
            writeWav(type, file)
        }
        return file
    }

    private fun normalize(buf: FloatArray): FloatArray {
        var peak = 1e-6f
        for (v in buf) peak = max(peak, abs(v))
        val gain = (0.88f / peak).coerceAtMost(2.5f)
        return FloatArray(buf.size) { i -> (buf[i] * gain).coerceIn(-1f, 1f) }
    }

    private fun softClip(x: Float): Float =
        if (abs(x) <= 0.85f) x else (x / abs(x)) * (0.85f + 0.15f * tanh((abs(x) - 0.85f) * 5f))
}
