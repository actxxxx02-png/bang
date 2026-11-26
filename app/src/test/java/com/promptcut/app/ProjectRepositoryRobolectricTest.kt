package com.promptcut.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.data.db.AppDatabase
import com.promptcut.app.data.db.ProjectRepository
import com.promptcut.app.render.RenderJobState
import com.promptcut.app.render.RenderJobStatus
import com.promptcut.app.render.RenderJobStore
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.VideoTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectRepositoryRobolectricTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProjectRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun lifecycle_createAutosaveRenameDuplicateSoftDeleteRestoreAndPurge() = runTest {
        val created = repo.createProject("My Gaming Cut")
        val clips = listOf(ClipMeta(0, "match.mp4", 15.0, 1080, 1920, true))
        val conv = ConversationManager().apply {
            projectInstructions = "Punchy 15s gaming highlight"
            addUserMessage("Cut boring parts")
        }
        val edited = created.copy(
            videoTracks = listOf(
                VideoTrack(items = listOf(VideoClipItem(sourceClipIndex = 0, timelineStartUs = 0L, sourceStartUs = 1_000_000L, sourceEndUs = 6_000_000L)))
            )
        )
        repo.autosaveProject(edited, clips, mapOf(0 to "content://match"), mapOf(0 to false), emptyMap(), conv)

        // Rename
        repo.renameProject(created.id, "Clutch Edit v2")
        val loaded = repo.loadProject(created.id).getOrNull()!!
        assertEquals("Clutch Edit v2", loaded.timeline.name)
        assertEquals(1, loaded.assets.size)

        // Duplicate
        val dup = repo.duplicateProject(created.id).getOrNull()!!
        assertTrue(dup.name.contains("Copy"))
        assertEquals(2, repo.observeActiveProjects().first().size)

        // Soft delete & restore
        repo.softDeleteProject(dup.id)
        assertEquals(1, repo.observeActiveProjects().first().size)
        assertEquals(1, repo.observeTrashedProjects().first().size)

        repo.restoreProject(dup.id)
        assertEquals(2, repo.observeActiveProjects().first().size)
        assertEquals(0, repo.observeTrashedProjects().first().size)

        // Permanent purge
        repo.permanentlyDeleteProject(dup.id)
        assertEquals(1, repo.observeActiveProjects().first().size)
    }

    @Test
    fun exportImportV2AndLegacyV1EditPlanNonDestructive() = runTest {
        val legacyPlan = EditPlan(
            summary = "Legacy vlog",
            aspect = Aspect.LANDSCAPE,
            title = "Roadtrip",
            segments = listOf(Segment(0, 0.0, 4.0, 1.0, "intro", true)),
            mood = Mood.CALM,
            color = ColorLook.WARM,
            musicOn = false,
        )
        val importedLegacy = repo.importProjectJson(legacyPlan.toJson()).getOrNull()!!
        assertEquals(1, importedLegacy.mainVideoTrack.items.size)
        assertNotNull(importedLegacy.legacyPlanJson)

        val exportedV2 = repo.exportProjectJson(importedLegacy.id).getOrNull()!!
        assertTrue(exportedV2.contains("promptcut_project_v2"))

        val reimported = repo.importProjectJson(exportedV2).getOrNull()!!
        assertEquals(Aspect.LANDSCAPE, reimported.aspect)
        assertEquals("Roadtrip", reimported.titleOverlay)
    }

    @Test
    fun recovery_findsInterruptedSessionAndInterruptedRenderJob() = runTest {
        val p = repo.createProject("Crash Recovery Edit")
        assertEquals(p.id, repo.findInterruptedSession()?.id)

        val store = RenderJobStore(ctx)
        store.save(
            RenderJobState(
                jobId = "job_1",
                projectId = p.id,
                projectName = p.name,
                status = RenderJobStatus.RENDERING,
                progress = 55,
                message = "Exporting...",
            )
        )
        val interrupted = store.markInterruptedIfRunning()
        assertNotNull(interrupted)
        assertEquals(RenderJobStatus.INTERRUPTED, interrupted?.status)
        repo.recordRenderJob(interrupted!!)
        assertEquals("job_1", repo.findInterruptedRenderJob()?.jobId)

        store.clear()
        assertNull(store.load())
    }
}
