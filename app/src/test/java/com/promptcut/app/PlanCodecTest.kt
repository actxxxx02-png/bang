package com.promptcut.app

import org.junit.Assert.*
import org.junit.Test

class PlanCodecTest {
    private val clips = listOf(
        ClipMeta(0, "beach.mp4", 12.0, 1080, 1920, true),
        ClipMeta(1, "sunset.mp4", 8.0, 1920, 1080, false),
    )

    @Test fun parsesValidPlan() {
        val p = PlanCodec.parse("""{"summary":"Beach reel","aspect":"9:16","remove_original_audio":false,"title":"Goa",
            "segments":[{"clip":0,"start":1,"end":5,"speed":1},{"clip":1,"start":0,"end":4,"speed":0.5}]}""", clips)
        assertEquals(2, p.segments.size)
        assertEquals(Aspect.PORTRAIT, p.aspect)
        assertEquals("Goa", p.title)
        assertEquals(4.0 + 8.0, p.totalSec, 0.001)
    }

    @Test fun stripsCodeFencesAndRepairsRanges() {
        val p = PlanCodec.parse("Here you go:\n```json\n{\"aspect\":\"16:9\",\"segments\":[{\"clip\":0,\"start\":-3,\"end\":99,\"speed\":10}]}\n```", clips)
        val s = p.segments.single()
        assertEquals(0.0, s.start, 0.0); assertEquals(12.0, s.end, 0.0); assertEquals(4.0, s.speed, 0.0)
        assertEquals(Aspect.LANDSCAPE, p.aspect)
        assertNull(p.title)
    }

    @Test fun dropsUnknownClipsAndTinySegments() {
        val p = PlanCodec.parse("""{"segments":[{"clip":7,"start":0,"end":3},{"clip":1,"start":2,"end":2.1},{"clip":1,"start":2,"end":6}]}""", clips)
        assertEquals(listOf(Segment(1, 2.0, 6.0, 1.0)), p.segments)
    }

    @Test(expected = PlanException::class) fun rejectsNonJson() { PlanCodec.parse("sorry, I can't", clips) }

    @Test(expected = PlanException::class) fun rejectsEmptySegments() { PlanCodec.parse("""{"segments":[]}""", clips) }

    @Test fun roundTripsThroughJson() {
        val p = PlanCodec.parse("""{"summary":"x","aspect":"1:1","title":"Hi","segments":[{"clip":0,"start":1.234,"end":3.5}]}""", clips)
        val again = PlanCodec.parse(p.toJson(), clips)
        assertEquals(Aspect.SQUARE, again.aspect)
        assertEquals(1.23, again.segments[0].start, 0.001)
    }

    @Test fun picksNewestStableGeminiFlash() {
        val names = listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.0-flash-preview",
            "gemini-3.0-flash", "gemini-2.5-flash-preview-tts", "gemini-2.5-pro", "text-embedding-004")
        assertEquals("gemini-3.0-flash", ModelPicker.gemini(names))
    }

    @Test fun picksGroqChatModel() {
        assertEquals("openai/gpt-oss-120b", ModelPicker.groq(listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b")))
        assertEquals("llama-3.1-8b-instant", ModelPicker.groq(listOf("whisper-large-v3", "llama-3.1-8b-instant")))
    }

    @Test fun promptMentionsClipsAndCurrentPlan() {
        val plan = PlanCodec.parse("""{"segments":[{"clip":0,"start":0,"end":2}]}""", clips)
        val prompt = PlanCodec.buildUserPrompt(clips, "shorter", plan, hasFrames = true)
        assertTrue("clip 1: \"sunset.mp4\", 8.0 s" in prompt)
        assertTrue("CURRENT PLAN" in prompt)
        assertTrue("USER REQUEST: shorter" in prompt)
    }
}
