package com.promptcut.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE deletedAtMs IS NOT NULL ORDER BY deletedAtMs DESC")
    fun observeTrashed(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE isActiveSession = 1 AND deletedAtMs IS NULL ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun findActiveSession(): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET isActiveSession = 0")
    suspend fun clearAllActiveSessions()

    @Query("UPDATE projects SET isActiveSession = (id = :projectId) WHERE deletedAtMs IS NULL")
    suspend fun setActiveSession(projectId: String)

    @Query("UPDATE projects SET name = :name, updatedAtMs = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE projects SET deletedAtMs = :deletedAt, isActiveSession = 0 WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE projects SET deletedAtMs = NULL, updatedAtMs = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE projectId = :projectId ORDER BY clipIndex ASC")
    suspend fun forProject(projectId: String): List<MediaAssetEntity>

    @Upsert
    suspend fun upsertAll(assets: List<MediaAssetEntity>)

    @Query("DELETE FROM media_assets WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_entries WHERE projectId = :projectId ORDER BY createdAtMs ASC")
    suspend fun forProject(projectId: String): List<ConversationEntity>

    @Upsert
    suspend fun upsertAll(entries: List<ConversationEntity>)

    @Query("DELETE FROM conversation_entries WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface RenderJobDao {
    @Query("SELECT * FROM render_jobs ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<RenderJobEntity>>

    @Query("SELECT * FROM render_jobs WHERE status IN ('QUEUED', 'RENDERING', 'INTERRUPTED') ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun findRecoverable(): RenderJobEntity?

    @Upsert
    suspend fun upsert(job: RenderJobEntity)

    @Query("DELETE FROM render_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)
}
