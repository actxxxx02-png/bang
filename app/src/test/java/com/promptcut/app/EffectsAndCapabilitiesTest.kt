package com.promptcut.app

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.promptcut.app.render.DeviceCapabilities
import com.promptcut.app.render.ExportDiagnostics
import com.promptcut.app.render.ExportSettings
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.SpeedRampPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class EffectsAndCapabilitiesTest {

    @Test
    fun speedRampProvider_interpolatesStopsAndSingleSpeedFallback() {
        val ramp = SpeedRampProvider(
            baseSpeed = 1.0,
            points = listOf(
                SpeedRampPoint(0f, 1.0),
                SpeedRampPoint(0.5f, 2.0),
                SpeedRampPoint(1f, 1.0),
            ),
            sourceDurationUs = 4_000_000L,
        )
        assertEquals(1.0f, ramp.getSpeed(0L), 0.05f)
        assertEquals(2.0f, ramp.getSpeed(2_000_000L), 0.05f)
        assertTrue(ramp.getNextSpeedChangeTimeUs(0L) > 0L)
        assertEquals(C.TIME_UNSET, ramp.getNextSpeedChangeTimeUs(3_900_000L))

        val constant = SpeedRampProvider(baseSpeed = 1.5, points = emptyList(), sourceDurationUs = 4_000_000L)
        assertEquals(1.5f, constant.getSpeed(1_000_000L), 0.001f)
        assertEquals(C.TIME_UNSET, constant.getNextSpeedChangeTimeUs(0L))
    }

    @Test
    fun interpolateProperty_clampsAndLinearlyInterpolatesKeyframes() {
        val keyframes = listOf(
            Keyframe(1_000_000L, KeyframeProperty.SCALE, 1.0f),
            Keyframe(3_000_000L, KeyframeProperty.SCALE, 2.0f),
            Keyframe(1_500_000L, KeyframeProperty.OPACITY, 0.5f),
        )
        // Before first keyframe clamps to first value
        assertEquals(1.0f, interpolateProperty(keyframes, KeyframeProperty.SCALE, 0L, 1.0f), 0.001f)
        // Midpoint linearly interpolates
        assertEquals(1.5f, interpolateProperty(keyframes, KeyframeProperty.SCALE, 2_000_000L, 1.0f), 0.001f)
        // Past last keyframe clamps to last value
        assertEquals(2.0f, interpolateProperty(keyframes, KeyframeProperty.SCALE, 5_000_000L, 1.0f), 0.001f)
        // Missing property returns default
        assertEquals(0.0f, interpolateProperty(keyframes, KeyframeProperty.PAN_X, 1_000_000L, 0.0f), 0.001f)
    }

    @Test
    fun exportSettings_computesDimensionsAndPreview720pOverride() {
        val feed45 = ExportSettings(resolution = ExportResolution.P1080)
        assertEquals(1080 to 1350, feed45.outputDimensions(Aspect.PORTRAIT_4_5))

        val customWide = ExportSettings(resolution = ExportResolution.P1080, customAspectWidth = 2100, customAspectHeight = 900)
        val (cw, ch) = customWide.outputDimensions(Aspect.CUSTOM)
        assertEquals(1080, ch)
        assertEquals(2520, cw)

        val preview720 = ExportSettings(resolution = ExportResolution.P2160, previewOnly = true)
        assertEquals(720 to 1280, preview720.outputDimensions(Aspect.PORTRAIT))
    }

    @Test
    fun exportDiagnostics_roundTripsJson() {
        val original = ExportDiagnostics(
            codec = "H.265 (HEVC)",
            width = 1080,
            height = 1920,
            fps = 60,
            videoBitrateBps = 14_000_000,
            audioBitrateBps = 256_000,
            durationMs = 15_200L,
            fileSizeBytes = 24_500_000L,
            hasVideoTrack = true,
            hasAudioTrack = true,
            renderTimeMs = 8_300L,
            usedSafeMode = false,
            verifiedOk = true,
            timestampMs = 1_700_000_000_000L,
        )
        val decoded = ExportDiagnostics.fromJson(original.toJson())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun deviceCapabilities_estimatesSizeAndDetectsPreflightWarnings() {
        val estMb = DeviceCapabilities.estimateOutputMb(
            videoBitrateBps = 8_000_000,
            audioBitrateBps = 192_000,
            durationSec = 60.0,
        )
        // ~61.44 MB for 8.192 Mbps * 60s
        assertTrue(estMb in 55.0..65.0)

        val lowStorageCaps = DeviceCapabilities(freeStorageMb = 40L)
        val storageWarning = DeviceCapabilities.preflightWarning(
            lowStorageCaps,
            ExportSettings(resolution = ExportResolution.P1080, videoBitrateBps = 8_000_000),
            durationSec = 60.0,
        )
        assertNotNull(storageWarning)
        assertTrue(storageWarning!!.contains("Low storage"))

        val lowRamCaps = DeviceCapabilities(freeStorageMb = 8_000L, availableRamMb = 350L, lowMemoryWarning = true)
        val ramWarning = DeviceCapabilities.preflightWarning(
            lowRamCaps,
            ExportSettings(resolution = ExportResolution.P2160, videoBitrateBps = 30_000_000),
            durationSec = 10.0,
        )
        assertNotNull(ramWarning)
        assertTrue(ramWarning!!.contains("RAM"))

        val healthyCaps = DeviceCapabilities(freeStorageMb = 10_000L, availableRamMb = 2_400L, lowMemoryWarning = false)
        assertNull(
            DeviceCapabilities.preflightWarning(
                healthyCaps,
                ExportSettings(resolution = ExportResolution.P1080),
                durationSec = 15.0,
            )
        )
    }
}
