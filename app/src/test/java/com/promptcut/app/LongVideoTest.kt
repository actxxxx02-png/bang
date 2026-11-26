package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

class LongVideoTest {
    private val meta = ClipMeta(0, "wedding.mp4", 3600.0, 1920, 1080, true)
    private val shots = (0 until 360).map { i ->
        Shot(i * 10.0, i * 10.0 + 10, if (i % 30 == 0) listOf("Cake", "Party") else listOf("Room"), if (i % 7 == 0) 3 else 1, i % 30 == 0)
    }
    private val ins = ClipInsight(0, shots = shots,
        speech = listOf(Span(1205.0, 1212.0, "happy birthday to you"), Span(3000.0, 3004.0, "thank you all")),
        highlights = listOf(Moment(1206.0, "smiling, cake", 5.0), Moment(3001.0, "3 people", 3.0)))

    @Test fun compactOverviewIsShort() {
        val full = ins.describe(meta)
        val compact = ins.describe(meta, compact = true)
        assertTrue("compact ${compact.length} vs full ${full.length}", compact.length < full.length / 3)
        assertTrue("happy birthday" in compact)
    }

    @Test fun windowDescriptionOnlyCoversWindows() {
        val d = ins.describe(meta, windows = listOf(Span(1190.0, 1230.0)))
        assertTrue("happy birthday" in d)
        assertFalse("thank you all" in d)
        assertTrue(d.lines().count { it.trim().startsWith("shot") } <= 6)
    }

    @Test fun parsesAndMergesWindows() {
        val w = PlanCodec.parseWindows("""{"windows":[{"clip":0,"start":1200,"end":1250},{"clip":0,"start":1251,"end":1300},
            {"clip":0,"start":2990,"end":3010},{"clip":9,"start":0,"end":5}]}""", listOf(meta))
        assertEquals(2, w[0]!!.size)            // first two merged
        assertEquals(1198.5, w[0]!![0].start, 0.01) // padded
        assertNull(w[9])
    }

    @Test fun samplingScalesWithLength() {
        assertEquals(40, ClipUnderstanding.sampleCount(20.0))
        assertTrue(ClipUnderstanding.sampleCount(600.0) in 100..240)
        assertEquals(240, ClipUnderstanding.sampleCount(3600.0))
    }
}
