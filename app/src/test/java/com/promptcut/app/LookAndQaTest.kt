package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

class LookAndQaTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(
        m[0] * r + m[4] * g + m[8] * b + m[12],
        m[1] * r + m[5] * g + m[9] * b + m[13],
        m[2] * r + m[6] * g + m[10] * b + m[14],
    )

    @Test fun vividKeepsGreyGrey() {
        val m = SaturationMatrix(1.28f).getMatrix(0, false)
        apply(m, 0.5f, 0.5f, 0.5f).forEach { assertEquals(0.5f, it, 1e-5f) }
        val n = apply(m, 0.50f, 0.49f, 0.51f)          // noisy grey road
        assertTrue(n.max() - n.min() < 0.03f)
        val leaf = apply(m, 0.2f, 0.6f, 0.2f)
        assertTrue(leaf[1] - leaf[0] > 0.4f)           // real colour gets richer
    }

    @Test fun dullShotsAreMarked() {
        val meta = ClipMeta(0, "road.mp4", 10.0, 1080, 1920, false)
        val ins = ClipInsight(0, shots = listOf(Shot(0.0, 5.0, listOf("Road", "Asphalt"), 0, false, interest = 0),
            Shot(5.0, 10.0, listOf("Tree", "Sky"), 0, false, interest = 1)))
        assertTrue("interest LOW" in ins.describe(meta))
    }

    // Numbers measured on the user's real broken video vs. the source thumbnails.
    private fun cs(grey: Double, sat: Double, speckle: Double = 0.0, luma: Double = 120.0, hue: Double = 90.0) =
        ColorStats(luma, grey, sat, hue, speckle)

    @Test fun catchesTheRainbowRoad() {
        assertNotNull(QaMetrics.colourProblem(cs(0.82, 0.09), cs(0.19, 0.49), ColorLook.VIVID))
        assertNotNull(QaMetrics.colourProblem(cs(0.33, 0.29), cs(0.13, 0.51), ColorLook.VIVID))
    }

    @Test fun acceptsNormalLooks() {
        assertNull(QaMetrics.colourProblem(cs(0.33, 0.29), cs(0.28, 0.36), ColorLook.VIVID))  // mild boost is fine
        assertNull(QaMetrics.colourProblem(cs(0.40, 0.25), cs(0.40, 0.25, hue = 100.0), ColorLook.WARM))
        assertNull(QaMetrics.colourProblem(cs(0.10, 0.5), cs(1.0, 0.0), ColorLook.BW))
    }

    @Test fun catchesBlackShot() {
        assertEquals("black picture", QaMetrics.colourProblem(cs(0.3, 0.3), cs(0.3, 0.3, luma = 5.0), ColorLook.NATURAL))
    }

    @Test fun statsOnPixels() {
        val grey = IntArray(64) { 0xFF808080.toInt() }
        assertEquals(1.0, QaMetrics.stats(grey, 8, 8).greyFraction, 1e-9)
        // alternating red/cyan pixels = rainbow speckle
        val speck = IntArray(64) { if (it % 2 == 0) 0xFFFF2020.toInt() else 0xFF20FFFF.toInt() }
        assertTrue(QaMetrics.stats(speck, 8, 8).speckle > 0.9)
    }

    @Test fun qaNotesListIssues() {
        val p = EditPlan("x", Aspect.PORTRAIT, null, listOf(Segment(3, 0.0, 2.0)))
        val r = QaReport(emptyList(), linkedMapOf(QaIssue.DULL_OPENING to "the video opens on a dull shot"), dullSegments = listOf(0))
        val n = r.notesForAi(p)
        assertTrue("DULL_OPENING" in n && "clip 3" in n)
        assertEquals(listOf(QaIssue.DULL_OPENING), r.storyIssues)
    }
}
