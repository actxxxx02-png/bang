package com.promptcut.app

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class MusicAndIntentsTest {
    private val sr = 44100

    private fun tone(sec: Double, amp: Float, hz: Double = 440.0) = FloatArray((sec * sr).toInt() * 2) { i ->
        (amp * sin(2 * Math.PI * hz * (i / 2) / sr)).toFloat()
    }

    private fun rms(a: ShortArray, fromSec: Double, toSec: Double): Double {
        val s = (fromSec * sr).toInt() * 2; val e = (toSec * sr).toInt() * 2
        var acc = 0.0
        for (i in s until e) acc += (a[i] / 32768.0).let { it * it }
        return Math.sqrt(acc / (e - s))
    }

    @Test fun musicIsAudibleAndOutputHasVideoLength() {
        val music = tone(30.0, 0.5f)
        val out = Mixer.mix(null, music, frames = 10 * sr, sampleRate = sr, musicGain = 0.8f)
        assertEquals(10 * sr * 2, out.size)                   // exactly the video length, never the song length
        assertTrue(rms(out, 2.0, 7.0) > 0.2)                  // clearly audible in the middle
        assertTrue(rms(out, 9.95, 10.0) < 0.05)               // faded out at the end
        assertTrue(rms(out, 0.0, 0.02) < 0.1)                 // fades in
    }

    @Test fun musicDucksUnderSpeech() {
        val music = tone(12.0, 0.5f, 220.0)
        val clip = FloatArray(12 * sr * 2).also { c ->           // "speech" between 4 and 8 s
            for (f in 4 * sr until 8 * sr) { val v = (0.4 * sin(2 * Math.PI * 1000.0 * f / sr)).toFloat(); c[2 * f] = v; c[2 * f + 1] = v }
        }
        val withSpeech = Mixer.mix(clip, music, 12 * sr, sr, 0.8f)
        val musicOnly = Mixer.mix(null, music, 12 * sr, sr, 0.8f)
        // During speech the music part must be much quieter: compare total level minus speech level.
        val speechAlone = Mixer.mix(clip, FloatArray(0), 12 * sr, sr, 0.8f)
        val duckedMusic = rms(withSpeech, 5.5, 7.5) - rms(speechAlone, 5.5, 7.5)
        assertTrue("music should duck", duckedMusic < rms(musicOnly, 5.5, 7.5) * 0.6)
        assertTrue("music returns after speech", rms(withSpeech, 10.0, 11.0) > rms(musicOnly, 10.0, 11.0) * 0.8)
    }

    @Test fun shortSongLoops() {
        val out = Mixer.mix(null, tone(3.0, 0.5f), 10 * sr, sr, 0.8f)
        assertTrue(rms(out, 6.0, 8.0) > 0.2)
    }

    @Test fun resamplesMonoToStereo() {
        val mono = FloatArray(22050) { 0.5f }
        val st = Mixer.toStereo(mono, 1, 22050, 44100)
        assertEquals(44100 * 2, st.size)
        assertEquals(0.5f, st[1000], 1e-4f)
    }

    @Test fun userMessagesAreUnderstood() {
        val a = QuickIntents.parse("make it natural, currently its warm and in background add a good music")
        assertEquals(ColorLook.NATURAL, a.color)
        assertEquals(true, a.musicOn)
        assertEquals(Transition.SMOOTH, QuickIntents.parse("the transition should be smooth with effects").transition)
        assertEquals(true, QuickIntents.parse("add a background song").musicOn)
        assertEquals(false, QuickIntents.parse("remove the music, keep original sound").musicOn)
        assertEquals(ColorLook.BW, QuickIntents.parse("black and white please").color)
        assertEquals(ColorLook.WARM, QuickIntents.parse("make it warm instead of cool").color)
        assertEquals(Transition.FLASH, QuickIntents.parse("use flash transitions").transition)
        assertNull(QuickIntents.parse("shorter intro").color)
        assertNull(QuickIntents.parse("shorter intro").transition)
        assertTrue(QuickIntents.parse("no title").noTitle)
    }

    @Test fun planCarriesTransitionAndMusicRequest() {
        val clips = listOf(ClipMeta(0, "a.mp4", 10.0, 1080, 1920, true))
        val p = PlanCodec.parse("""{"transition":"zoom","music":"on","music_query":"upbeat acoustic","segments":[{"clip":0,"start":0,"end":3}]}""", clips)
        assertEquals(Transition.ZOOM, p.transition)
        assertEquals(true, p.musicOn)
        assertEquals("upbeat acoustic", p.musicQuery)
        assertEquals(Transition.SMOOTH, p.copy(transition = Transition.AUTO, mood = Mood.EMOTIONAL).effectiveTransition)
    }

    /** Runs only when a real song is supplied (REAL_SONG_WAV=mono 16-bit 44.1k wav, REAL_SONG_BPM=expected). */
    @Test fun detectsTempoOfRealSong() {
        val path = System.getenv("REAL_SONG_WAV"); val expected = System.getenv("REAL_SONG_BPM")?.toDoubleOrNull()
        assumeTrue(path != null && expected != null && File(path).exists())
        val bytes = File(path!!).readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val hop = 0.02; val per = (sr * hop).toInt()
        val env = ArrayList<Float>(); var acc = 0.0; var n = 0
        while (bb.remaining() >= 2) { val v = bb.short / 32768.0; acc += v * v; n++; if (n == per) { env += Math.sqrt(acc / n).toFloat(); acc = 0.0; n = 0 } }
        val t = Beat.detect(env.toFloatArray(), hop)!!
        println("detected ${t.bpm} BPM, expected $expected")
        assertTrue(listOf(0.5, 1.0, 2.0).any { abs(t.bpm / expected!! - it) < 0.04 })
    }
}

class ResamplerTest {
    @Test fun chunkedEqualsWhole() {
        val src = FloatArray(48000 * 2) { i -> kotlin.math.sin(i * 0.013).toFloat() }
        val whole = ArrayList<Float>()
        Resampler(2, 48000, 44100).feed(src, src.size) { l, r -> whole += l; whole += r }
        val chunked = ArrayList<Float>()
        val rs = Resampler(2, 48000, 44100)
        var i = 0
        while (i < src.size) { val n = minOf(1234 * 2, src.size - i); rs.feed(src.copyOfRange(i, i + n), n) { l, r -> chunked += l; chunked += r }; i += n }
        assertEquals(whole.size.toDouble(), chunked.size.toDouble(), 4.0)
        for (k in 0 until minOf(whole.size, chunked.size)) assertEquals(whole[k], chunked[k], 1e-4f)
    }
}
