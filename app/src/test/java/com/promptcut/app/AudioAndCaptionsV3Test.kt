package com.promptcut.app

import com.promptcut.app.audio.SfxSynth
import com.promptcut.app.audio.SfxType
import com.promptcut.app.audio.VoiceAndLoudnessDsp
import com.promptcut.app.intelligence.CaptionTools
import com.promptcut.app.timeline.CaptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioAndCaptionsV3Test {

    @Test
    fun sfxSynth_generatesValidStereoPcmAndWavForAllTypes() {
        val tempDir = Files.createTempDirectory("sfx_test").toFile()
        try {
            SfxType.entries.forEach { type ->
                val pcm = SfxSynth.synthesizePcm(type)
                assertTrue("PCM should not be empty for $type", pcm.size > 1000)
                assertEquals("PCM should be stereo pairs for $type", 0, pcm.size % 2)
                var maxAbs = 0f
                for (s in pcm) {
                    assertTrue("Sample must be finite", s.isFinite())
                    assertTrue("Sample in [-1, 1]", s in -1f..1f)
                    maxAbs = maxOf(maxAbs, abs(s))
                }
                assertTrue("Audio should not be silent for $type", maxAbs > 0.2f)

                val outWav = File(tempDir, "${type.key}.wav")
                SfxSynth.writeWav(type, outWav)
                assertTrue(outWav.exists())
                val bytes = outWav.readBytes()
                assertTrue(bytes.size > 44)
                assertEquals("RIFF", String(bytes.sliceArray(0..3)))
                assertEquals("WAVE", String(bytes.sliceArray(8..11)))
                val declaredDataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
                assertEquals(bytes.size - 44, declaredDataSize)
                assertEquals(pcm.size * 2, declaredDataSize)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun voiceAndLoudnessDsp_reducesRumbleAndNormalizesSafely() {
        val sr = 44100
        // Compare 50Hz handling rumble vs 2kHz speech tone with voiceEnhance = true
        val rumbleDsp = VoiceAndLoudnessDsp(sr, 1, voiceEnhance = true, normalizeTargetDb = null)
        val speechDsp = VoiceAndLoudnessDsp(sr, 1, voiceEnhance = true, normalizeTargetDb = null)

        var rumbleOutSq = 0.0
        var speechOutSq = 0.0
        val total = 8820 // 200ms
        val measureFrom = 4410
        for (i in 0 until total) {
            val rIn = (0.5 * sin(2.0 * PI * 50.0 * i / sr)).toFloat()
            val sIn = (0.5 * sin(2.0 * PI * 2000.0 * i / sr)).toFloat()
            val rOut = rumbleDsp.processSample(0, rIn)
            val sOut = speechDsp.processSample(0, sIn)
            if (i >= measureFrom) {
                rumbleOutSq += rOut * rOut
                speechOutSq += sOut * sOut
            }
        }
        val rumbleRms = sqrt(rumbleOutSq / (total - measureFrom))
        val speechRms = sqrt(speechOutSq / (total - measureFrom))
        assertTrue("Expected speech presence ($speechRms) > rumble ($rumbleRms)", speechRms > rumbleRms * 2.5)

        // Quiet signal normalization boosts level upward while capping peaks <= 1.0
        val normDsp = VoiceAndLoudnessDsp(sr, 1, voiceEnhance = false, normalizeTargetDb = -14f)
        var maxSample = 0f
        var outEnergy = 0.0
        for (i in 0 until 10_000) {
            val quietIn = (0.04 * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
            val out = normDsp.processSample(0, quietIn)
            assertTrue("Soft limiter prevents clipping: $out", abs(out) <= 1.0f)
            maxSample = maxOf(maxSample, abs(out))
            if (i >= 5000) outEnergy += out * out
        }
        assertTrue("Quiet input should be boosted above 0.08 peak (got $maxSample)", maxSample > 0.08f)
        assertTrue(outEnergy > 0.0)
    }

    @Test
    fun captionTools_formatsPunctuationTimingsSafeAreaSrtAndVtt() {
        assertEquals("How do I win this?", CaptionTools.autoPunctuate("how do i win this"))
        assertEquals("I'm ready to clutch.", CaptionTools.autoPunctuate("i'm ready to clutch"))

        val keywords = CaptionTools.extractKeywords("Amazing CLUTCH shot on round 5!")
        assertTrue(keywords.contains("Amazing") || keywords.contains("CLUTCH"))

        val timings = CaptionTools.interpolateWordTimings("Hold my beer right now", 2_000_000L)
        assertEquals(5, timings.size)
        assertEquals(0L, timings.first().startOffsetUs)
        assertEquals(2_000_000L, timings.last().endOffsetUs)
        for (i in 0 until timings.lastIndex) {
            assertEquals(timings[i].endOffsetUs, timings[i + 1].startOffsetUs)
        }

        assertEquals(0.12f, CaptionTools.clampSafeAreaY(0.01f), 0.001f)
        assertEquals(0.88f, CaptionTools.clampSafeAreaY(0.99f), 0.001f)

        val captions = listOf(
            CaptionItem(id = "c1", startUs = 0L, endUs = 1_500_000L, text = "First line"),
            CaptionItem(id = "c2", startUs = 2_000_000L, endUs = 3_250_000L, text = "Second line"),
        )
        val srt = CaptionTools.exportSrt(captions)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:01,500"))
        assertTrue(srt.contains("00:00:02,000 --> 00:00:03,250"))

        val vtt = CaptionTools.exportVtt(captions)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:01.500"))
    }
}
