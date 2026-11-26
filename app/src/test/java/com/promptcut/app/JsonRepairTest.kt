package com.promptcut.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonRepairTest {
    private val clips = listOf(ClipMeta(0, "a.mp4", 14.6, 1080, 1920, true), ClipMeta(2, "c.mp4", 31.5, 1080, 1920, true))

    @Test fun trailingCommasAndComments() {
        val raw = """{"summary":"x", // the story
            "segments":[{"clip":0,"start":1,"end":3,},{"clip":2,"start":4,"end":7},],}"""
        val p = PlanCodec.parse(raw, clips)
        assertEquals(2, p.segments.size)
    }

    @Test fun cutOffAnswerKeepsCompleteSegments() {
        val raw = """{"summary":"Upbeat reel","aspect":"9:16","segments":[{"clip":0,"start":1,"end":3,"why":"opening"},{"clip":2,"start":4,"end":7,"why":"build"},{"clip":2,"start":10,"en"""
        val p = PlanCodec.parse(raw, clips)
        assertEquals(2, p.segments.size)
        assertEquals("Upbeat reel", p.summary)
    }

    @Test fun fencedJson() {
        assertNotNull(JsonRepair.repairObject("Sure!\n```json\n{\"a\":1}\n```"))
    }

    @Test fun stringsWithSlashesSurvive() {
        val r = JsonRepair.repairObject("""{"why":"open // on the smile","x":1,}""")!!
        assertEquals("open // on the smile", JSONObject(r).getString("why"))
    }

    @Test fun readsGroqFailedGeneration() {
        val body = """{"error":{"message":"Failed to validate JSON. Please adjust your prompt. See 'failed_generation' for more details.",
            "type":"invalid_request_error","code":"json_validate_failed",
            "failed_generation":"{\"summary\":\"Reel\",\"segments\":[{\"clip\":0,\"start\":0,\"end\":2},]}"}}"""
        val attempt = JsonRepair.failedGeneration(body)!!
        val fixed = JsonRepair.repairObject(attempt)!!
        assertEquals(1, PlanCodec.parse(fixed, clips).segments.size)
    }

    @Test fun hopelessInputStillRejected() {
        assertNull(JsonRepair.repairObject("I can't help with that."))
    }
}
