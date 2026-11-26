package com.promptcut.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.data.db.AppDatabase
import com.promptcut.app.data.db.ProjectRepository
import com.promptcut.app.timeline.TimelineCodec
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.VideoTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectMigrationV2Test {

    @Test
    fun migration1To2_preservesExistingProjectsAndAssetsWithoutDataLoss() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(ctx.cacheDir, "migration_v1_to_v2_test.db")
        dbFile.delete()

        val v1Project = TimelineProject(
            id = "legacy_v1_proj",
            schemaVersion = 2,
            name = "Legacy Studio Cut",
            videoTracks = listOf(
                VideoTrack(
                    items = listOf(
                        VideoClipItem(
                            id = "seg_1",
                            sourceClipIndex = 0,
                            sourceUri = "file:///missing_clip.mp4",
                            timelineStartUs = 0L,
                            sourceStartUs = 0L,
                            sourceEndUs = 3_000_000L,
                        )
                    )
                )
            ),
        )
        val timelineJson = TimelineCodec.toJson(v1Project)

        // Create authentic Room v1 SQLite schema without V2 columns on projects
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `projects` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `timelineJson` TEXT NOT NULL,
                `legacyPlanJson` TEXT,
                `conversationJson` TEXT NOT NULL,
                `clipCutsJson` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `deletedAtMs` INTEGER,
                `isActiveSession` INTEGER NOT NULL,
                `lastOutputPath` TEXT,
                `durationSec` REAL NOT NULL,
                `clipCount` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_assets` (
                `id` TEXT NOT NULL,
                `projectId` TEXT NOT NULL,
                `clipIndex` INTEGER NOT NULL,
                `uriString` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `durationSec` REAL NOT NULL,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `hasAudio` INTEGER NOT NULL,
                `recordedAtMs` INTEGER NOT NULL,
                `insightSummary` TEXT NOT NULL,
                `isHdr` INTEGER NOT NULL,
                `cropJson` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_media_assets_projectId` ON `media_assets` (`projectId`)")
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_entries` (
                `id` TEXT NOT NULL,
                `projectId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_entries_projectId` ON `conversation_entries` (`projectId`)")
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `render_jobs` (
                `jobId` TEXT NOT NULL,
                `projectId` TEXT NOT NULL,
                `projectName` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `progress` INTEGER NOT NULL,
                `message` TEXT NOT NULL,
                `outputPath` TEXT,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`jobId`),
                FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS `index_render_jobs_projectId` ON `render_jobs` (`projectId`)")

        rawDb.execSQL(
            "INSERT INTO `projects` VALUES (?, ?, ?, ?, NULL, '', '', ?, ?, NULL, 1, NULL, 3.0, 1)",
            arrayOf<Any>("legacy_v1_proj", "Legacy Studio Cut", 2, timelineJson, 1000L, 2000L),
        )
        rawDb.execSQL(
            "INSERT INTO `media_assets` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
            arrayOf<Any>("legacy_v1_proj_clip_0", "legacy_v1_proj", 0, "file:///missing_clip.mp4", "missing_clip.mp4", 3.0, 1080, 1920, 1, 1000L, "Action shot", 0),
        )
        rawDb.version = 1
        rawDb.close()

        val migratedDb = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val repo = ProjectRepository(migratedDb)
            val loaded = repo.loadProject("legacy_v1_proj", ctx).getOrNull()!!
            assertEquals("Legacy Studio Cut", loaded.entity.name)
            assertEquals(1, loaded.assets.size)
            assertEquals(1, loaded.timeline.mainVideoTrack.items.size)
            assertNull(loaded.entity.thumbnailPath)
            assertEquals(1, loaded.entity.missingAssetsCount)
            assertEquals(listOf(0), loaded.missingAssetIndices)

            // Relink missing asset to an existing readable file and verify missing count clears to 0
            val replacementFile = File(ctx.cacheDir, "relinked_clip.mp4").apply {
                writeBytes(ByteArray(128) { 0x11 })
            }
            val relinked = repo.relinkAsset(
                context = ctx,
                projectId = "legacy_v1_proj",
                clipIndex = 0,
                newUriString = "file://${replacementFile.absolutePath}",
                newDisplayName = "relinked_clip.mp4",
            ).getOrNull()!!
            assertTrue(relinked.missingAssetIndices.isEmpty())
            assertEquals(0, relinked.entity.missingAssetsCount)
            assertEquals("file://${replacementFile.absolutePath}", relinked.timeline.mainVideoTrack.items.first().sourceUri)
            replacementFile.delete()
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }
}
