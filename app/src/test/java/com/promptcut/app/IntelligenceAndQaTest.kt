package com.promptcut.app

import com.promptcut.app.intelligence.GamingEventType
import com.promptcut.app.intelligence.GamingSignals
import com.promptcut.app.intelligence.StoryAct
import com.promptcut.app.intelligence.StoryAnalyzer
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.VideoTrack
import com.promptcut.app.timeline.CaptionTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceAndQaTest {

    @Test
    fun gamingSignals_combinesOcrScoreDeltaMotionAudioAndFace() {
        val (events, score) = GamingSignals.detectFromFrameSignals(
            timeSec = 14.5,
            ocrText = "HEADSHOT! ELIMINATED PlayerOne +100 SCORE: 4200 VICTORY",
            prevScore = 4100,
            motion = 35.0,
            lumaDelta = 45.0,
            audioLevel = 0.85,
            hasFace = true,
            smileProb = 0.88f,
        )
        assertEquals(4200, score)
        val types = events.map { it.type }.toSet()
        assertTrue(GamingEventType.HEADSHOT in types)
        assertTrue(GamingEventType.KILL_FEED in types)
        assertTrue(GamingEventType.VICTORY in types)
        assertTrue(GamingEventType.SCORE_CHANGE in types)
        assertTrue(GamingEventType.EXPLOSION in types)
        assertTrue(GamingEventType.PLAYER_REACTION in types)
    }

    @Test
    fun storyAnalyzer_mapsSpeechToTrimmedTimelineAndFindsSilences() {
        val clips = listOf(ClipMeta(0, "vlog.mp4", 10.0, 1080, 1920, true))
        val insights = mapOf(
            0 to ClipInsight(
                clip = 0,
                shots = listOf(
                    Shot(0.0, 3.0, listOf("indoor"), 1, false, interest = 1),
                    Shot(3.0, 7.0, listOf("outdoor", "action"), 1, true, interest = 2),
                ),
                speech = listOf(
                    Span(0.5, 2.0, "Too early"),
                    Span(3.5, 5.5, "Welcome to the highlight tour"),
                ),
            )
        )
        val beats = StoryAnalyzer.analyzeStoryArc(clips, insights)
        assertTrue(beats.any { it.act == StoryAct.HOOK })

        val project = TimelineProject(
            videoTracks = listOf(
                VideoTrack(
                    items = listOf(
                        VideoClipItem(
                            id = "v0",
                            sourceClipIndex = 0,
                            timelineStartUs = 0L,
                            sourceStartUs = 3_000_000L,
                            sourceEndUs = 7_000_000L,
                            speed = 2.0,
                        )
                    )
                )
            )
        )
        val captions = StoryAnalyzer.generateCaptionsForTimeline(project, insights, CaptionStyle.SUBTITLE_BAR)
        assertEquals(1, captions.size)
        assertEquals("Welcome to the highlight tour", captions.first().text)
        // At 2x speed, 3.5s..5.5s relative to sourceStart 3.0s -> 0.25s..1.25s on timeline
        assertEquals(250_000L, captions.first().startUs)
        assertEquals(1_250_000L, captions.first().endUs)

        val env = FloatArray(30) { idx -> if (idx in 10..22) 0.005f else 0.5f }
        val silences = StoryAnalyzer.silenceSpans(env, hopSec = 0.1, minSilenceSec = 0.7)
        assertEquals(1, silences.size)
        assertTrue(silences.first().length >= 1.0)
    }

    @Test
    fun qaMetricsAndChecks_detectClippingRepeatedScenesAndCaptionOverlap() {
        val clean = FloatArray(100) { 0.4f }
        assertFalse(QaMetrics.hasAudioClipping(clean))

        val clipped = FloatArray(100) { idx -> if (idx < 15) 0.995f else 0.5f }
        assertTrue(QaMetrics.hasAudioClipping(clipped))

        val segs = listOf(
            Segment(0, 1.0, 3.0, 1.0, "a", true),
            Segment(0, 1.1, 3.1, 1.0, "a dup", true),
            Segment(1, 0.0, 2.0, 1.0, "b", true),
        )
        assertEquals(1, QualityCheck.detectRepeatedAdjacentSegments(segs))

        val project = TimelineProject(
            captionTracks = listOf(
                CaptionTrack(
                    items = listOf(
                        CaptionItem(startUs = 0L, endUs = 2_000_000L, text = "One"),
                        CaptionItem(startUs = 1_500_000L, endUs = 3_000_000L, text = "Two"),
                    )
                )
            )
        )
        assertEquals(1, QualityCheck.detectCaptionOverlaps(project))
    }
}
