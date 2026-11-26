package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

/** Replays the 0.7 crash report: one 33 s clip, "remove 10–13 s … crop only ipad". */
class ClipEditsTest {
    private val msg = "make a 20 seconds video. remove all frames from 10 sec to 13 sec and from last. crop only ipad"

    @Test fun understandsTheRangeCut() {
        val r = TimeCuts.parse(msg, mapOf(0 to 33.21))
        val c = r.cuts[0]!!
        assertEquals(1, c.size)
        assertEquals(10.0, c[0].start, 0.01); assertEquals(13.0, c[0].end, 0.01)
    }

    @Test fun lastSecondsAndMmSs() {
        val r = TimeCuts.parse("remove the last 3 seconds; cut 0:05 to 0:07", mapOf(0 to 30.0))
        val c = r.cuts[0]!!
        assertEquals(listOf(5.0 to 7.0, 27.0 to 30.0), c.map { it.start to it.end })
    }

    @Test fun keepOnly() {
        val c = TimeCuts.parse("keep only 5 to 20 s", mapOf(0 to 30.0)).cuts[0]!!
        assertEquals(listOf(0.0 to 5.0, 20.0 to 30.0), c.map { it.start to it.end })
    }

    @Test fun severalClipsNeedAClipNumber() {
        val d = mapOf(0 to 20.0, 1 to 20.0)
        assertTrue(TimeCuts.parse("remove 2 to 4 s", d).cuts.isEmpty())
        assertEquals(setOf(1), TimeCuts.parse("in clip 1 remove 2 to 4 s", d).cuts.keys)
    }

    @Test fun cropTarget() {
        assertEquals("ipad", TimeCuts.cropTarget(msg))
        assertEquals("cake", TimeCuts.cropTarget("Zoom in on the cake please"))
        assertNull(TimeCuts.cropTarget("make it calm"))
    }

    @Test fun applySplitsSegments() {
        val out = TimeCuts.apply(listOf(Segment(0, 5.0, 20.0)), mapOf(0 to listOf(Span(10.0, 13.0, ""))))
        assertEquals(listOf(5.0 to 10.0, 13.0 to 20.0), out.map { it.start to it.end })
    }

    @Test fun cropBoxFromGemini() {
        val b = CropBox.fromBox1000(listOf(200.0, 100.0, 800.0, 600.0))!!
        assertEquals(0.1, b.x0, 1e-9); assertEquals(0.2, b.y0, 1e-9); assertEquals(0.6, b.x1, 1e-9); assertEquals(0.8, b.y1, 1e-9)
        assertNull(CropBox.fromBox1000(listOf(0.0, 0.0, 1000.0, 1000.0)))
    }

    @Test fun singleClipMixDoesNotCrash() {
        val clips = listOf(ClipMeta(0, "ipad.mp4", 33.21, 1080, 1920, true))
        val plan = EditPlan("x", Aspect.PORTRAIT, null, listOf(
            Segment(0, 20.0, 23.0, why = "hook"), Segment(0, 0.0, 4.0), Segment(0, 5.0, 12.0), Segment(0, 14.0, 18.0), Segment(0, 26.0, 30.0),
        ), mood = Mood.CALM)
        val cuts = TimeCuts.parse(msg, mapOf(0 to 33.21)).cuts
        val out = FilmRules.apply(plan, clips, emptyMap(), SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.MIX, cuts).segments
        assertTrue(out.isNotEmpty())
        out.forEach { s -> assertTrue("cut part used: $s", s.end <= 10.0 + 1e-6 || s.start >= 13.0 - 1e-6) }
        // story stays in time order after the hook
        val rest = out.drop(1).map { it.start }
        assertEquals(rest.sorted(), rest)
    }

    // ---- 0.8.2: the video came out 9 s instead of 20 s

    @Test fun targetLengthFromWords() {
        assertEquals(20.0, TimeCuts.targetLength(msg)!!, 0.01)
        assertEquals(20.0, TimeCuts.targetLength("make a 20 second calm video. remove 10 to 13 sec")!!, 0.01)
        assertEquals(60.0, TimeCuts.targetLength("make it 1 minute")!!, 0.01)
        assertEquals(30.0, TimeCuts.targetLength("30s long please")!!, 0.01)
        assertNull(TimeCuts.targetLength("remove 10 sec to 13 sec"))
        assertNull(TimeCuts.targetLength("remove the last 3 seconds"))
    }

    @Test fun moodFromWords() {
        assertEquals(Mood.CALM, QuickIntents.parse("make a 20 second calm video").mood)
        assertEquals(Mood.ENERGETIC, QuickIntents.parse("not so calm, make it energetic").mood)
        assertNull(QuickIntents.parse("remove 10 to 13 sec").mood)
    }

    private val one = listOf(ClipMeta(0, "ipad.mp4", 33.21, 1080, 1920, true))
    private val cuts = TimeCuts.parse("remove 10 sec to 13 sec. remove the last 3 seconds", mapOf(0 to 33.21)).cuts

    @Test fun reachesTheAskedLengthWithoutCutParts() {
        // What the AI planned in the report: up to 10 s, then from 13 s "to reach the 20-second target".
        val plan = EditPlan("x", Aspect.PORTRAIT, null, listOf(Segment(0, 0.0, 10.0), Segment(0, 13.0, 23.0)), mood = Mood.HAPPY)
        for (target in listOf<Double?>(null, 20.0)) {
            val out = FilmRules.apply(plan, one, emptyMap(), SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.MIX, cuts, target).segments
            val total = out.sumOf { it.outputSec }
            println("target=$target → ${round1(total)} s: " + out.joinToString { "${it.start}-${it.end}" })
            if (target != null) assertEquals(20.0, total, 0.8)
            out.forEach { s ->
                assertTrue("uses removed 10-13: $s", s.end <= 10.0 + 1e-6 || s.start >= 13.0 - 1e-6)
                assertTrue("uses removed ending: $s", s.end <= 30.21 + 1e-6)
            }
        }
    }

    @Test fun addsFootageWhenTheAiPickedTooLittle() {
        val plan = EditPlan("x", Aspect.PORTRAIT, null, listOf(Segment(0, 4.0, 6.0)), mood = Mood.ENERGETIC)
        val out = FilmRules.apply(plan, one, emptyMap(), SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.MIX, cuts, 25.0).segments
        assertEquals(25.0, out.sumOf { it.outputSec }, 0.8)
        val starts = out.map { it.start }; assertEquals(starts.sorted(), starts)
        out.zipWithNext().forEach { (a, b) -> assertTrue("overlap $a $b", a.end <= b.start + 1e-6) }
    }

    @Test fun usesAllFootageWhenAskedForMoreThanThereIs() {
        val plan = EditPlan("x", Aspect.PORTRAIT, null, listOf(Segment(0, 0.0, 5.0)), mood = Mood.CALM)
        val out = FilmRules.apply(plan, one, emptyMap(), SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.MIX, cuts, 60.0).segments
        val total = out.sumOf { it.outputSec }
        assertTrue("usable is 27.2 s, got $total", total in 25.0..27.3)
    }

    @Test fun shortensWhenTooLong() {
        val clips = (0..3).map { ClipMeta(it, "c$it.mp4", 30.0, 1080, 1920, true) }
        val plan = EditPlan("x", Aspect.PORTRAIT, null, clips.map { Segment(it.index, 2.0, 11.0) }, mood = Mood.CALM)
        val out = FilmRules.apply(plan, clips, emptyMap(), SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.AI, emptyMap(), 15.0).segments
        assertEquals(15.0, out.sumOf { it.outputSec }, 0.8)
        assertEquals(4, out.size)
    }
}
