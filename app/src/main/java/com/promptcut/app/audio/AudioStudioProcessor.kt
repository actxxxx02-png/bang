package com.promptcut.app.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Pure Kotlin DSP engine usable both inside Media3 [AudioStudioProcessor] and [MusicMux] offline mixing:
 * - Optional Voice Enhance (110 Hz high-pass rumble removal + gentle 3 kHz vocal clarity shelf)
 * - Optional Loudness Normalization toward target dBFS (-14 dBFS default)
 * - Soft-knee limiter preventing digital clipping.
 */
class VoiceAndLoudnessDsp(
    private val sampleRate: Int,
    private val channels: Int,
    private val voiceEnhance: Boolean,
    private val normalizeTargetDb: Float?,
    private val gain: Float = 1f,
) {
    private val hpPrevIn = FloatArray(channels.coerceAtLeast(1))
    private val hpPrevOut = FloatArray(channels.coerceAtLeast(1))
    private val lpPrev = FloatArray(channels.coerceAtLeast(1))
    private val hpCoeff = exp(-2.0 * Math.PI * 110.0 / sampleRate.coerceAtLeast(8000)).toFloat()
    private val shelfCoeff = exp(-2.0 * Math.PI * 2200.0 / sampleRate.coerceAtLeast(8000)).toFloat()
    private var rmsEnv = 0.1f
    private val targetLinear = normalizeTargetDb?.let { 10f.pow(it.coerceIn(-24f, -3f) / 20f) } ?: 0f

    fun processSample(ch: Int, input: Float): Float {
        val c = ch.coerceIn(0, hpPrevIn.lastIndex)
        var x = input * gain
        if (voiceEnhance) {
            // 1-pole high-pass (~110 Hz) removes handling rumble & wind thump
            val hp = hpCoeff * (hpPrevOut[c] + x - hpPrevIn[c])
            hpPrevIn[c] = x
            hpPrevOut[c] = hp
            // Gentle presence emphasis (~2.2 kHz+ high-shelf +0.35)
            lpPrev[c] = lpPrev[c] + (1f - shelfCoeff) * (hp - lpPrev[c])
            val highBand = hp - lpPrev[c]
            x = hp + highBand * 0.35f
        }
        if (targetLinear > 0f) {
            val mag = abs(x)
            rmsEnv = rmsEnv * 0.9992f + mag * 0.0008f
            val normGain = (targetLinear / max(rmsEnv, 0.02f)).coerceIn(0.45f, 2.8f)
            x *= normGain
        }
        return softLimit(x)
    }

    private fun softLimit(x: Float): Float =
        if (abs(x) <= 0.85f) x else (if (x > 0f) 1f else -1f) * (0.85f + 0.15f * tanh((abs(x) - 0.85f) / 0.15f))
}

/**
 * Media3 [AudioProcessor] applying track/clip gain, voice enhancement, and loudness normalization.
 */
@OptIn(UnstableApi::class)
class AudioStudioProcessor(
    private val gain: Float = 1f,
    private val voiceEnhance: Boolean = false,
    private val normalizeTargetDb: Float? = null,
) : BaseAudioProcessor() {
    private var dsp: VoiceAndLoudnessDsp? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!voiceEnhance && normalizeTargetDb == null && abs(gain - 1f) < 0.01f) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        dsp = VoiceAndLoudnessDsp(
            sampleRate = inputAudioFormat.sampleRate,
            channels = inputAudioFormat.channelCount,
            voiceEnhance = voiceEnhance,
            normalizeTargetDb = normalizeTargetDb,
            gain = gain,
        )
        return inputAudioFormat
    }

    override fun onFlush() {
        dsp = null
    }

    override fun onReset() {
        dsp = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val engine = dsp ?: VoiceAndLoudnessDsp(fmt.sampleRate, fmt.channelCount, voiceEnhance, normalizeTargetDb, gain).also { dsp = it }
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = fmt.bytesPerFrame
        val out = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            for (c in 0 until fmt.channelCount) {
                val s = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                val processed = engine.processSample(c, s)
                if (isFloat) out.putFloat(processed)
                else out.putShort((processed.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }
}
