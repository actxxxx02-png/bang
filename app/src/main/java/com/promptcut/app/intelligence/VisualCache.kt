package com.promptcut.app.intelligence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.promptcut.app.AudioTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Memory-bounded visual & waveform cache tuned for mid-range devices (e.g. Redmi 11 Prime, 3-4 GB RAM).
 * - Limits simultaneous frame decoders via [decodeSemaphore]
 * - Uses small JPEG disk cache + bounded LruCache
 */
object VisualCache {
    private val decodeSemaphore = Semaphore(2)

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 12L).toInt().coerceIn(4096, 20_480)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val waveformCache = object : LruCache<String, FloatArray>(64) {}

    fun clearMemory() {
        memoryCache.evictAll()
        waveformCache.evictAll()
    }

    suspend fun getThumbnailStrip(
        context: Context,
        uri: Uri,
        clipIndex: Int,
        startSec: Double,
        endSec: Double,
        count: Int = 5,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val targetCount = count.coerceIn(1, 8)
        val dir = File(context.cacheDir, "thumb_strip").apply { mkdirs() }
        val step = ((endSec - startSec).coerceAtLeast(0.2)) / targetCount
        val out = mutableListOf<Bitmap>()

        for (i in 0 until targetCount) {
            val t = startSec + (i + 0.5) * step
            val key = "c${clipIndex}_${(t * 10).toInt()}"
            memoryCache.get(key)?.let {
                if (!it.isRecycled) {
                    out += it
                    continue
                }
            }
            val disk = File(dir, "$key.jpg")
            if (disk.exists() && disk.length() > 100) {
                val decoded = BitmapFactory.decodeFile(disk.absolutePath)
                if (decoded != null) {
                    memoryCache.put(key, decoded)
                    out += decoded
                    continue
                }
            }
        }
        if (out.size == targetCount) return@withContext out

        decodeSemaphore.withPermit {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    out.clear()
                    for (i in 0 until targetCount) {
                        val t = startSec + (i + 0.5) * step
                        val key = "c${clipIndex}_${(t * 10).toInt()}"
                        val cached = memoryCache.get(key)?.takeIf { !it.isRecycled }
                        if (cached != null) {
                            out += cached
                            continue
                        }
                        val bmp = r.getScaledFrameAtTime(
                            (t * 1_000_000).toLong(),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            120,
                            120,
                        ) ?: continue
                        memoryCache.put(key, bmp)
                        val disk = File(dir, "$key.jpg")
                        runCatching {
                            FileOutputStream(disk).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 55, fos) }
                        }
                        out += bmp
                    }
                }
            }
        }
        out
    }

    suspend fun getWaveform(context: Context, uri: Uri, cacheKey: String, bins: Int = 64): FloatArray =
        withContext(Dispatchers.IO) {
            waveformCache.get(cacheKey)?.let { return@withContext it }
            val env = AudioTools.envelope(context, uri, 0.05, 180.0) ?: return@withContext FloatArray(bins) { 0.15f }
            val down = downsample(env, bins)
            waveformCache.put(cacheKey, down)
            down
        }

    fun downsample(env: FloatArray, bins: Int): FloatArray {
        if (env.isEmpty() || bins <= 0) return FloatArray(bins.coerceAtLeast(1)) { 0.1f }
        val maxVal = env.maxOrNull()?.takeIf { it > 1e-4f } ?: 1f
        val out = FloatArray(bins)
        val stride = env.size.toDouble() / bins
        for (i in 0 until bins) {
            val from = (i * stride).toInt().coerceIn(0, env.lastIndex)
            val to = ((i + 1) * stride).toInt().coerceIn(from + 1, env.size)
            var peak = 0f
            for (k in from until to) peak = maxOf(peak, env[k])
            out[i] = (peak / maxVal).coerceIn(0.05f, 1.0f)
        }
        return out
    }
}
