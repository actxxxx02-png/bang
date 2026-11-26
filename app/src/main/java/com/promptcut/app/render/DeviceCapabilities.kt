package com.promptcut.app.render

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.StatFs
import com.promptcut.app.ExportResolution
import java.util.Locale

data class DeviceCapabilities(
    val maxResolution: ExportResolution = ExportResolution.P1080,
    val supportsHevc: Boolean = false,
    val supports60Fps: Boolean = true,
    val totalRamMb: Long = 4096L,
    val availableRamMb: Long = 1500L,
    val freeStorageMb: Long = 2048L,
    val lowMemoryWarning: Boolean = false,
) {
    fun supportedResolutions(): List<ExportResolution> =
        ExportResolution.entries.filter { it.shortEdge <= maxResolution.shortEdge }

    fun summaryLabel(): String = buildString {
        append("Max ${maxResolution.label}")
        append(if (supports60Fps) " • 60fps" else " • 30fps")
        append(if (supportsHevc) " • H.265/H.264" else " • H.264")
        append(" • ${freeStorageMb / 1024}GB free")
    }

    companion object {
        fun probe(context: Context): DeviceCapabilities {
            var maxShortEdge = 1080
            var supportsHevc = false
            var supports60Fps = false

            runCatching {
                val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                for (info in codecs) {
                    if (!info.isEncoder) continue
                    for (type in info.supportedTypes) {
                        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()?.videoCapabilities ?: continue
                        if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            supportsHevc = true
                        }
                        if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                            type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                        ) {
                            val maxW = caps.supportedWidths?.upper ?: 1920
                            val maxH = caps.supportedHeights?.upper ?: 1080
                            val shortBound = minOf(maxW, maxH)
                            val longBound = maxOf(maxW, maxH)
                            when {
                                longBound >= 3840 && shortBound >= 2160 -> maxShortEdge = maxOf(maxShortEdge, 2160)
                                longBound >= 2560 && shortBound >= 1440 -> maxShortEdge = maxOf(maxShortEdge, 1440)
                                longBound >= 1920 && shortBound >= 1080 -> maxShortEdge = maxOf(maxShortEdge, 1080)
                            }
                            if (runCatching { caps.getSupportedFrameRatesFor(1920, 1080).upper >= 58.0 }.getOrDefault(false) ||
                                runCatching { caps.getSupportedFrameRatesFor(1280, 720).upper >= 58.0 }.getOrDefault(false)
                            ) {
                                supports60Fps = true
                            }
                        }
                    }
                }
            }

            val memInfo = ActivityManager.MemoryInfo()
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.getMemoryInfo(memInfo)
            }
            val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).takeIf { it > 0L } ?: 4096L
            val availRamMb = (memInfo.availMem / (1024L * 1024L)).takeIf { it > 0L } ?: 1500L

            val freeStorageMb = runCatching {
                val stat = StatFs(context.cacheDir.absolutePath)
                (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
            }.getOrDefault(2048L)

            val maxRes = when {
                maxShortEdge >= 2160 && totalRamMb >= 5500L -> ExportResolution.P2160
                maxShortEdge >= 1440 && totalRamMb >= 4000L -> ExportResolution.P1440
                maxShortEdge >= 1080 -> ExportResolution.P1080
                else -> ExportResolution.P720
            }

            return DeviceCapabilities(
                maxResolution = maxRes,
                supportsHevc = supportsHevc,
                supports60Fps = supports60Fps,
                totalRamMb = totalRamMb,
                availableRamMb = availRamMb,
                freeStorageMb = freeStorageMb,
                lowMemoryWarning = memInfo.lowMemory || availRamMb < 450L,
            )
        }

        fun estimateOutputMb(videoBitrateBps: Int, audioBitrateBps: Int, durationSec: Double): Double {
            val totalBps = (videoBitrateBps.toLong() + audioBitrateBps.toLong()).coerceAtLeast(500_000L)
            return ((totalBps * durationSec.coerceAtLeast(1.0)) / 8.0) / (1024.0 * 1024.0)
        }

        fun preflightWarning(caps: DeviceCapabilities, settings: ExportSettings, durationSec: Double): String? {
            val estMb = estimateOutputMb(settings.videoBitrateBps, settings.audioBitrateBps, durationSec)
            val neededTempMb = (estMb * 2.5).coerceAtLeast(32.0)
            if (caps.freeStorageMb < neededTempMb) {
                return String.format(
                    Locale.US,
                    "Low storage: ~%.0f MB needed for safe export, only %d MB free.",
                    neededTempMb,
                    caps.freeStorageMb,
                )
            }
            if (caps.lowMemoryWarning && settings.resolution.shortEdge > 1080) {
                return "Available RAM is low (${caps.availableRamMb} MB). Consider 1080p or Safe Render for stability."
            }
            return null
        }
    }
}
