package com.promptcut.app

import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.CaptionTrack
import com.promptcut.app.timeline.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QualityControlV3Test {

    @Test
    fun captionReadability_detectsAndRepairsUnsafeSmallOrLowContrastCaptions() {
        val badProject = TimelineProject(
            captionTracks = listOf(
                CaptionTrack(
                    items = listOf(
                        CaptionItem(
                            id = "c_unsafe",
                            startUs = 0L,
                            endUs = 2_000_000L,
                            text = "clipped top caption",
                            positionYFraction = 0.04f,
                            fontSizeSp = 18,
                        ),
                        CaptionItem(
                            id = "c_tiny_minimal",
                            startUs = 2_000_000L,
                            endUs = 4_000_000L,
                            text = "tiny unreadable minimal caption",
                            style = CaptionStyle.MINIMAL,
                            fontSizeSp = 11,
                            strokeWidth = 0f,
                            shadowEnabled = false,
                        ),
                        CaptionItem(
                            id = "c_long",
                            startUs = 4_000_000L,
                            endUs = 7_000_000L,
                            text = "A".repeat(95),
                        ),
                    )
                )
            )
        )

        assertEquals(3, QualityCheck.detectCaptionReadabilityIssues(badProject))

        val repaired = QualityCheck.repairCaptionReadability(badProject)
        assertEquals(3, repaired.size)
        repaired.forEach { c ->
            assertTrue("Y must be inside safe area: ${c.positionYFraction}", c.positionYFraction in 0.12f..0.88f)
            assertTrue("Font size >= 16sp: ${c.fontSizeSp}", c.fontSizeSp >= 16)
            assertTrue("Shadow enabled for contrast", c.shadowEnabled)
        }
        val repairedMinimal = repaired.first { it.id == "c_tiny_minimal" }
        assertTrue(repairedMinimal.strokeWidth >= 2.0f)

        val fixedNonLongOnly = TimelineProject(
            captionTracks = listOf(CaptionTrack(items = repaired.take(2)))
        )
        assertEquals(0, QualityCheck.detectCaptionReadabilityIssues(fixedNonLongOnly))
    }

    @Test
    fun verifyExportedMp4_rejectsMissingAndCorruptFilesSafely() {
        val tempDir = java.nio.file.Files.createTempDirectory("mp4_verify_test").toFile()
        try {
            val missing = File(tempDir, "does_not_exist.mp4")
            val missingRes = Renderer.verifyExportedMp4(missing, expectAudio = true)
            assertFalse(missingRes.valid)
            assertFalse(missingRes.errorReason.isNullOrBlank())

            val tinyCorrupt = File(tempDir, "corrupt.mp4").apply {
                writeBytes(ByteArray(256) { 0x42 })
            }
            val corruptRes = Renderer.verifyExportedMp4(tinyCorrupt, expectAudio = false)
            assertFalse(corruptRes.valid)
            assertFalse(corruptRes.errorReason.isNullOrBlank())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
