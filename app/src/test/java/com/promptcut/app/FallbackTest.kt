package com.promptcut.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FallbackTest {
    private val ranked = suspend { listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash") }

    @Test fun fallsBackWhenModelOverloaded() = runBlocking {
        val calls = mutableListOf<String>()
        val (used, text) = tryModels("gemini-3.0-flash-preview", ranked, "Gemini") { m ->
            calls += m
            if (m == "gemini-3.0-flash-preview") throw ApiException("busy", 503) else "ok from $m"
        }
        assertEquals("gemini-2.5-flash", used)
        assertEquals("ok from gemini-2.5-flash", text)
        assertEquals(listOf("gemini-3.0-flash-preview", "gemini-3.0-flash-preview", "gemini-2.5-flash"), calls)
    }

    @Test fun retriesSameModelOnceBeforeSwitching() = runBlocking {
        var n = 0
        val (used, _) = tryModels("gemini-2.5-flash", ranked, "Gemini") { if (n++ == 0) throw ApiException("busy", 503) else "ok" }
        assertEquals("gemini-2.5-flash", used)
    }

    @Test fun badKeyFailsFastWithoutFallback() = runBlocking {
        var n = 0
        try {
            tryModels("gemini-2.5-flash", ranked, "Gemini") { n++; throw ApiException("bad key", 403) }
            fail()
        } catch (e: ApiException) { assertEquals(403, e.code) }
        assertEquals(1, n)
    }

    @Test fun givesClearMessageWhenAllBusy() = runBlocking {
        try {
            tryModels(null, ranked, "Gemini") { throw ApiException("busy", 503) }
            fail()
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("busy right now"))
            assertTrue(e.message!!.contains("gemini-2.5-flash-lite"))
        }
    }

    @Test fun ranksStableBeforePreview() {
        val r = ModelPicker.geminiRanked(listOf("gemini-3.0-flash-preview", "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-preview-tts"))
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.0-flash-preview"), r)
    }
}
