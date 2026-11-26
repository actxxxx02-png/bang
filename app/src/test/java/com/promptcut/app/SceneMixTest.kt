package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

/** Replays the user's video: a long roadside ride first, then tree, water, fire, train at the end. */
class SceneMixTest {
    private val clips = listOf(
        ClipMeta(0, "ride.mp4", 60.0, 1080, 1920, true, recordedAt = 1),
        ClipMeta(1, "tree.mp4", 31.5, 1080, 1920, true, recordedAt = 2),
        ClipMeta(2, "water.mp4", 16.8, 1080, 1920, true, recordedAt = 3),
        ClipMeta(3, "fire.mp4", 12.0, 1080, 1920, true, recordedAt = 4),
        ClipMeta(4, "train.mp4", 10.0, 1080, 1920, true, recordedAt = 5),
    )
    private fun shots(vararg l: String) = listOf(Shot(0.0, 100.0, l.toList(), 0, false, 1))
    private val insights = mapOf(
        0 to ClipInsight(0, shots = shots("Tree", "Road", "Plant", "Sky")),
        1 to ClipInsight(1, shots = shots("Tree", "Plant", "Person")),
        2 to ClipInsight(2, shots = shots("Water", "Lake")),
        3 to ClipInsight(3, shots = shots("Fire", "Night")),
        4 to ClipInsight(4, shots = shots("Train", "Rail")),
    )
    // What happened: the ride takes 5 shots up front, the rest bunched at the end.
    private val plan = EditPlan("x", Aspect.PORTRAIT, null, listOf(
        Segment(0, 0.0, 3.0), Segment(0, 8.0, 11.0), Segment(0, 18.0, 21.0), Segment(0, 30.0, 33.0), Segment(0, 45.0, 48.0),
        Segment(1, 5.0, 8.0), Segment(2, 2.0, 5.0), Segment(3, 1.0, 4.0), Segment(4, 2.0, 5.0),
    ), mood = Mood.HAPPY)

    private val out = FilmRules.apply(plan, clips, insights, SpeedMode.NATURAL, SoundMode.VOICES, true, true, OrderMode.MIX).segments

    @Test fun opensOnTheMainPlace() {
        val names = listOf("ride", "tree", "water", "fire", "train")
        println("ORDER: " + out.joinToString(" → ") { "${names[it.clip]} ${round1(it.outputSec)}s" })
        assertEquals(0, out.first().clip)
    }

    @Test fun mainPlaceComesBackLater() {
        val rideAt = out.withIndex().filter { it.value.clip == 0 }.map { it.index }
        assertEquals(0, rideAt.first())
        assertTrue("ride should come back in the second half: $rideAt of ${out.size}", rideAt.last() >= out.size / 2)
    }

    @Test fun noSameSceneBackToBack() {
        out.zipWithNext().forEach { (a, b) -> assertNotEquals("adjacent: ${out.map { it.clip }}", a.clip, b.clip) }
    }

    @Test fun repetitiveRideGetsLessTime() {
        val ride = out.filter { it.clip == 0 }.sumOf { it.outputSec }
        val total = out.sumOf { it.outputSec }
        // other scenes get 3 s each; the ride may get at most 1.5× that (it was 15 s before)
        assertTrue("ride was 15 s of 27; now $ride of $total", ride <= 4.5 + 0.01)
    }

    @Test fun everySceneStays() = assertEquals(setOf(0, 1, 2, 3, 4), out.map { it.clip }.toSet())

    @Test fun similarClipsCountAsOneScene() {
        val twoRides = clips + ClipMeta(5, "ride2.mp4", 20.0, 1080, 1920, true, recordedAt = 6)
        val g = FilmRules.sceneGroups(twoRides, insights + (5 to ClipInsight(5, shots = shots("Tree", "Road", "Plant"))))
        assertEquals(g[0], g[5])
        assertNotEquals(g[0], g[2])
    }

    @Test fun singleMainShotGetsAReprise() {
        val p = plan.copy(segments = listOf(Segment(0, 0.0, 3.0), Segment(2, 2.0, 5.0), Segment(3, 1.0, 4.0), Segment(4, 2.0, 5.0)))
        val o = FilmRules.apply(p, clips.filter { it.index != 1 }, insights, SpeedMode.NATURAL, SoundMode.VOICES, true, false, OrderMode.MIX).segments
        assertEquals(2, o.count { it.clip == 0 })
        assertEquals(0, o.first().clip)
        assertNotEquals(0, o[1].clip)
    }
}
