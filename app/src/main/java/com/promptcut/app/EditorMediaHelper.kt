package com.promptcut.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
)

enum class MusicSource(val label: String) {
    LIBRARY("Free songs"),
    MOOD("Built-in music"),
    MY_SONG("My song"),
    ORIGINAL("Original sound only"),
}

enum class EditorTab(val label: String) {
    AGENT("AI Agent"),
    STYLE("Style"),
    EDIT("Edit"),
    CAPTIONS("Captions"),
    AUDIO("Audio"),
    EFFECTS("Effects"),
    EXPORT("Export"),
}

/** A song the user picked or downloaded, with its envelope & tempo so cuts can follow the beat. */
class Song(
    val uri: Uri,
    val name: String,
    val envelope: FloatArray?,
    val tempo: Beat.Tempo?,
    val durationSec: Double,
    val credit: String? = null,
)

val OCCASIONS = listOf("birthday", "travel", "wedding", "family", "kids", "friends", "party", "festival", "sports", "pets", "food", "gaming", "vlog", "other")
val LENGTHS = listOf(0, 15, 30, 60, 180, 600)

/** Rows of style choices the user can unselect. */
enum class Opt { OCCASION, MOOD, LENGTH, FORMAT, LOOK, TRANSITION, MUSIC, SOUND, SPEED, USE_ALL, ORDER }

object EditorMediaHelper {
    suspend fun analyzeSong(ctx: Context, uri: Uri, name: String, credit: String? = null): Song? {
        val hop = 0.02
        val env = withContext(Dispatchers.Default) { AudioTools.envelope(ctx, uri, hop, 300.0) } ?: return null
        val tempo = withContext(Dispatchers.Default) { Beat.detect(env, hop) }
        return Song(uri, name, env, tempo, env.size * hop, credit)
    }

    suspend fun displayName(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Song"
    }

    fun pinEdges(
        before: EditPlan,
        after: EditPlan,
        durations: Map<Int, Double>,
        clipCuts: Map<Int, List<Span>>,
    ): EditPlan {
        if (before.segments.size != after.segments.size) return after
        fun fixed(clip: Int, t: Double): Boolean {
            val d = durations[clip] ?: return false
            if (t < 0.05 || t > d - 0.05) return true
            return clipCuts[clip].orEmpty().any { abs(it.start - t) < 0.05 || abs(it.end - t) < 0.05 }
        }
        return after.copy(segments = after.segments.zip(before.segments) { a, b ->
            a.copy(start = if (fixed(b.clip, b.start)) b.start else a.start, end = if (fixed(b.clip, b.end)) b.end else a.end)
        })
    }

    fun formatTimecode(us: Long): String {
        val totalMs = (us / 1000L).coerceAtLeast(0L)
        val mins = totalMs / 60_000L
        val secs = (totalMs % 60_000L) / 1000L
        val tenths = (totalMs % 1000L) / 100L
        return "%02d:%02d.%d".format(mins, secs, tenths)
    }

    fun describeTimelineBrief(project: TimelineProject): String {
        val dur = round1(usToSec(project.totalDurationUs))
        return "${project.mainVideoTrack.items.size} clips · ${dur}s · ${project.aspect.key} · ${project.colorLook.label}"
    }
}
