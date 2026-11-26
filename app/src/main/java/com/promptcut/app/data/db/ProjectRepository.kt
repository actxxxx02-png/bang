package com.promptcut.app.data.db

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.promptcut.app.ClipMeta
import com.promptcut.app.CropBox
import com.promptcut.app.Span
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.render.RenderJobState
import com.promptcut.app.timeline.TimelineCodec
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LoadedProject(
    val entity: ProjectEntity,
    val timeline: TimelineProject,
    val assets: List<MediaAssetEntity>,
    val conversationJson: String,
    val clipCuts: Map<Int, List<Span>>,
    val missingAssetIndices: List<Int> = emptyList(),
)

/**
 * Offline-first Room repository managing project autosave, thumbnails, missing-media detection
 * and relinking, lifecycle (create, rename, duplicate, soft delete & restore), import/export JSON,
 * legacy EditPlan migration, and session/render recovery.
 */
class ProjectRepository(private val db: AppDatabase) {

    fun observeActiveProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeActive()
    fun observeTrashedProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeTrashed()
    fun observeRenderJobs(): Flow<List<RenderJobEntity>> = db.renderJobDao().observeAll()

    suspend fun createProject(name: String = "Untitled Edit"): TimelineProject {
        val now = System.currentTimeMillis()
        val project = TimelineProject.empty(name).copy(createdAtMs = now, updatedAtMs = now)
        val entity = ProjectEntity(
            id = project.id,
            name = project.name,
            schemaVersion = project.schemaVersion,
            timelineJson = TimelineCodec.toJson(project),
            createdAtMs = now,
            updatedAtMs = now,
            isActiveSession = true,
        )
        db.projectDao().clearAllActiveSessions()
        db.projectDao().upsert(entity)
        return project
    }

    suspend fun autosaveProject(
        project: TimelineProject,
        clips: List<ClipMeta>,
        clipUris: Map<Int, String>,
        clipHdr: Map<Int, Boolean>,
        clipCuts: Map<Int, List<Span>>,
        conversation: ConversationManager,
        lastOutputPath: String? = null,
        thumbnailPath: String? = null,
        lastExportDiagnosticsJson: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val updated = project.copy(updatedAtMs = now)
        val existing = db.projectDao().findById(updated.id)
        val entity = ProjectEntity(
            id = updated.id,
            name = updated.name,
            schemaVersion = updated.schemaVersion,
            timelineJson = TimelineCodec.toJson(updated),
            legacyPlanJson = updated.legacyPlanJson ?: existing?.legacyPlanJson,
            conversationJson = conversation.toJson(),
            clipCutsJson = encodeCuts(clipCuts),
            createdAtMs = existing?.createdAtMs ?: updated.createdAtMs,
            updatedAtMs = now,
            deletedAtMs = existing?.deletedAtMs,
            isActiveSession = true,
            lastOutputPath = lastOutputPath ?: existing?.lastOutputPath,
            durationSec = usToSec(updated.totalDurationUs),
            clipCount = clips.size,
            thumbnailPath = thumbnailPath ?: existing?.thumbnailPath,
            missingAssetsCount = 0,
            lastBackupAtMs = existing?.lastBackupAtMs,
            lastExportDiagnosticsJson = lastExportDiagnosticsJson ?: existing?.lastExportDiagnosticsJson,
        )
        db.projectDao().clearAllActiveSessions()
        db.projectDao().upsert(entity)

        val assets = clips.map { m ->
            MediaAssetEntity(
                id = "${updated.id}_clip_${m.index}",
                projectId = updated.id,
                clipIndex = m.index,
                uriString = clipUris[m.index] ?: "",
                displayName = m.name,
                durationSec = m.durationSec,
                width = m.width,
                height = m.height,
                hasAudio = m.hasAudio,
                recordedAtMs = m.recordedAt,
                insightSummary = m.description,
                isHdr = clipHdr[m.index] ?: false,
                cropJson = m.crop?.let { "[${it.x0},${it.y0},${it.x1},${it.y1}]" },
            )
        }
        db.mediaAssetDao().deleteForProject(updated.id)
        if (assets.isNotEmpty()) db.mediaAssetDao().upsertAll(assets)

        val convEntries = conversation.messages.mapIndexed { idx, msg ->
            ConversationEntity(
                id = "${updated.id}_msg_$idx",
                projectId = updated.id,
                role = msg.role.name,
                content = msg.content,
                createdAtMs = now + idx,
            )
        }
        db.conversationDao().deleteForProject(updated.id)
        if (convEntries.isNotEmpty()) db.conversationDao().upsertAll(convEntries)
    }

    suspend fun loadProject(projectId: String, context: Context? = null): AppResult<LoadedProject> {
        val entity = db.projectDao().findById(projectId)
            ?: return AppResult.Failure(AppError.Storage("Project not found."))
        val assets = db.mediaAssetDao().forProject(projectId)
        val fallbackMetas = assets.map { a ->
            ClipMeta(
                index = a.clipIndex,
                name = a.displayName,
                durationSec = a.durationSec,
                width = a.width,
                height = a.height,
                hasAudio = a.hasAudio,
                recordedAt = a.recordedAtMs,
                description = a.insightSummary,
                crop = parseCropJson(a.cropJson),
            )
        }
        val timeline = when (val parsed = TimelineCodec.fromJson(entity.timelineJson, fallbackMetas)) {
            is AppResult.Success -> parsed.value.copy(
                id = entity.id,
                name = entity.name,
                legacyPlanJson = parsed.value.legacyPlanJson ?: entity.legacyPlanJson,
            )
            is AppResult.Failure -> return parsed
        }
        val missingIndices = if (context != null) findMissingAssets(context, assets) else emptyList()
        if (context != null && missingIndices.size != entity.missingAssetsCount) {
            db.projectDao().upsert(entity.copy(missingAssetsCount = missingIndices.size))
        }
        db.projectDao().clearAllActiveSessions()
        db.projectDao().setActiveSession(projectId)
        return AppResult.Success(
            LoadedProject(
                entity = entity.copy(missingAssetsCount = missingIndices.size),
                timeline = timeline,
                assets = assets,
                conversationJson = entity.conversationJson,
                clipCuts = decodeCuts(entity.clipCutsJson),
                missingAssetIndices = missingIndices,
            )
        )
    }

    fun findMissingAssets(context: Context, assets: List<MediaAssetEntity>): List<Int> {
        return assets.filter { a -> !isAssetReadable(context, a.uriString) }.map { it.clipIndex }
    }

    fun isAssetReadable(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return File(path).let { it.exists() && it.canRead() && it.length() > 0L }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    suspend fun relinkAsset(
        context: Context,
        projectId: String,
        clipIndex: Int,
        newUriString: String,
        newDisplayName: String? = null,
    ): AppResult<LoadedProject> {
        val entity = db.projectDao().findById(projectId)
            ?: return AppResult.Failure(AppError.Storage("Project not found."))
        val assets = db.mediaAssetDao().forProject(projectId)
        val updatedAssets = assets.map { a ->
            if (a.clipIndex != clipIndex) a
            else a.copy(
                uriString = newUriString,
                displayName = newDisplayName ?: a.displayName,
            )
        }
        db.mediaAssetDao().upsertAll(updatedAssets)

        val parsed = when (val r = TimelineCodec.fromJson(entity.timelineJson)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val updatedTimeline = parsed.copy(
            updatedAtMs = System.currentTimeMillis(),
            videoTracks = parsed.videoTracks.map { t ->
                t.copy(items = t.items.map { item ->
                    if (item.sourceClipIndex == clipIndex) item.copy(sourceUri = newUriString) else item
                })
            },
        )
        val missing = findMissingAssets(context, updatedAssets)
        db.projectDao().upsert(
            entity.copy(
                timelineJson = TimelineCodec.toJson(updatedTimeline),
                missingAssetsCount = missing.size,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
        return loadProject(projectId, context)
    }

    fun saveProjectThumbnail(context: Context, projectId: String, bitmap: Bitmap): String? {
        return runCatching {
            val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val safeId = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "project" }
            val file = File(dir, "$safeId.jpg")
            require(file.canonicalPath.startsWith(dir.canonicalPath + File.separator))
            val scaled = Bitmap.createScaledBitmap(bitmap, 240, (240 * bitmap.height / bitmap.width.coerceAtLeast(1)).coerceIn(80, 426), true)
            file.outputStream().use { os ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, os)
            }
            if (scaled !== bitmap) scaled.recycle()
            file.absolutePath
        }.getOrNull()
    }

    suspend fun recordExportDiagnostics(projectId: String, outputPath: String, diagnosticsJson: String) {
        val existing = db.projectDao().findById(projectId) ?: return
        db.projectDao().upsert(
            existing.copy(
                lastOutputPath = outputPath,
                lastExportDiagnosticsJson = diagnosticsJson,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    suspend fun renameProject(projectId: String, newName: String) {
        val clean = newName.trim().take(60)
        if (clean.isEmpty()) return
        val entity = db.projectDao().findById(projectId) ?: return
        val now = System.currentTimeMillis()
        val parsed = TimelineCodec.fromJson(entity.timelineJson).getOrNull()?.copy(name = clean, updatedAtMs = now)
        db.projectDao().upsert(
            entity.copy(
                name = clean,
                timelineJson = parsed?.let { TimelineCodec.toJson(it) } ?: entity.timelineJson,
                updatedAtMs = now,
            )
        )
    }

    suspend fun duplicateProject(projectId: String): AppResult<TimelineProject> {
        val src = db.projectDao().findById(projectId)
            ?: return AppResult.Failure(AppError.Storage("Project not found."))
        val parsed = when (val r = TimelineCodec.fromJson(src.timelineJson)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val now = System.currentTimeMillis()
        val copyId = UUID.randomUUID().toString()
        val copyTimeline = parsed.copy(
            id = copyId,
            name = "${src.name} (Copy)".take(60),
            createdAtMs = now,
            updatedAtMs = now,
        )
        db.projectDao().upsert(
            src.copy(
                id = copyId,
                name = copyTimeline.name,
                timelineJson = TimelineCodec.toJson(copyTimeline),
                createdAtMs = now,
                updatedAtMs = now,
                deletedAtMs = null,
                isActiveSession = false,
            )
        )
        val assets = db.mediaAssetDao().forProject(projectId).map {
            it.copy(id = "${copyId}_clip_${it.clipIndex}", projectId = copyId)
        }
        if (assets.isNotEmpty()) db.mediaAssetDao().upsertAll(assets)
        return AppResult.Success(copyTimeline)
    }

    suspend fun softDeleteProject(projectId: String) {
        db.projectDao().softDelete(projectId, System.currentTimeMillis())
    }

    suspend fun restoreProject(projectId: String) {
        db.projectDao().restore(projectId, System.currentTimeMillis())
    }

    suspend fun permanentlyDeleteProject(projectId: String) {
        db.projectDao().findById(projectId)?.thumbnailPath?.let { path ->
            runCatching { File(path).delete() }
        }
        db.projectDao().hardDelete(projectId)
    }

    suspend fun exportProjectJson(projectId: String): AppResult<String> {
        val entity = db.projectDao().findById(projectId)
            ?: return AppResult.Failure(AppError.Storage("Project not found."))
        val assets = db.mediaAssetDao().forProject(projectId)
        val now = System.currentTimeMillis()
        db.projectDao().upsert(entity.copy(lastBackupAtMs = now))
        val wrapper = JSONObject().apply {
            put("format", "promptcut_project_v2")
            put("schemaVersion", TimelineProject.CURRENT_SCHEMA_VERSION)
            put("exportedAtMs", now)
            put("timeline", JSONObject(entity.timelineJson))
            if (!entity.legacyPlanJson.isNullOrBlank()) put("legacyPlanJson", entity.legacyPlanJson)
            if (entity.conversationJson.isNotBlank()) put("conversationJson", entity.conversationJson)
            if (entity.clipCutsJson.isNotBlank()) put("clipCutsJson", entity.clipCutsJson)
            put("assets", JSONArray().apply {
                assets.forEach { a ->
                    put(JSONObject().apply {
                        put("clipIndex", a.clipIndex)
                        put("uriString", a.uriString)
                        put("displayName", a.displayName)
                        put("durationSec", a.durationSec)
                        put("width", a.width)
                        put("height", a.height)
                        put("hasAudio", a.hasAudio)
                        put("recordedAtMs", a.recordedAtMs)
                        put("insightSummary", a.insightSummary)
                        put("isHdr", a.isHdr)
                        a.cropJson?.let { put("cropJson", it) }
                    })
                }
            })
        }
        return AppResult.Success(wrapper.toString(2))
    }

    suspend fun importProjectJson(rawJson: String, fallbackClips: List<ClipMeta> = emptyList()): AppResult<TimelineProject> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return AppResult.Failure(AppError.Validation("Import file is empty."))
        return try {
            val root = JSONObject(trimmed)
            val isEnvelope = (root.optString("format").startsWith("promptcut_project_v")) && root.has("timeline")
            val timelineSource = if (isEnvelope) root.getJSONObject("timeline").toString() else trimmed
            val importedAssets = if (isEnvelope) parseImportedAssets(root.optJSONArray("assets")) else emptyList()
            val metas = fallbackClips.ifEmpty {
                importedAssets.map { a ->
                    ClipMeta(
                        index = a.clipIndex,
                        name = a.displayName,
                        durationSec = a.durationSec,
                        width = a.width,
                        height = a.height,
                        hasAudio = a.hasAudio,
                        recordedAt = a.recordedAtMs,
                        description = a.insightSummary,
                        crop = parseCropJson(a.cropJson),
                    )
                }
            }
            val parsed = when (val r = TimelineCodec.fromJson(timelineSource, metas)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return r
            }
            val now = System.currentTimeMillis()
            val newId = UUID.randomUUID().toString()
            val imported = parsed.copy(
                id = newId,
                name = if (parsed.name == "PromptCut Edit" || parsed.name == "Untitled Edit") "Imported Edit" else parsed.name,
                createdAtMs = now,
                updatedAtMs = now,
                legacyPlanJson = parsed.legacyPlanJson ?: root.optString("legacyPlanJson", "").ifBlank { null },
            )
            val entity = ProjectEntity(
                id = newId,
                name = imported.name,
                schemaVersion = imported.schemaVersion,
                timelineJson = TimelineCodec.toJson(imported),
                legacyPlanJson = imported.legacyPlanJson,
                conversationJson = root.optString("conversationJson", ""),
                clipCutsJson = root.optString("clipCutsJson", ""),
                createdAtMs = now,
                updatedAtMs = now,
                isActiveSession = true,
                durationSec = usToSec(imported.totalDurationUs),
                clipCount = importedAssets.size,
            )
            db.projectDao().clearAllActiveSessions()
            db.projectDao().upsert(entity)
            if (importedAssets.isNotEmpty()) {
                db.mediaAssetDao().upsertAll(
                    importedAssets.map { it.copy(id = "${newId}_clip_${it.clipIndex}", projectId = newId) }
                )
            }
            AppResult.Success(imported)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Could not import project: ${t.message}", cause = t))
        }
    }

    suspend fun findInterruptedSession(): ProjectEntity? = db.projectDao().findActiveSession()

    suspend fun recordRenderJob(state: RenderJobState) {
        db.renderJobDao().upsert(
            RenderJobEntity(
                jobId = state.jobId,
                projectId = state.projectId,
                projectName = state.projectName,
                status = state.status.name,
                progress = state.progress,
                message = state.message,
                outputPath = state.outputPath,
                updatedAtMs = state.updatedAtMs,
            )
        )
    }

    suspend fun findInterruptedRenderJob(): RenderJobEntity? = db.renderJobDao().findRecoverable()

    private fun parseImportedAssets(arr: JSONArray?): List<MediaAssetEntity> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MediaAssetEntity(
                id = "",
                projectId = "",
                clipIndex = o.optInt("clipIndex", i),
                uriString = o.optString("uriString", ""),
                displayName = o.optString("displayName", "Clip ${i + 1}"),
                durationSec = o.optDouble("durationSec", 0.0),
                width = o.optInt("width", 1080),
                height = o.optInt("height", 1920),
                hasAudio = o.optBoolean("hasAudio", true),
                recordedAtMs = o.optLong("recordedAtMs", 0L),
                insightSummary = o.optString("insightSummary", ""),
                isHdr = o.optBoolean("isHdr", false),
                cropJson = o.optString("cropJson", "").ifBlank { null },
            )
        }
    }

    private fun encodeCuts(cuts: Map<Int, List<Span>>): String {
        if (cuts.isEmpty()) return ""
        return JSONObject().apply {
            cuts.forEach { (clipIdx, list) ->
                put(clipIdx.toString(), JSONArray().apply {
                    list.forEach { s ->
                        put(JSONObject().put("start", s.start).put("end", s.end).put("text", s.text))
                    }
                })
            }
        }.toString()
    }

    private fun decodeCuts(raw: String): Map<Int, List<Span>> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<Int, List<Span>>()
            for (k in o.keys()) {
                val idx = k.toIntOrNull() ?: continue
                val arr = o.optJSONArray(k) ?: continue
                out[idx] = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    Span(s.optDouble("start"), s.optDouble("end"), s.optString("text", ""))
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    companion object {
        internal fun parseCropJson(raw: String?): CropBox? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(raw)
                if (arr.length() == 4) CropBox(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2), arr.getDouble(3)).normalized() else null
            }.getOrNull()
        }
    }
}
