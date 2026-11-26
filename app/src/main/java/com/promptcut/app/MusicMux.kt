package com.promptcut.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.promptcut.app.audio.SfxSynth
import com.promptcut.app.audio.SfxType
import com.promptcut.app.audio.VoiceAndLoudnessDsp
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Mixes background music, voiceover tracks, and synthesized SFX overlays cleanly with the
 * rendered video's main audio, encodes AAC at the configured bitrate, and remuxes without touching video.
 */
object MusicMux {
    const val SR = 44100

    sealed class Source {
        class File(val uri: Uri, val startSec: Double) : Source()
    }

    private data class DecodedOverlay(
        val pcmFile: File,
        val totalFrames: Long,
        val startFrame: Int,
        val gain: Float,
        val fadeInFrames: Int,
        val fadeOutFrames: Int,
        val duckUnderSpeech: Boolean,
        val dsp: VoiceAndLoudnessDsp?,
    )

    fun addMusic(context: Context, video: File, music: Source, musicGain: Float, out: File, audioBitrateBps: Int = 192_000) {
        music as Source.File
        val durationSec = videoDuration(video)
        val totalFrames = (durationSec * SR).toInt().coerceAtLeast(1)
        val tmp = File(context.cacheDir, "mix").apply { mkdirs() }
        val clipRaw = File(tmp, "clip.pcm")
        val musicRaw = File(tmp, "music.pcm")
        try {
            val clipFrames = decodeToFile(context, Uri.fromFile(video), 0.0, durationSec + 0.5, clipRaw)
            val musicFrames = decodeToFile(context, music.uri, music.startSec, durationSec + 1.0, musicRaw)
            if (musicFrames <= 0) throw RenderException("Couldn't read the song file.")
            val aac = encodeAac(totalFrames, audioBitrateBps) { mixer ->
                PcmReader(if (clipFrames > 0) clipRaw else null, musicRaw, emptyList(), mixer)
            }.let { it(MixerStream(totalFrames, SR, musicGain)) }
            remux(video, aac, out)
        } finally {
            clipRaw.delete()
            musicRaw.delete()
        }
    }

    /**
     * Full V3 multi-track audio mixer: mixes video audio + A1 Music + A2 Voiceover + A3 SFX
     * with per-track gain, mute, voice enhancement, loudness normalization, and auto-ducking.
     */
    fun mixTimelineAudio(
        context: Context,
        video: File,
        project: TimelineProject,
        music: Source?,
        audioBitrateBps: Int = project.audioBitrateBps,
        out: File,
    ) {
        val durationSec = videoDuration(video)
        val totalFrames = (durationSec * SR).toInt().coerceAtLeast(1)
        val tmp = File(context.cacheDir, "mix_${System.currentTimeMillis()}").apply { mkdirs() }
        val clipRaw = File(tmp, "clip.pcm")
        var musicRaw: File? = null
        val overlays = mutableListOf<DecodedOverlay>()

        try {
            val clipFrames = decodeToFile(context, Uri.fromFile(video), 0.0, durationSec + 0.5, clipRaw)

            val musicTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.MUSIC }
            val musicTrackItem = musicTrack?.items?.firstOrNull()
            val musicGain = if (musicTrack?.muted == true) 0f else (musicTrack?.volume ?: 1f) * (musicTrackItem?.volume ?: project.toEditPlan().musicVolume.toFloat())
            val effectiveMusicUri = (music as? Source.File)?.uri ?: musicTrackItem?.sourceUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val musicStartSec = (music as? Source.File)?.startSec ?: usToSec(musicTrackItem?.sourceStartUs ?: 0L)
            if (effectiveMusicUri != null && musicGain > 0.01f) {
                val mr = File(tmp, "music.pcm")
                if (decodeToFile(context, effectiveMusicUri, musicStartSec, durationSec + 1.0, mr) > 0) {
                    musicRaw = mr
                } else {
                    mr.delete()
                }
            }

            // Decode A2 Voiceover & A3 SFX overlay clips
            var ovIdx = 0
            project.audioTracks.filter { !it.muted && it.type != AudioTrackType.MUSIC }.forEach { track ->
                track.items.forEach { item ->
                    val uri = resolveAudioUri(context, item.sourceUri) ?: return@forEach
                    val ovFile = File(tmp, "ov_${ovIdx++}.pcm")
                    val durSec = usToSec(item.durationUs).coerceAtLeast(0.15)
                    val decodedFrames = decodeToFile(context, uri, usToSec(item.sourceStartUs), durSec + 0.25, ovFile)
                    if (decodedFrames > 0) {
                        val dsp = if (track.voiceEnhance || track.normalizeTargetDb != null) {
                            VoiceAndLoudnessDsp(SR, 2, track.voiceEnhance, track.normalizeTargetDb, 1f)
                        } else null
                        overlays += DecodedOverlay(
                            pcmFile = ovFile,
                            totalFrames = decodedFrames,
                            startFrame = (usToSec(item.timelineStartUs) * SR).toInt().coerceAtLeast(0),
                            gain = (track.volume * item.volume).coerceIn(0f, 1.8f),
                            fadeInFrames = (usToSec(item.fadeInUs) * SR).toInt().coerceAtLeast(0),
                            fadeOutFrames = (usToSec(item.fadeOutUs) * SR).toInt().coerceAtLeast(0),
                            duckUnderSpeech = item.duckUnderSpeech,
                            dsp = dsp,
                        )
                    } else {
                        ovFile.delete()
                    }
                }
            }

            if (musicRaw == null && overlays.isEmpty()) {
                // No extra tracks needed; simply copy video out
                video.copyTo(out, overwrite = true)
                return
            }

            val aac = encodeAac(totalFrames, audioBitrateBps) { mixer ->
                PcmReader(if (clipFrames > 0) clipRaw else null, musicRaw, overlays, mixer)
            }.let { it(MixerStream(totalFrames, SR, if (musicRaw != null) musicGain else 0f)) }

            remux(video, aac, out)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun resolveAudioUri(context: Context, sourceUri: String): Uri? {
        if (sourceUri.isBlank()) return null
        if (sourceUri.startsWith("sfx://")) {
            val sfx = SfxType.fromKey(sourceUri.removePrefix("sfx://"))
            return Uri.fromFile(SfxSynth.ensureCachedWav(context, sfx))
        }
        return runCatching { Uri.parse(sourceUri) }.getOrNull()
    }

    private class OverlayReader(val meta: DecodedOverlay) {
        private val stream = java.io.DataInputStream(meta.pcmFile.inputStream().buffered(1 shl 14))
        private var consumedFrames = 0L
        private val bytes = ByteArray(8192 * 2)
        private val tmp = FloatArray(8192)
        private var closed = false

        fun readAndAdd(globalStartFrame: Int, frames: Int, accum: FloatArray) {
            if (closed) return
            val ovStart = meta.startFrame
            val ovEnd = ovStart + meta.totalFrames.toInt()
            val windowEnd = globalStartFrame + frames
            if (windowEnd <= ovStart || globalStartFrame >= ovEnd) return

            val outOffset = max(0, ovStart - globalStartFrame)
            val needed = min(windowEnd, ovEnd) - max(globalStartFrame, ovStart)
            if (needed <= 0) return

            val wantBytes = needed * 4
            var got = 0
            while (got < wantBytes) {
                val n = stream.read(bytes, got, wantBytes - got)
                if (n < 0) {
                    close()
                    break
                }
                got += n
            }
            val gotFrames = got / 4
            val bb = ByteBuffer.wrap(bytes, 0, gotFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until gotFrames) {
                var l = bb.short / 32768f
                var r = bb.short / 32768f
                meta.dsp?.let { d ->
                    l = d.processSample(0, l)
                    r = d.processSample(1, r)
                }
                val posInClip = (consumedFrames + k).toInt()
                val fadeIn = if (meta.fadeInFrames > 0) (posInClip.toFloat() / meta.fadeInFrames).coerceIn(0f, 1f) else 1f
                val rem = (meta.totalFrames - posInClip).toInt().coerceAtLeast(0)
                val fadeOut = if (meta.fadeOutFrames > 0) (rem.toFloat() / meta.fadeOutFrames).coerceIn(0f, 1f) else 1f
                val g = meta.gain * min(fadeIn, fadeOut)
                val dstIdx = (outOffset + k) * 2
                if (dstIdx + 1 < accum.size) {
                    accum[dstIdx] += l * g
                    accum[dstIdx + 1] += r * g
                }
            }
            consumedFrames += gotFrames
            if (consumedFrames >= meta.totalFrames) close()
        }

        fun close() {
            if (!closed) {
                closed = true
                runCatching { stream.close() }
            }
        }
    }

    /** Reads clip sound, (looping) music, and VO/SFX overlay tracks from raw files and mixes chunk by chunk. */
    private class PcmReader(
        clip: File?,
        private val music: File?,
        overlays: List<DecodedOverlay>,
        private val mixer: MixerStream,
    ) {
        private val clipIn = clip?.let { java.io.DataInputStream(it.inputStream().buffered(1 shl 16)) }
        private var musicIn = music?.let { openMusic(it) }
        private var clipDone = clip == null
        private val overlayReaders = overlays.map { OverlayReader(it) }
        private val cBuf = FloatArray(8192)
        private val mBuf = FloatArray(8192)
        private val ovBuf = FloatArray(8192)
        private val bytes = ByteArray(8192 * 2)
        private var globalFrame = 0

        private fun openMusic(f: File) = f.inputStream().buffered(1 shl 16)

        private fun readInto(input: java.io.InputStream, dst: FloatArray, frames: Int): Int {
            val want = frames * 4
            var got = 0
            while (got < want) {
                val n = input.read(bytes, got, want - got)
                if (n < 0) break
                got += n
            }
            val bb = ByteBuffer.wrap(bytes, 0, got).order(ByteOrder.LITTLE_ENDIAN)
            val samples = got / 2
            for (i in 0 until samples) dst[i] = bb.short / 32768f
            return samples / 2
        }

        fun next(frames: Int, out: ShortArray) {
            var clipFrames = 0
            if (!clipDone) {
                clipFrames = readInto(clipIn!!, cBuf, frames)
                if (clipFrames < frames) clipDone = true
            }

            var musicGot = 0
            val mf = music
            if (mf != null && musicIn != null) {
                musicGot = readInto(musicIn!!, mBuf, frames)
                while (musicGot < frames) {
                    musicIn?.close()
                    musicIn = openMusic(mf)
                    val extra = FloatArray((frames - musicGot) * 2)
                    val n = readInto(musicIn!!, extra, frames - musicGot)
                    if (n == 0) break
                    System.arraycopy(extra, 0, mBuf, musicGot * 2, n * 2)
                    musicGot += n
                }
            }

            mixer.process(if (clipFrames > 0) cBuf else null, clipFrames, if (musicGot > 0) mBuf else null, musicGot, frames, out)

            if (overlayReaders.isNotEmpty()) {
                java.util.Arrays.fill(ovBuf, 0, frames * 2, 0f)
                overlayReaders.forEach { it.readAndAdd(globalFrame, frames, ovBuf) }
                for (i in 0 until frames * 2) {
                    val base = out[i] / 32768f
                    val mixed = softLimit(base + ovBuf[i])
                    out[i] = (mixed.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
            globalFrame += frames
        }

        private fun softLimit(x: Float): Float =
            if (abs(x) <= 0.85f) x else (if (x > 0f) 1f else -1f) * (0.85f + 0.15f * tanh((abs(x) - 0.85f) / 0.15f))

        fun close() {
            runCatching { clipIn?.close() }
            runCatching { musicIn?.close() }
            overlayReaders.forEach { it.close() }
        }
    }

    private fun videoDuration(video: File): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(video.absolutePath)
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(MediaFormat.KEY_DURATION)) {
                    dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            return dur / 1_000_000.0
        } finally {
            ex.release()
        }
    }

    fun decodeToFile(context: Context, uri: Uri, startSec: Double, maxSec: Double, out: File): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var os: java.io.BufferedOutputStream? = null
        var written = 0L
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0
            os = out.outputStream().buffered(1 shl 16)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val startUs = (startSec * 1_000_000).toLong()
            val endUs = startUs + (maxSec * 1_000_000).toLong()
            val maxFrames = (maxSec * SR).toLong()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return 0
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var resampler: Resampler? = null
            var tmp = FloatArray(16384)
            val outBytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            loop@ while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = null
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bps = if (isFloat) 4 else 2
                    val count = info.size / bps
                    val skip = if (info.presentationTimeUs < startUs) {
                        (((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt() * channels).coerceAtMost(count)
                    } else 0
                    if (count > tmp.size) tmp = FloatArray(count)
                    var n = 0
                    for (k in 0 until count) {
                        val v = if (isFloat) buf.float else buf.short / 32768f
                        if (k >= skip) tmp[n++] = v
                    }
                    codec.releaseOutputBuffer(oi, false)
                    val rs = resampler ?: Resampler(channels.coerceAtLeast(1), rate, SR).also { resampler = it }
                    rs.feed(tmp, n) { l, r ->
                        if (written < maxFrames) {
                            if (outBytes.remaining() < 4) { os?.write(outBytes.array(), 0, outBytes.position()); outBytes.clear() }
                            outBytes.putShort((l.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            outBytes.putShort((r.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            written++
                        }
                    }
                    if (outBytes.position() > 0) { os?.write(outBytes.array(), 0, outBytes.position()); outBytes.clear() }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || written >= maxFrames) break@loop
                }
            }
            return written
        } catch (e: Exception) {
            return 0
        } finally {
            runCatching { os?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private class Encoded(val format: MediaFormat, val chunks: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    private fun encodeAac(totalFrames: Int, bitrateBps: Int, makeReader: (MixerStream) -> PcmReader): (MixerStream) -> Encoded = { mixer ->
        val reader = makeReader(mixer)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceIn(96_000, 320_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val chunks = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var frame = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(1024 * 2)
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val pts = frame * 1_000_000L / SR
                        if (frame >= totalFrames) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            val count = minOf(1024, totalFrames - frame, buf.capacity() / 4)
                            reader.next(count, pcm)
                            for (k in 0 until count * 2) buf.putShort(pcm[k])
                            codec.queueInputBuffer(ii, 0, count * 4, pts, 0)
                            frame += count
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (oi >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buf.get(it) }
                        chunks += bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            reader.close()
            runCatching { codec.stop() }
            codec.release()
        }
        Encoded(outFormat ?: throw RenderException("Audio encoder produced no format."), chunks)
    }

    private fun remux(video: File, audio: Encoded, out: File) {
        val ex = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(video.absolutePath)
            val vTrack = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw RenderException("Video contains no video track.")
            ex.selectTrack(vTrack)
            val vFormat = ex.getTrackFormat(vTrack)
            val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.getInteger(MediaFormat.KEY_ROTATION)
            else android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(video.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val dstV = muxer.addTrack(vFormat)
            val dstA = muxer.addTrack(audio.format)
            muxer.start()
            val buf = ByteBuffer.allocate(maxOf(4 * 1024 * 1024, if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
            val vInfo = MediaCodec.BufferInfo()
            var ai = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                while (ai < audio.chunks.size && audio.chunks[ai].second.presentationTimeUs <= t) {
                    val (bytes, info) = audio.chunks[ai++]
                    muxer.writeSampleData(dstA, ByteBuffer.wrap(bytes), info)
                }
                vInfo.set(0, size, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(dstV, buf, vInfo)
                ex.advance()
            }
            while (ai < audio.chunks.size) {
                val (bytes, info) = audio.chunks[ai++]
                muxer.writeSampleData(dstA, ByteBuffer.wrap(bytes), info)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }
}
