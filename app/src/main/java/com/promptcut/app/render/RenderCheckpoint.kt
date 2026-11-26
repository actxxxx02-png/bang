package com.promptcut.app.render

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tracks shot-by-shot safe-mode render progress on disk so interrupted exports
 * can resume from the last completed segment or clean up safely.
 */
data class RenderCheckpoint(
    val projectId: String,
    val planHash: String,
    val totalShots: Int,
    val completedIndices: Set<Int> = emptySet(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun isCompleteFor(index: Int, partFile: File): Boolean =
        index in completedIndices && partFile.exists() && partFile.length() > 1024L

    fun markCompleted(index: Int): RenderCheckpoint = copy(
        completedIndices = completedIndices + index,
        updatedAtMs = System.currentTimeMillis(),
    )

    fun saveTo(file: File) {
        val obj = JSONObject().apply {
            put("projectId", projectId)
            put("planHash", planHash)
            put("totalShots", totalShots)
            put("updatedAtMs", updatedAtMs)
            put("completed", JSONArray(completedIndices.sorted()))
        }
        file.parentFile?.mkdirs()
        file.writeText(obj.toString())
    }

    companion object {
        fun loadFrom(file: File, expectedProjectId: String, expectedHash: String): RenderCheckpoint? {
            if (!file.exists()) return null
            return runCatching {
                val o = JSONObject(file.readText())
                if (o.optString("projectId") != expectedProjectId) return@runCatching null
                if (o.optString("planHash") != expectedHash) return@runCatching null
                val arr = o.optJSONArray("completed") ?: JSONArray()
                val set = (0 until arr.length()).map { arr.getInt(it) }.toSet()
                RenderCheckpoint(
                    projectId = expectedProjectId,
                    planHash = expectedHash,
                    totalShots = o.optInt("totalShots", 0),
                    completedIndices = set,
                    updatedAtMs = o.optLong("updatedAtMs", 0L),
                )
            }.getOrNull()
        }
    }
}
