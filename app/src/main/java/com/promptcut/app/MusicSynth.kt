package com.promptcut.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates original background music for each mood, right on the phone, so there are no licensing issues.
 * Tempo is known exactly, so cuts can land on the beat.
 */
object MusicSynth {
    const val SR = 44100
    private const val TWO_PI = 2 * PI

    private class Track(seconds: Double) {
        val n = (seconds * SR).toInt() + SR
        val l = FloatArray(n)
        val r = FloatArray(n)
        fun add(i: Int, v: Double, pan: Double) {
            if (i < 0 || i >= n) return
            l[i] += (v * (1 - pan) ).toFloat()
            r[i] += (v * (1 + pan)).toFloat()
        }
    }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Chord = root midi + intervals. */
    private data class Chord(val root: Int, val tones: IntArray) {
        fun notes(octaveShift: Int = 0) = tones.map { root + it + 12 * octaveShift }
    }
    private val MAJ = intArrayOf(0, 4, 7)
    private val MIN = intArrayOf(0, 3, 7)
    private val MAJ7 = intArrayOf(0, 4, 7, 11)
    private val MIN7 = intArrayOf(0, 3, 7, 10)

    // ------------------------------------------------------------------ instruments

    private fun pluck(t: Track, time: Double, f: Double, amp: Double, pan: Double = 0.0, decay: Double = 6.0, len: Double = 0.6) {
        val s0 = (time * SR).toInt()
        val count = (len * SR).toInt()
        for (k in 0 until count) {
            val x = k.toDouble() / SR
            val env = min(1.0, x / 0.004) * exp(-x * decay)
            val v = sin(TWO_PI * f * x) + 0.45 * sin(TWO_PI * 2 * f * x) * exp(-x * decay) + 0.2 * sin(TWO_PI * 3 * f * x) * exp(-x * decay * 2)
            t.add(s0 + k, amp * env * v, pan)
        }
    }

    private fun piano(t: Track, time: Double, f: Double, amp: Double, pan: Double = 0.0, len: Double = 2.4) {
        val s0 = (time * SR).toInt()
        val count = (len * SR).toInt()
        for (k in 0 until count) {
            val x = k.toDouble() / SR
            var v = 0.0
            for (h in 1..5) v += sin(TWO_PI * f * h * 1.0007.pow(h) * x) * exp(-x * (1.2 + h * 0.9)) / h.toDouble().pow(1.3)
            val env = min(1.0, x / 0.003) * (1.0 - (x / len).pow(8))
            t.add(s0 + k, amp * env * v, pan)
        }
    }

    private fun pad(t: Track, time: Double, dur: Double, f: Double, amp: Double, pan: Double = 0.0, bright: Int = 5) {
        val s0 = (time * SR).toInt()
        val rel = 0.9
        val count = ((dur + rel) * SR).toInt()
        val atk = 0.5
        for (k in 0 until count) {
            val x = k.toDouble() / SR
            val env = when {
                x < atk -> x / atk
                x < dur -> 1.0
                else -> max(0.0, 1 - (x - dur) / rel)
            }
            var v = 0.0
            for (h in 1..bright) {
                val a = 1.0 / h
                v += a * (sin(TWO_PI * f * h * 1.003 * x) + sin(TWO_PI * f * h * 0.997 * x + 1.3))
            }
            t.add(s0 + k, amp * env * v * 0.5, pan)
        }
    }

    private fun bass(t: Track, time: Double, dur: Double, f: Double, amp: Double) {
        val s0 = (time * SR).toInt()
        val count = ((dur + 0.05) * SR).toInt()
        for (k in 0 until count) {
            val x = k.toDouble() / SR
            val env = min(1.0, x / 0.006) * (if (x > dur) max(0.0, 1 - (x - dur) / 0.05) else 1.0) * (0.75 + 0.25 * exp(-x * 8))
            val v = sin(TWO_PI * f * x) + 0.35 * sin(TWO_PI * 2 * f * x) + 0.12 * sin(TWO_PI * 3 * f * x)
            t.add(s0 + k, amp * env * v, 0.0)
        }
    }

    private fun bell(t: Track, time: Double, f: Double, amp: Double, pan: Double) {
        val s0 = (time * SR).toInt()
        val count = (2.5 * SR).toInt()
        for (k in 0 until count) {
            val x = k.toDouble() / SR
            val v = sin(TWO_PI * f * x) * exp(-x * 1.6) + 0.4 * sin(TWO_PI * f * 2.76 * x) * exp(-x * 3.5) +
                0.18 * sin(TWO_PI * f * 5.4 * x) * exp(-x * 7)
            t.add(s0 + k, amp * min(1.0, x / 0.002) * v, pan)
        }
    }

    private fun kick(t: Track, time: Double, amp: Double) {
        val s0 = (time * SR).toInt()
        var phase = 0.0
        for (k in 0 until (0.4 * SR).toInt()) {
            val x = k.toDouble() / SR
            val f = 48 + 110 * exp(-x * 28)
            phase += TWO_PI * f / SR
            t.add(s0 + k, amp * sin(phase) * exp(-x * 8), 0.0)
        }
    }

    private fun snare(t: Track, time: Double, amp: Double, rnd: Random, clap: Boolean = false) {
        val s0 = (time * SR).toInt()
        var prev = 0.0
        for (k in 0 until (0.25 * SR).toInt()) {
            val x = k.toDouble() / SR
            val raw = rnd.nextDouble(-1.0, 1.0)
            val hp = raw - prev * 0.6; prev = raw
            val noiseEnv = if (clap) exp(-((x * 1000) % 11) * 0.35) * exp(-x * 18) else exp(-x * 20)
            val tone = if (clap) 0.0 else 0.45 * sin(TWO_PI * 185 * x) * exp(-x * 30)
            t.add(s0 + k, amp * (0.6 * hp * noiseEnv + tone), 0.1)
        }
    }

    private fun hat(t: Track, time: Double, amp: Double, rnd: Random, open: Boolean = false) {
        val s0 = (time * SR).toInt()
        var prev = 0.0
        val decay = if (open) 14.0 else 70.0
        for (k in 0 until ((if (open) 0.25 else 0.07) * SR).toInt()) {
            val x = k.toDouble() / SR
            val raw = rnd.nextDouble(-1.0, 1.0)
            val hp = raw - prev; prev = raw
            t.add(s0 + k, amp * hp * exp(-x * decay), -0.25)
        }
    }

    // ------------------------------------------------------------------ arrangements

    /** Renders [seconds] of music for [mood] into a WAV file. Returns the BPM. */
    fun render(mood: Mood, seconds: Double, out: File): Double {
        writeWav(compose(mood, seconds), out)
        return mood.bpm.toDouble()
    }

    /** Interleaved stereo floats (peak-normalised), for mixing directly without a file. */
    fun renderPcm(mood: Mood, seconds: Double): FloatArray {
        val t = compose(mood, seconds)
        val gain = normGain(t)
        val n = minOf(t.n, (seconds * SR).toInt() + SR)
        return FloatArray(n * 2) { i -> softClip((if (i % 2 == 0) t.l[i / 2] else t.r[i / 2]) * gain) }
    }

    private fun normGain(t: Track): Float {
        var peak = 1e-6f
        for (i in 0 until t.n) peak = max(peak, max(abs(t.l[i]), abs(t.r[i])))
        return 0.89f / peak
    }

    private fun compose(mood: Mood, seconds: Double): Track {
        val bpm = mood.bpm.toDouble()
        val beat = 60.0 / bpm
        val bar = beat * 4
        val bars = ceil(seconds / bar).toInt() + 1
        val track = Track(bars * bar + 3)
        val rnd = Random(mood.ordinal * 7919 + 17)

        when (mood) {
            Mood.HAPPY, Mood.FUNNY -> {
                val prog = if (mood == Mood.HAPPY)
                    listOf(Chord(48, MAJ), Chord(55, MAJ), Chord(57, MIN), Chord(53, MAJ))  // C G Am F
                else listOf(Chord(48, MAJ), Chord(53, MAJ), Chord(55, MAJ), Chord(48, MAJ))  // C F G C
                for (b in 0 until bars) {
                    val c = prog[b % 4]; val t0 = b * bar
                    val full = b >= 1
                    c.notes(1).forEach { pad(track, t0, bar, hz(it), 0.018, 0.0, 4) }
                    // 8th-note arpeggio
                    val arp = c.notes(2) + c.notes(2).first() + 12
                    for (e in 0 until 8) {
                        val note = arp[listOf(0, 1, 2, 3, 2, 1, 2, 3)[e]]
                        val dec = if (mood == Mood.FUNNY) 14.0 else 7.0
                        pluck(track, t0 + e * beat / 2, hz(note), if (full) 0.10 else 0.07, if (e % 2 == 0) -0.35 else 0.35, dec)
                    }
                    if (full) {
                        for (q in 0 until 4) {
                            val bn = if (mood == Mood.FUNNY && q % 2 == 1) c.root - 12 + 12 else c.root - 12
                            bass(track, t0 + q * beat, beat * (if (mood == Mood.FUNNY) 0.45 else 0.85), hz(bn), 0.22)
                        }
                        kick(track, t0, 0.55); kick(track, t0 + 2 * beat, 0.55); kick(track, t0 + 2.5 * beat, 0.35)
                        snare(track, t0 + beat, 0.22, rnd, clap = true); snare(track, t0 + 3 * beat, 0.22, rnd, clap = true)
                        for (e in 0 until 8) hat(track, t0 + e * beat / 2, if (e % 2 == 1) 0.07 else 0.045, rnd)
                    }
                }
            }
            Mood.ENERGETIC -> {
                val prog = listOf(Chord(45, MIN), Chord(41, MAJ), Chord(48, MAJ), Chord(43, MAJ)) // Am F C G
                for (b in 0 until bars) {
                    val c = prog[b % 4]; val t0 = b * bar
                    val full = b >= 1
                    c.notes(1).forEach { pad(track, t0, bar, hz(it), 0.02, 0.0, 6) }
                    val arp = c.notes(2)
                    for (s in 0 until 16) {
                        pluck(track, t0 + s * beat / 4, hz(arp[s % 3] + if (s % 8 >= 6) 12 else 0), if (full) 0.07 else 0.05,
                            if (s % 2 == 0) -0.4 else 0.4, 11.0, 0.3)
                    }
                    if (full) {
                        for (e in 0 until 8) {
                            val sidechain = if (e % 2 == 0) 0.6 else 1.0
                            bass(track, t0 + e * beat / 2, beat * 0.42, hz(c.root - 12 + if (e % 2 == 1) 12 else 0), 0.2 * sidechain)
                        }
                        for (q in 0 until 4) kick(track, t0 + q * beat, 0.6)
                        snare(track, t0 + beat, 0.25, rnd); snare(track, t0 + 3 * beat, 0.25, rnd)
                        for (e in 0 until 8) hat(track, t0 + e * beat / 2 + beat / 4, 0.06, rnd, open = e == 7)
                        if (b % 4 == 3) for (s in 0 until 4) snare(track, t0 + 3 * beat + s * beat / 4, 0.12 + s * 0.03, rnd)
                    }
                }
            }
            Mood.EMOTIONAL -> {
                val prog = listOf(Chord(57, MIN), Chord(53, MAJ), Chord(48, MAJ), Chord(55, MAJ)) // Am F C G
                for (b in 0 until bars) {
                    val c = prog[b % 4]; val t0 = b * bar
                    piano(track, t0, hz(c.root - 12), 0.16, -0.1, bar)
                    val broken = listOf(c.notes(0)[0], c.notes(0)[1], c.notes(0)[2], c.notes(1)[0], c.notes(0)[2], c.notes(0)[1], c.notes(0)[2], c.notes(1)[1])
                    broken.forEachIndexed { e, n -> piano(track, t0 + e * beat / 2, hz(n), 0.07, if (e % 2 == 0) 0.2 else -0.2, 1.8) }
                    if (b >= 2) {
                        // simple melody on top: chord tone per half bar
                        piano(track, t0, hz(c.notes(1)[2]), 0.06, 0.3)
                        piano(track, t0 + 2 * beat, hz(c.notes(1)[1]), 0.05, 0.3)
                    }
                    if (b >= 1) c.notes(0).forEach { pad(track, t0, bar, hz(it), 0.012 + min(b, 8) * 0.0015, 0.0, 3) }
                }
            }
            Mood.CALM -> {
                val prog = listOf(Chord(53, MAJ7), Chord(48, MAJ7), Chord(50, MIN7), Chord(45, MIN7)) // Fmaj7 Cmaj7 Dm7 Am7
                for (b in 0 until bars) {
                    val c = prog[b % 4]; val t0 = b * bar
                    c.notes(0).forEach { pad(track, t0, bar, hz(it), 0.02, 0.0, 3) }
                    bass(track, t0, bar * 0.95, hz(c.root - 12), 0.12)
                    for (q in 0 until 4) {
                        if (rnd.nextDouble() < 0.7) {
                            val n = c.notes(1)[rnd.nextInt(c.tones.size)] + if (rnd.nextBoolean()) 12 else 0
                            bell(track, t0 + q * beat + (if (q % 2 == 1) beat / 2 else 0.0), hz(n), 0.05, rnd.nextDouble(-0.5, 0.5))
                        }
                    }
                }
            }
            Mood.CINEMATIC -> {
                val prog = listOf(Chord(50, MIN), Chord(46, MAJ), Chord(53, MAJ), Chord(48, MAJ)) // Dm Bb F C
                for (b in 0 until bars) {
                    val c = prog[b % 4]; val t0 = b * bar
                    val intensity = min(1.0, 0.4 + b * 0.08)
                    c.notes(-1).forEach { pad(track, t0, bar, hz(it), 0.022 * intensity, 0.0, 6) }
                    c.notes(0).forEach { pad(track, t0, bar, hz(it), 0.012 * intensity, 0.2, 4) }
                    bass(track, t0, bar * 0.95, hz(c.root - 24), 0.16 * intensity)
                    if (b >= 2) for (e in 0 until 8) {
                        val n = if (e % 2 == 0) c.root + 12 else c.root + 19
                        pluck(track, t0 + e * beat / 2, hz(n), 0.06 * intensity, if (e % 2 == 0) -0.3 else 0.3, 9.0, 0.4)
                    }
                    kick(track, t0, 0.5 * intensity)
                    if (b >= 4) { kick(track, t0 + 2.5 * beat, 0.35 * intensity); snare(track, t0 + 2 * beat, 0.12 * intensity, rnd) }
                }
            }
        }
        return track
    }

    private fun writeWav(t: Track, out: File) {
        val gain = normGain(t)
        val dataBytes = t.n * 4
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(SR).putInt(SR * 4).putShort(4).putShort(16)
            h.put("data".toByteArray()).putInt(dataBytes)
            f.write(h.array())
            val chunk = ByteBuffer.allocate(4 * 8192).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < t.n) {
                chunk.clear()
                val end = min(t.n, i + 8192)
                for (k in i until end) {
                    chunk.putShort((softClip(t.l[k] * gain) * 32767).toInt().toShort())
                    chunk.putShort((softClip(t.r[k] * gain) * 32767).toInt().toShort())
                }
                f.write(chunk.array(), 0, chunk.position())
                i = end
            }
        }
    }

    private fun softClip(x: Float): Float = if (abs(x) < 0.9f) x else (x / abs(x)) * (0.9f + 0.1f * kotlin.math.tanh((abs(x) - 0.9f) * 5))
}
