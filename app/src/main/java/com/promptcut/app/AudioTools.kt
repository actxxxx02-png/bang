package com.promptcut.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioTools {

    /**
     * Decodes the audio track and returns RMS loudness (0..1) per [hopSec].
     * Returns null when there is no decodable audio.
     */
    fun envelope(context: Context, uri: Uri, hopSec: Double, maxSec: Double = 240.0): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val out = ArrayList<Float>()
            var acc = 0.0
            var accN = 0
            var framesPerHop = (sampleRate * hopSec).toInt().coerceAtLeast(1)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalFrames = 0L


            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > maxSec * 1_000_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        framesPerHop = (sampleRate * hopSec).toInt().coerceAtLeast(1)
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytesPerSample = if (isFloat) 4 else 2
                        val frames = info.size / (bytesPerSample * channels)
                        for (f in 0 until frames) {
                            var mono = 0.0
                            for (c in 0 until channels) {
                                mono += if (isFloat) buf.float.toDouble() else buf.short / 32768.0
                            }
                            mono /= channels
                            acc += mono * mono; accN++
                            if (accN >= framesPerHop) {
                                out += sqrt(acc / accN).toFloat(); acc = 0.0; accN = 0
                            }
                        }
                        totalFrames += frames
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || totalFrames > (maxSec * sampleRate).toLong()) outputDone = true
                    }
                }
            }
            return if (out.isEmpty()) null else out.toFloatArray()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /**
     * Copies the AAC audio track into an .m4a file without re-encoding (for speech-to-text upload).
     * Returns false if the clip's audio isn't AAC or the file would be too large.
     */
    fun extractAac(
        context: Context, uri: Uri, out: File,
        fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE, maxBytes: Long = 24L * 1024 * 1024,
    ): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: return false
            ex.selectTrack(track)
            if (fromSec > 0) ex.seekTo((fromSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = ex.sampleTime.coerceAtLeast(0)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dst = muxer.addTrack(ex.getTrackFormat(track))
            muxer.start()
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var written = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0 || ex.sampleTime > toSec * 1_000_000) break
                info.set(0, n, ex.sampleTime - base, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(dst, buf, info)
                written += n
                if (written > maxBytes) return false
                ex.advance()
            }
            muxer.stop()
            return written > 0
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }

    /** Spans where the sound is clearly louder than the clip's background (talking, cheering, laughter). */
    fun loudSpans(env: FloatArray, hopSec: Double): List<Span> {
        if (env.isEmpty()) return emptyList()
        val sorted = env.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9) / 10]
        val threshold = maxOf(median * 2.2f, 0.03f).coerceAtMost(p90.coerceAtLeast(0.03f))
        val spans = mutableListOf<Span>()
        var startI = -1
        for (i in env.indices) {
            val on = env[i] >= threshold
            if (on && startI < 0) startI = i
            if ((!on || i == env.lastIndex) && startI >= 0) {
                val endI = if (on) i + 1 else i
                spans += Span(startI * hopSec, endI * hopSec)
                startI = -1
            }
        }
        // merge small gaps, drop blips
        val merged = mutableListOf<Span>()
        spans.forEach { s ->
            val last = merged.lastOrNull()
            if (last != null && s.start - last.end < 0.45) merged[merged.lastIndex] = Span(last.start, s.end) else merged += s
        }
        return merged.filter { it.length >= 0.35 }
    }

    /**
     * Detects musical beat timestamps in microseconds for timeline beat-markers & beat-sync editing.
     * Falls back to onset peaks if tempo confidence is ambiguous.
     */
    fun detectBeats(context: Context, uri: Uri, hopSec: Double = 0.05, maxSec: Double = 180.0): List<Long> {
        val env = envelope(context, uri, hopSec, maxSec) ?: return emptyList()
        if (env.isEmpty()) return emptyList()
        val tempo = Beat.detect(env, hopSec)
        val totalSec = env.size * hopSec
        if (tempo != null) {
            val out = mutableListOf<Long>()
            var t = tempo.offsetSec
            while (t <= totalSec) {
                if (t >= 0.0) out += (t * 1_000_000.0).toLong()
                t += tempo.beatSec
            }
            if (out.isNotEmpty()) return out
        }
        val onsets = Beat.onsets(env)
        val mean = onsets.average()
        val markers = mutableListOf<Long>()
        var lastT = -1.0
        for (i in onsets.indices) {
            val t = i * hopSec
            if (onsets[i] > maxOf(mean * 2.5, 0.25) && t - lastT >= 0.32) {
                markers += (t * 1_000_000.0).toLong()
                lastT = t
            }
        }
        return markers
    }
}
