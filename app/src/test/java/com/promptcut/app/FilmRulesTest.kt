package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

class FilmRulesTest {
    // 5 clips like the user's: filmed in order 0..4 but given in a different order
    private val clips = listOf(
        ClipMeta(0, "a.mp4", 14.6, 1920, 1080, true, recordedAt = 3_000),
        ClipMeta(1, "b.mp4", 8.0, 1920, 1080, true, recordedAt = 1_000),
        ClipMeta(2, "c.mp4", 31.5, 1920, 1080, true, recordedAt = 2_000),
        ClipMeta(3, "d.mp4", 16.8, 1920, 1080, false, recordedAt = 4_000),
        ClipMeta(4, "e.mp4", 10.0, 1920, 1080, true, recordedAt = 5_000),
    )
    private val insights = mapOf(
        2 to ClipInsight(2,
            shots = listOf(Shot(0.0, 31.5, listOf("Tree"), 1, true, 2, focusX = 0.7)),
            speech = listOf(Span(12.0, 15.5, "look at me")),
            highlights = listOf(Moment(13.0, "smiling", 4.0))),
        4 to ClipInsight(4, highlights = listOf(Moment(7.0, "2 people", 3.0))),
    )
    // What the AI did: fast-paced, 2x and 4x, skipped clips 1 and 4, one 20 s shot.
    private val aiPlan = EditPlan("fast reel", Aspect.PORTRAIT, null, listOf(
        Segment(0, 2.0, 6.0, 4.0, "hook"),
        Segment(2, 5.0, 25.0, 2.0, "build"),
        Segment(3, 1.0, 5.0, 1.0, "ground"),
    ), mood = Mood.CALM)

    private fun run(speed: SpeedMode = SpeedMode.NATURAL, sound: SoundMode = SoundMode.VOICES, all: Boolean = true, order: OrderMode = OrderMode.FILMED) =
        FilmRules.apply(aiPlan, clips, insights, speed, sound, musicOn = true, useAllClips = all, order = order)

    @Test fun noSpeedChangesByDefault() {
        assertTrue(run().segments.all { it.speed == 1.0 })
        assertTrue(run(SpeedMode.DYNAMIC).segments.all { it.speed <= 2.0 })
        assertTrue(run(SpeedMode.SLOWMO).segments.all { it.speed in 0.5..1.0 })
    }

    @Test fun everyClipIsUsed() {
        assertEquals(setOf(0, 1, 2, 3, 4), run().segments.map { it.clip }.toSet())
        assertEquals(setOf(0, 2, 3), run(all = false).segments.map { it.clip }.toSet())
    }

    @Test fun missingClipUsesItsBestMoment() {
        val e = run().segments.first { it.clip == 4 }
        assertTrue(7.0 in e.start..e.end)
    }

    @Test fun longShotTrimmedAroundBestMomentWithoutCuttingSpeech() {
        val c = run().segments.first { it.clip == 2 }
        assertTrue("calm max 9 s, was ${c.end - c.start}", c.end - c.start <= 9.5)
        assertTrue("keeps the smile at 13 s", 13.0 in c.start..c.end)
        assertTrue("keeps the whole sentence", c.start <= 12.0 && c.end >= 15.5)
    }

    @Test fun filmingOrderWithHookFirst() {
        val order = run().segments.map { it.clip }
        assertEquals(0, order.first())                    // the hook stays first
        assertEquals(listOf(1, 2, 3, 4), order.drop(1))    // then filmed order: 1(1000) 2(2000) 3(4000) 4(5000)
    }

    @Test fun originalSoundOnlyWhereItMatters() {
        val segs = run().segments
        assertEquals(1.0, segs.first { it.clip == 2 }.soundGain, 0.0)   // speech inside
        assertEquals(0.0, segs.first { it.clip == 3 }.soundGain, 0.0)   // no audio track
        assertEquals(0.0, segs.first { it.clip == 1 }.soundGain, 0.0)   // nothing said: music only
        assertTrue(run(sound = SoundMode.MUTE).segments.all { it.soundGain == 0.0 })
        assertTrue(run(sound = SoundMode.FULL).segments.filter { it.clip != 3 }.all { it.soundGain > 0.5 })
    }

    @Test fun reframesOnPeople() {
        assertEquals(0.7, run().segments.first { it.clip == 2 }.focusX!!, 1e-9)
    }

    @Test fun chatRequestsForTheNewControls() {
        assertEquals(SpeedMode.NATURAL, QuickIntents.parse("don't speed it up, no 4x").speed)
        assertEquals(SoundMode.MUTE, QuickIntents.parse("remove the original sound, music only").sound)
        assertEquals(true, QuickIntents.parse("it skipped some of my videos, use all clips").useAll)
        assertEquals(SpeedMode.SLOWMO, QuickIntents.parse("add slow motion on the smile").speed)
    }
}
