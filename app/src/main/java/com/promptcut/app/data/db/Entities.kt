package com.promptcut.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val timelineJson: String,
    val legacyPlanJson: String? = null,
    val conversationJson: String = "",
    val clipCutsJson: String = "",
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
    val isActiveSession: Boolean = false,
    val lastOutputPath: String? = null,
    val durationSec: Double = 0.0,
    val clipCount: Int = 0,
    val thumbnailPath: String? = null,
    val missingAssetsCount: Int = 0,
    val lastBackupAtMs: Long? = null,
    val lastExportDiagnosticsJson: String? = null,
)

@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId")],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val clipIndex: Int,
    val uriString: String,
    val displayName: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val recordedAtMs: Long,
    val insightSummary: String,
    val isHdr: Boolean,
    val cropJson: String? = null,
)

@Entity(
    tableName = "conversation_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val role: String,
    val content: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "render_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId")],
)
data class RenderJobEntity(
    @PrimaryKey val jobId: String,
    val projectId: String,
    val projectName: String,
    val status: String,
    val progress: Int,
    val message: String,
    val outputPath: String? = null,
    val updatedAtMs: Long,
)
