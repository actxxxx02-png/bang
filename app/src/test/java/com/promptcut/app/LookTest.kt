package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

class LookTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(
            m[0] * r + m[4] * g + m[8] * b + m[12],
            m[1] * r + m[5] * g + m[9] * b + m[13],
            m[2] * r + m[6] * g + m[10] * b + m[14],
        )

    @Test fun greyStaysGrey() {
        val m = SaturationMatrix(1.28f).getMatrix(0, false)
        val out = apply(m, 0.5f, 0.5f, 0.5f)
        out.forEach { assertEquals(0.5f, it, 1e-5f) }
        // slightly noisy grey (like a road) must stay nearly grey, not turn purple
        val n = apply(m, 0.50f, 0.49f, 0.51f)
        assertTrue(n.max() - n.min() < 0.03f)
    }

    @Test fun colourGetsRicher() {
        val m = SaturationMatrix(1.28f).getMatrix(0, false)
        val o = apply(m, 0.2f, 0.6f, 0.2f) // green leaf
        assertTrue(o[1] - o[0] > 0.4f)      // more separation than the 0.4 we started with
    }

    @Test fun dullShotsAreMarked() {
        val meta = ClipMeta(0, "road.mp4", 10.0, 1080, 1920, false)
        val ins = ClipInsight(0, shots = listOf(Shot(0.0, 5.0, listOf("Road", "Asphalt"), 0, false, interest = 0),
            Shot(5.0, 10.0, listOf("Tree", "Sky"), 0, false, interest = 1)))
        val d = ins.describe(meta)
        assertTrue(d, "interest LOW" in d)
    }
}
