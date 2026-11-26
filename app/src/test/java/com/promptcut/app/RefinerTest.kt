package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class RefinerTest {
    private val dur = mapOf(0 to 20.0, 1 to 10.0)

    private fun plan(vararg s: Segment) = EditPlan("x", Aspect.PORTRAIT, null, s.toList())

    @Test fun snapsToShotChange() {
        val ins = ClipInsight(0, shots = listOf(Shot(0.0, 5.0, emptyList(), 0, false), Shot(5.0, 20.0, emptyList(), 0, false)))
        val p = CutRefiner.refine(plan(Segment(0, 1.0, 5.4)), dur, mapOf(0 to ins), null)
        assertEquals(5.0, p.segments[0].end, 0.001)
    }

    @Test fun neverCutsInsideSpeech() {
        val ins = ClipInsight(0, speech = listOf(Span(3.0, 7.2, "happy birthday to you")))
        val p = CutRefiner.refine(plan(Segment(0, 3.8, 6.0)), dur, mapOf(0 to ins), null)
        assertEquals(3.0, p.segments[0].start, 0.001)   // pulled back to start of the sentence
        assertTrue(p.segments[0].end >= 7.2)             // extended to finish it
    }

    @Test fun landsCutsOnTheBeat() {
        val grid = BeatGrid(0.5, 2) // cut every 1.0 s
        val p = CutRefiner.refine(plan(Segment(0, 0.0, 2.3), Segment(1, 1.0, 3.8), Segment(0, 10.0, 12.0)), dur, emptyMap(), grid)
        var t = 0.0
        p.segments.dropLast(1).forEach { s ->
            t += s.outputSec
            assertEquals("cut at $t should be on the beat", 0.0, abs(t - Math.round(t)), 0.02)
        }
    }

    @Test fun removesOverlapFromSameClip() {
        val p = CutRefiner.refine(plan(Segment(0, 1.0, 4.0), Segment(0, 3.5, 6.0)), dur, emptyMap(), null)
        assertEquals(4.0, p.segments[1].start, 0.001)
    }

    @Test fun describesClipForTextOnlyModels() {
        val meta = ClipMeta(0, "kid.mp4", 19.3, 1080, 1920, true)
        val ins = ClipInsight(0,
            shots = listOf(Shot(0.0, 19.3, listOf("Clothing", "Smile"), 1, true)),
            speech = listOf(Span(2.0, 4.0, "look at me")),
            highlights = listOf(Moment(3.5, "smiling, face", 4.0)))
        val d = ins.describe(meta)
        assertTrue(d, "1 person" in d && "smiling" in d && "look at me" in d && "best moments: 3.5 s" in d)
    }

    @Test fun detectsTempoOfGeneratedMusic() {
        for (mood in listOf(Mood.HAPPY, Mood.ENERGETIC)) {
            val f = File.createTempFile("music", ".wav")
            MusicSynth.render(mood, 20.0, f)
            val env = envelopeOf(f, 0.01)
            val tempo = Beat.detect(env, 0.01)!!
            val ratio = tempo.bpm / mood.bpm
            assertTrue("$mood detected ${tempo.bpm}", listOf(0.5, 1.0, 2.0).any { abs(ratio - it) < 0.03 })
            f.delete()
        }
    }

    @Test fun parsesSuggestions() {
        val s = PlanCodec.parseSuggestions("""{"understanding":"A boy posing","occasion":"birthday","presets":[
            {"name":"Cute reel","description":"bouncy","mood":"happy","length":20,"aspect":"9:16","color":"warm","instructions":"open on smile"}]}""")
        assertEquals("birthday", s.occasion)
        assertEquals(Mood.HAPPY, s.presets[0].mood)
        assertEquals(ColorLook.WARM, s.presets[0].color)
    }

    private fun envelopeOf(f: File, hop: Double): FloatArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val per = (MusicSynth.SR * hop).toInt()
        val out = ArrayList<Float>()
        var acc = 0.0; var n = 0
        while (bb.remaining() >= 4) {
            val m = (bb.short + bb.short) / 65536.0
            acc += m * m; n++
            if (n == per) { out += Math.sqrt(acc / n).toFloat(); acc = 0.0; n = 0 }
        }
        return out.toFloatArray()
    }
}

class MusicSamples {
    /** Writes a 20 s sample of every mood to build/music-samples (for listening). */
    @Test fun writeSamples() {
        val dir = File("build/music-samples").apply { mkdirs() }
        Mood.entries.forEach { MusicSynth.render(it, 20.0, File(dir, "${it.key}.wav")) }
        assertEquals(Mood.entries.size, dir.listFiles()!!.size)
    }
}
