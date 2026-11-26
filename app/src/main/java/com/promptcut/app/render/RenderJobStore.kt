package com.promptcut.app.render

import android.content.Context
import org.json.JSONObject

enum class RenderJobStatus(val label: String) {
    QUEUED("Queued"),
    RENDERING("Rendering"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    INTERRUPTED("Interrupted"),
}

data class RenderJobState(
    val jobId: String,
    val projectId: String,
    val projectName: String,
    val status: RenderJobStatus,
    val progress: Int,
    val message: String,
    val outputPath: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Persists the active/last render job state in SharedPreferences so crashes or process kills
 * are detected on next launch and offered cleanly for recovery.
 */
class RenderJobStore(context: Context) {
    private val prefs = context.getSharedPreferences("promptcut_render_jobs", Context.MODE_PRIVATE)

    fun save(job: RenderJobState) {
        val obj = JSONObject().apply {
            put("jobId", job.jobId)
            put("projectId", job.projectId)
            put("projectName", job.projectName)
            put("status", job.status.name)
            put("progress", job.progress)
            put("message", job.message)
            put("outputPath", job.outputPath ?: "")
            put("updatedAtMs", job.updatedAtMs)
        }
        prefs.edit().putString(KEY_LAST_JOB, obj.toString()).apply()
    }

    fun load(): RenderJobState? {
        val raw = prefs.getString(KEY_LAST_JOB, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            RenderJobState(
                jobId = o.getString("jobId"),
                projectId = o.getString("projectId"),
                projectName = o.optString("projectName", "Untitled Edit"),
                status = runCatching { RenderJobStatus.valueOf(o.getString("status")) }.getOrDefault(RenderJobStatus.INTERRUPTED),
                progress = o.optInt("progress", 0),
                message = o.optString("message", ""),
                outputPath = o.optString("outputPath", "").ifBlank { null },
                updatedAtMs = o.optLong("updatedAtMs", 0L),
            )
        }.getOrNull()
    }

    fun markInterruptedIfRunning(): RenderJobState? {
        val cur = load() ?: return null
        if (cur.status == RenderJobStatus.RENDERING || cur.status == RenderJobStatus.QUEUED) {
            val updated = cur.copy(
                status = RenderJobStatus.INTERRUPTED,
                message = "Previous export was interrupted before finishing.",
                updatedAtMs = System.currentTimeMillis(),
            )
            save(updated)
            return updated
        }
        return null
    }

    fun clear() {
        prefs.edit().remove(KEY_LAST_JOB).apply()
    }

    companion object {
        private const val KEY_LAST_JOB = "last_render_job"
    }
}
