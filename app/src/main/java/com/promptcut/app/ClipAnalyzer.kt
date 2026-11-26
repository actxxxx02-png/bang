package com.promptcut.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class Clip(val uri: Uri, val meta: ClipMeta, val thumbnail: Bitmap?, val isHdr: Boolean = false)

object ClipAnalyzer {
    private const val MAX_TOTAL_FRAMES = 36
    private const val FRAME_WIDTH = 384

    suspend fun load(context: Context, uris: List<Uri>, startIndex: Int): List<Clip> = withContext(Dispatchers.IO) {
        uris.mapIndexedNotNull { i, uri ->
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    val thumb = runCatching {
                        r.getScaledFrameAtTime((durMs * 1000L) / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
                    }.getOrNull()
                    if (durMs <= 0) return@runCatching null
                    val recorded = parseDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    Clip(uri, ClipMeta(startIndex + i, displayName(context, uri), durMs / 1000.0, w, h, hasAudio, recorded), thumb, isHdr(context, uri))
                }
            }.getOrNull()
        }
    }

    /**
     * Samples frames across all clips (more for longer clips) so a vision model can "watch" the footage cheaply.
     * With [windows], frames are taken only inside those time ranges (second pass on long footage).
     */
    suspend fun sampleFrames(context: Context, clips: List<Clip>, windows: Map<Int, List<Span>>? = null): List<Frame> = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) return@withContext emptyList()
        val ranges = clips.associate { c ->
            c.meta.index to (windows?.get(c.meta.index) ?: listOf(Span(0.0, c.meta.durationSec)))
        }
        val totalDur = ranges.values.flatten().sumOf { it.length }.coerceAtLeast(1.0)
        val frames = mutableListOf<Frame>()
        clips.forEach { clip ->
            val rs = ranges[clip.meta.index] ?: return@forEach
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    rs.forEach { span ->
                        val n = (MAX_TOTAL_FRAMES * span.length / totalDur).toInt().coerceIn(1, 8)
                        for (k in 0 until n) {
                            val t = span.start + span.length * (k + 0.5) / n
                            val scaleH = if (clip.meta.width > 0) FRAME_WIDTH * clip.meta.height / clip.meta.width else FRAME_WIDTH
                            val bmp = r.getScaledFrameAtTime(
                                (t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_WIDTH, scaleH.coerceAtLeast(64)
                            ) ?: continue
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                            bmp.recycle()
                            frames += Frame("clip ${clip.meta.index} at ${round2(t)} s", out.toByteArray())
                        }
                    }
                }
            }
        }
        frames.take(MAX_TOTAL_FRAMES + 8)
    }

    /** HDR (HLG/HDR10) footage, common on recent phones, is converted to normal colour before effects. */
    private fun isHdr(context: Context, uri: Uri): Boolean {
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).any { i ->
                val f = ex.getTrackFormat(i)
                f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                    f.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER) &&
                    f.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER).let {
                        it == android.media.MediaFormat.COLOR_TRANSFER_ST2084 || it == android.media.MediaFormat.COLOR_TRANSFER_HLG
                    }
            }
        } catch (e: Exception) { false } finally { ex.release() }
    }

    /** Phone videos store e.g. "20260925T101530.000Z". */
    private fun parseDate(v: String?): Long {
        if (v.isNullOrBlank()) return 0
        return listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss").firstNotNullOfOrNull { f ->
            runCatching {
                java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(v)?.time
            }.getOrNull()
        }?.takeIf { it > 946684800000L } ?: 0 // ignore bogus 1904/1970 dates
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "clip"
}
