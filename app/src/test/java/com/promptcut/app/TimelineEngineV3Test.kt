package com.promptcut.app

import com.promptcut.app.timeline.AudioClipItem
import com.promptcut.app.timeline.AudioTrack
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionAnimation
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.CaptionTrack
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.OverlayBounds
import com.promptcut.app.timeline.SpeedRampPoint
import com.promptcut.app.timeline.TimelineCodec
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.VideoTrack
import com.promptcut.app.timeline.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineV3Test {

    private fun sampleClip(id: String = "v1", startUs: Long = 0L, srcEndUs: Long = 4_000_000L) = VideoClipItem(
        id = id,
        sourceClipIndex = 0,
        sourceUri = "file:///sample.mp4",
        timelineStartUs = startUs,
        sourceStartUs = 0L,
        sourceEndUs = srcEndUs,
    )

    @Test
    fun trackLocking_blocksVideoAudioAndCaptionMutationsUntilUnlocked() {
        val initial = TimelineProject(
            videoTracks = listOf(VideoTrack(items = listOf(sampleClip("v1")))),
            audioTracks = listOf(AudioTrack(id = "audio_sfx", name = "A3 · SFX", type = AudioTrackType.SFX)),
            captionTracks = listOf(CaptionTrack()),
        )
        val engine = TimelineEngine(initial)

        // Lock video_main, audio_sfx, captions_main
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("video_main")).isSuccess)
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("audio_sfx")).isSuccess)
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("captions_main")).isSuccess)
        assertTrue(engine.project.mainVideoTrack.locked)
        assertTrue(engine.project.audioTracks.first { it.id == "audio_sfx" }.locked)
        assertTrue(engine.project.mainCaptionTrack.locked)

        // Mutations fail cleanly while locked
        assertTrue(engine.execute(TimelineCommand.SplitVideoClip("v1", 2_000_000L)).isFailure)
        assertTrue(engine.execute(TimelineCommand.TrimVideoClip("v1", 500_000L, 3_500_000L)).isFailure)
        assertTrue(engine.execute(TimelineCommand.SetClipEffects("v1", EffectSettings(glow = 0.4f))).isFailure)
        assertTrue(
            engine.execute(
                TimelineCommand.AddAudioItem(
                    AudioClipItem(title = "Whoosh", sourceUri = "sfx://whoosh", trackType = AudioTrackType.SFX, timelineStartUs = 500_000L, durationUs = 550_000L),
                    trackId = "audio_sfx",
                )
            ).isFailure
        )
        assertTrue(
            engine.execute(
                TimelineCommand.ReplaceCaptions(
                    listOf(CaptionItem(startUs = 0L, endUs = 1_500_000L, text = "Locked test"))
                )
            ).isFailure
        )

        // Unlock all three and verify mutations succeed
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("video_main")).isSuccess)
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("audio_sfx")).isSuccess)
        assertTrue(engine.execute(TimelineCommand.ToggleTrackLock("captions_main")).isSuccess)

        assertTrue(engine.execute(TimelineCommand.SetClipEffects("v1", EffectSettings(glow = 0.4f))).isSuccess)
        assertTrue(engine.execute(TimelineCommand.SplitVideoClip("v1", 2_000_000L)).isSuccess)
        assertTrue(
            engine.execute(
                TimelineCommand.AddAudioItem(
                    AudioClipItem(title = "Whoosh", sourceUri = "sfx://whoosh", trackType = AudioTrackType.SFX, timelineStartUs = 500_000L, durationUs = 550_000L),
                    trackId = "audio_sfx",
                )
            ).isSuccess
        )
        assertTrue(
            engine.execute(
                TimelineCommand.ReplaceCaptions(
                    listOf(CaptionItem(startUs = 0L, endUs = 1_500_000L, text = "Unlocked test"))
                )
            ).isSuccess
        )
        assertEquals(2, engine.project.mainVideoTrack.items.size)
        assertEquals(1, engine.project.audioTracks.first { it.id == "audio_sfx" }.items.size)
        assertEquals(1, engine.project.mainCaptionTrack.items.size)
    }

    @Test
    fun v3Commands_speedRampEffectsOverlayAudioMixBeatsAspectAndHistoryWork() {
        val engine = TimelineEngine(
            TimelineProject(
                videoTracks = listOf(VideoTrack(items = listOf(sampleClip("v1")))),
            )
        )

        // Speed ramp
        val ramp = listOf(
            SpeedRampPoint(0f, 1.0),
            SpeedRampPoint(0.5f, 2.0),
            SpeedRampPoint(1.0f, 1.0),
        )
        assertTrue(engine.execute(TimelineCommand.SetClipSpeedRamp("v1", ramp)).isSuccess)
        val clipAfterRamp = engine.project.mainVideoTrack.items.first()
        assertEquals(3, clipAfterRamp.speedRamp.size)
        assertEquals((1.0 + 2.0 + 1.0) / 3.0, clipAfterRamp.effectiveSpeed, 0.001)

        // Shader effects + clamping
        assertTrue(
            engine.execute(
                TimelineCommand.SetClipEffects(
                    "v1",
                    EffectSettings(brightness = 0.2f, vignette = 0.6f, filmGrain = 0.35f, glow = 0.9f, sharpen = 0.4f, shake = 0.25f)
                )
            ).isSuccess
        )
        assertTrue(engine.project.mainVideoTrack.items.first().effects.hasActiveAdjustments)

        // V2 Overlay clip + bounds update
        val overlay = VideoClipItem(
            id = "ov1",
            sourceClipIndex = 1,
            sourceUri = "file:///pip.mp4",
            timelineStartUs = 500_000L,
            sourceStartUs = 0L,
            sourceEndUs = 2_000_000L,
        )
        assertTrue(engine.execute(TimelineCommand.AddVideoClip(overlay, trackId = "video_overlay")).isSuccess)
        assertNotNull(engine.project.overlayVideoTrack)
        assertTrue(
            engine.execute(
                TimelineCommand.SetOverlayBounds("ov1", OverlayBounds(x = 0.1f, y = 0.12f, widthFraction = 0.4f, opacity = 0.85f))
            ).isSuccess
        )
        assertEquals(0.4f, engine.project.overlayVideoTrack!!.items.first().overlayBounds!!.widthFraction, 0.001f)
        assertEquals(1, engine.project.activeOverlaysAt(1_000_000L).size)

        // Audio track voice enhance + loudness target
        assertTrue(
            engine.execute(
                TimelineCommand.SetAudioTrackMix(
                    trackId = "audio_vo",
                    volume = 1.15f,
                    muted = false,
                    voiceEnhance = true,
                    normalizeTargetDb = -14f,
                )
            ).isSuccess
        )
        val voTrack = engine.project.audioTracks.first { it.id == "audio_vo" }
        assertTrue(voTrack.voiceEnhance)
        assertEquals(-14f, voTrack.normalizeTargetDb!!, 0.01f)

        // Beat markers
        assertTrue(engine.execute(TimelineCommand.SetBeatMarkers(listOf(500_000L, 1_500_000L, 500_000L))).isSuccess)
        assertEquals(listOf(500_000L, 1_500_000L), engine.project.beatMarkersUs)

        // Aspect 4:5 and Custom
        assertTrue(engine.execute(TimelineCommand.SetProjectStyle(aspect = Aspect.PORTRAIT_4_5)).isSuccess)
        assertEquals(1080 to 1350, engine.project.resolvedDimensions(1080))
        assertTrue(
            engine.execute(
                TimelineCommand.SetProjectStyle(aspect = Aspect.CUSTOM, customAspectWidth = 2100, customAspectHeight = 900)
            ).isSuccess
        )
        assertEquals(Aspect.CUSTOM, engine.project.aspect)
        assertTrue(engine.project.resolvedAspectRatio() > 2.0f)

        // History entries reflect executed commands newest-first
        val history = engine.historyEntries()
        assertFalse(history.isEmpty())
        assertEquals("Update style", history.first())
    }

    @Test
    fun timelineCodec_roundTripsV3AndRemainsCompatibleWithV1AndV2() {
        val projectV3 = TimelineProject(
            id = "proj_v3",
            name = "Studio V3 Project",
            aspect = Aspect.CUSTOM,
            customAspectWidth = 1080,
            customAspectHeight = 1350,
            fps = 60,
            resolution = ExportResolution.P1440,
            videoBitrateBps = 18_000_000,
            audioBitrateBps = 256_000,
            preferredHevc = true,
            beatMarkersUs = listOf(400_000L, 800_000L),
            videoTracks = listOf(
                VideoTrack(
                    id = "video_main",
                    locked = true,
                    items = listOf(
                        sampleClip("v1").copy(
                            speedRamp = listOf(SpeedRampPoint(0f, 1.0), SpeedRampPoint(1f, 2.0)),
                            effects = EffectSettings(vignette = 0.5f, filmGrain = 0.25f, glow = 0.3f, sharpen = 0.4f, shake = 0.1f),
                        )
                    ),
                ),
                VideoTrack(
                    id = "video_overlay",
                    name = "Video 2 (Overlay)",
                    isMain = false,
                    items = listOf(
                        sampleClip("ov1", startUs = 200_000L, srcEndUs = 2_000_000L)
                            .copy(overlayBounds = OverlayBounds(x = 0.5f, y = 0.1f, widthFraction = 0.35f, opacity = 0.9f))
                    ),
                ),
            ),
            audioTracks = listOf(
                AudioTrack(
                    id = "audio_vo",
                    name = "A2 · Voiceover",
                    type = AudioTrackType.VOICEOVER,
                    voiceEnhance = true,
                    normalizeTargetDb = -14f,
                    items = listOf(
                        AudioClipItem(id = "a1", title = "VO", sourceUri = "file:///vo.wav", trackType = AudioTrackType.VOICEOVER, timelineStartUs = 0L, durationUs = 2_000_000L)
                    ),
                ),
                AudioTrack(
                    id = "audio_sfx",
                    name = "A3 · SFX",
                    type = AudioTrackType.SFX,
                    items = listOf(
                        AudioClipItem(id = "sfx1", title = "Impact", sourceUri = "sfx://impact", trackType = AudioTrackType.SFX, timelineStartUs = 500_000L, durationUs = 700_000L)
                    ),
                ),
            ),
            captionTracks = listOf(
                CaptionTrack(
                    items = listOf(
                        CaptionItem(
                            id = "c1",
                            startUs = 0L,
                            endUs = 1_500_000L,
                            text = "CLUTCH victory!",
                            style = CaptionStyle.BOLD_BOX,
                            font = CaptionFont.CONDENSED,
                            fontSizeSp = 22,
                            highlightKeywords = listOf("CLUTCH"),
                            wordTimings = listOf(
                                WordTiming("CLUTCH", 0L, 700_000L),
                                WordTiming("victory!", 700_000L, 1_500_000L),
                            ),
                            animation = CaptionAnimation.POP,
                        )
                    )
                )
            ),
        )

        val json = TimelineCodec.toJson(projectV3)
        val decoded = TimelineCodec.fromJson(json).getOrNull()!!
        assertEquals(3, decoded.schemaVersion)
        assertEquals(Aspect.CUSTOM, decoded.aspect)
        assertEquals(1080, decoded.customAspectWidth)
        assertEquals(1350, decoded.customAspectHeight)
        assertEquals(ExportResolution.P1440, decoded.resolution)
        assertEquals(256_000, decoded.audioBitrateBps)
        assertTrue(decoded.preferredHevc)
        assertEquals(listOf(400_000L, 800_000L), decoded.beatMarkersUs)
        assertTrue(decoded.mainVideoTrack.locked)
        assertEquals(2, decoded.mainVideoTrack.items.first().speedRamp.size)
        assertEquals(0.5f, decoded.mainVideoTrack.items.first().effects.vignette, 0.001f)
        assertEquals(0.35f, decoded.overlayVideoTrack!!.items.first().overlayBounds!!.widthFraction, 0.001f)
        assertTrue(decoded.audioTracks.first { it.id == "audio_vo" }.voiceEnhance)
        assertEquals(1, decoded.audioTracks.first { it.id == "audio_sfx" }.items.size)
        assertEquals(CaptionAnimation.POP, decoded.mainCaptionTrack.items.first().animation)
        assertEquals(listOf("CLUTCH"), decoded.mainCaptionTrack.items.first().highlightKeywords)
        assertEquals(2, decoded.mainCaptionTrack.items.first().wordTimings.size)

        // Legacy V1 EditPlan JSON migration
        val v1PlanJson = EditPlan(
            summary = "V1 highlight",
            aspect = Aspect.PORTRAIT,
            title = "Retro",
            segments = listOf(Segment(clip = 0, start = 0.0, end = 3.0, why = "open")),
        ).toJson()
        val migratedV1 = TimelineCodec.fromJson(v1PlanJson).getOrNull()!!
        assertEquals(1, migratedV1.mainVideoTrack.items.size)
        assertNotNull(migratedV1.legacyPlanJson)

        // Legacy V2 minimal project JSON (missing V3 optional fields)
        val v2Json = """
            {
              "id": "v2_id",
              "schemaVersion": 2,
              "name": "V2 Project",
              "aspect": "9:16",
              "videoTracks": [
                { "id": "video_main", "name": "Video 1", "isMain": true, "items": [
                  { "id": "s1", "sourceClipIndex": 0, "timelineStartUs": 0, "sourceStartUs": 0, "sourceEndUs": 3000000 }
                ] }
              ]
            }
        """.trimIndent()
        val migratedV2 = TimelineCodec.fromJson(v2Json).getOrNull()!!
        assertEquals("V2 Project", migratedV2.name)
        assertEquals(192_000, migratedV2.audioBitrateBps)
        assertFalse(migratedV2.preferredHevc)
        assertFalse(migratedV2.mainVideoTrack.items.first().effects.hasActiveAdjustments)
    }
}
