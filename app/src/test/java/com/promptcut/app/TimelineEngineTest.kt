package com.promptcut.app

import com.promptcut.app.timeline.AudioClipItem
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.TimelineCodec
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.secToUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {

    private fun sampleClip(index: Int = 0, id: String = "clip_0", durSec: Double = 6.0): VideoClipItem =
        VideoClipItem(
            id = id,
            sourceClipIndex = index,
            timelineStartUs = 0L,
            sourceStartUs = 0L,
            sourceEndUs = secToUs(durSec),
            why = "highlight",
        )

    @Test
    fun splitDuplicateReorderDeleteAndUndoRedo() {
        val engine = TimelineEngine(TimelineProject.empty("Test Edit"))
        assertTrue(engine.execute(TimelineCommand.AddVideoClip(sampleClip(0, "c0", 6.0))).isSuccess)
        assertEquals(6_000_000L, engine.project.videoDurationUs)

        // Split at 2.5s -> 2 clips (2.5s and 3.5s)
        assertTrue(engine.execute(TimelineCommand.SplitVideoClip("c0", 2_500_000L)).isSuccess)
        assertEquals(2, engine.project.mainVideoTrack.items.size)
        assertEquals(2_500_000L, engine.project.mainVideoTrack.items[0].timelineDurationUs)
        assertEquals(3_500_000L, engine.project.mainVideoTrack.items[1].timelineDurationUs)
        assertEquals(2_500_000L, engine.project.mainVideoTrack.items[1].timelineStartUs)

        // Duplicate first item -> 3 items
        assertTrue(engine.execute(TimelineCommand.DuplicateVideoClip("c0")).isSuccess)
        assertEquals(3, engine.project.mainVideoTrack.items.size)

        // Reorder last item to front
        val lastId = engine.project.mainVideoTrack.items.last().id
        assertTrue(engine.execute(TimelineCommand.ReorderVideoClips(2, 0)).isSuccess)
        assertEquals(lastId, engine.project.mainVideoTrack.items.first().id)

        // Undo back to before reorder
        assertTrue(engine.undo().isSuccess)
        assertEquals(lastId, engine.project.mainVideoTrack.items.last().id)

        // Redo reorder
        assertTrue(engine.redo().isSuccess)
        assertEquals(lastId, engine.project.mainVideoTrack.items.first().id)

        // Delete front item
        assertTrue(engine.execute(TimelineCommand.DeleteVideoClip(lastId)).isSuccess)
        assertEquals(2, engine.project.mainVideoTrack.items.size)
    }

    @Test
    fun keyframesCaptionsOverlapAndUndoHistoryLimit() {
        val engine = TimelineEngine(TimelineProject.empty("History Test"))
        engine.execute(TimelineCommand.AddVideoClip(sampleClip(0, "c0", 5.0)))

        // Keyframes on Scale property
        engine.execute(TimelineCommand.UpsertKeyframe("c0", Keyframe(1_000_000L, KeyframeProperty.SCALE, 1.25f)))
        assertEquals(1, engine.project.mainVideoTrack.items.first().keyframes.size)

        // Overlapping captions are automatically resolved cleanly
        engine.execute(
            TimelineCommand.ReplaceCaptions(
                listOf(
                    CaptionItem(id = "cap1", startUs = 0L, endUs = 2_000_000L, text = "First line", style = CaptionStyle.BOLD_BOX),
                    CaptionItem(id = "cap2", startUs = 1_200_000L, endUs = 3_000_000L, text = "Second line", style = CaptionStyle.BOLD_BOX),
                )
            )
        )
        val caps = engine.project.mainCaptionTrack.items
        assertEquals(2, caps.size)
        assertTrue(caps[1].startUs >= caps[0].endUs)

        // History caps cleanly at 60 steps without crashing
        repeat(70) { i ->
            engine.execute(TimelineCommand.SetClipSpeed("c0", 1.0 + (i % 5) * 0.1))
        }
        assertTrue(engine.historyDepth <= 60)
    }

    @Test
    fun codecRoundTripAndLegacyEditPlanMigration() {
        val clips = listOf(
            ClipMeta(0, "clip0.mp4", 8.0, 1080, 1920, true),
            ClipMeta(1, "clip1.mp4", 6.0, 1080, 1920, true),
        )
        val legacyPlan = EditPlan(
            summary = "Summer montage",
            aspect = Aspect.PORTRAIT,
            title = "Weekend",
            segments = listOf(
                Segment(0, 1.0, 4.0, 1.0, "opening smile", true),
                Segment(1, 0.5, 3.5, 1.5, "action finish", false),
            ),
            mood = Mood.ENERGETIC,
            color = ColorLook.VIVID,
            musicOn = true,
        )
        val v1Json = legacyPlan.toJson()
        val migrated = TimelineCodec.fromJson(v1Json, clips).getOrNull()!!
        assertEquals(2, migrated.mainVideoTrack.items.size)
        assertEquals(3_000_000L, migrated.mainVideoTrack.items[0].timelineDurationUs)
        assertEquals(2_000_000L, migrated.mainVideoTrack.items[1].timelineDurationUs) // 3s at 1.5x = 2s
        assertFalse(migrated.legacyPlanJson.isNullOrBlank())

        // Add music track & caption, serialize v2, parse back
        val withExtras = migrated.copy(
            audioTracks = migrated.audioTracks.map {
                it.copy(items = listOf(AudioClipItem(title = "Beat", sourceUri = "file:///beat.m4a", trackType = AudioTrackType.MUSIC, timelineStartUs = 0L, durationUs = 5_000_000L)))
            },
            captionTracks = migrated.captionTracks.map {
                it.copy(items = listOf(CaptionItem(startUs = 0L, endUs = 1_500_000L, text = "Let's go")))
            },
        )
        val v2Json = TimelineCodec.toJson(withExtras)
        val restored = TimelineCodec.fromJson(v2Json).getOrNull()!!
        assertEquals(2, restored.mainVideoTrack.items.size)
        assertEquals(1, restored.mainAudioTrack.items.size)
        assertEquals(1, restored.mainCaptionTrack.items.size)
        assertEquals("Weekend", restored.toEditPlan().title)
    }
}
