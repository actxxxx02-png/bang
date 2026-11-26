package com.promptcut.app

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.SpeedRampPoint
import com.promptcut.app.timeline.VideoClipItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Constant volume for one audio stream (clip sound or music). */
@OptIn(UnstableApi::class)
class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (gain == 1f) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) out.putFloat(inputBuffer.float * gain)
        } else {
            while (inputBuffer.remaining() >= 2) {
                out.putShort((inputBuffer.short * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }
}

/** Fade in at the start and out at the end of the whole mix, so the video doesn't end with a hard cut. */
@OptIn(UnstableApi::class)
class FadeProcessor(private val totalSec: Double, private val fadeIn: Double = 0.25, private val fadeOut: Double = 1.4) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = fmt.bytesPerFrame
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val t = frames.toDouble() / fmt.sampleRate
            val g = (minOf(1.0, t / fadeIn, (totalSec - t) / fadeOut)).coerceIn(0.0, 1.0).toFloat()
            for (c in 0 until fmt.channelCount) {
                if (isFloat) out.putFloat(inputBuffer.float * g)
                else out.putShort((inputBuffer.short * g).toInt().toShort())
            }
            frames++
        }
        out.flip()
    }
}

/** Fades picture from/to black. Time is measured from the first frame this item sees. */
@OptIn(UnstableApi::class)
class FadeEffect(private val lengthSec: Double, private val fadeIn: Double, private val fadeOut: Double) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        var g = 1.0
        if (fadeIn > 0) g = minOf(g, t / fadeIn)
        if (fadeOut > 0) g = minOf(g, (lengthSec - t) / fadeOut)
        val s = g.coerceIn(0.0, 1.0).toFloat()
        return floatArrayOf(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/**
 * Fades a shot in from a colour and out to a colour (black, or white for a flash). Time is local to the shot,
 * measured from the first frame it sees, so it works inside a sequence of many shots.
 */
@OptIn(UnstableApi::class)
class DipEffect(
    private val lengthSec: Double,
    private val inSec: Double,
    private val outSec: Double,
    private val inWhite: Boolean = false,
    private val outWhite: Boolean = false,
) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        var s = 1.0
        var white = false
        if (inSec > 0 && t < inSec) { s = ease(t / inSec); white = inWhite }
        if (outSec > 0 && t > lengthSec - outSec) {
            val o = ease(((lengthSec - t) / outSec).coerceIn(0.0, 1.0))
            if (o < s) { s = o; white = outWhite }
        }
        val k = s.coerceIn(0.0, 1.0).toFloat()
        val off = if (white) 1f - k else 0f
        return floatArrayOf(
            k, 0f, 0f, 0f,
            0f, k, 0f, 0f,
            0f, 0f, k, 0f,
            off, off, off, 1f,
        )
    }
}

/**
 * Camera motion inside a shot: a quick "punch" zoom at the start (energy), and/or a slow Ken Burns drift
 * across the whole shot (smooth, cinematic). Keeps the frame size; zooming only ever crops in.
 */
@OptIn(UnstableApi::class)
class MotionEffect(
    private val lengthSec: Double,
    private val punch: Float = 0f,
    private val driftFrom: Float = 1f,
    private val driftTo: Float = 1f,
    private val panX: Float = 0f,
    private val pivotX: Float = 0f,
) : MatrixTransformation {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        val p = (t / lengthSec).coerceIn(0.0, 1.0).toFloat()
        var scale = driftFrom + (driftTo - driftFrom) * p
        if (punch > 0f && t < 0.35) scale += punch * (1f - ease(t / 0.35).toFloat())
        val m = android.graphics.Matrix()
        m.setScale(scale, scale, pivotX, 0f)
        val maxPan = (scale - 1f).coerceAtLeast(0f)
        if (pivotX == 0f) m.postTranslate((panX * (p - 0.5f) * 2f).coerceIn(-maxPan, maxPan), 0f)
        return m
    }
}

/**
 * Handheld micro-shake transformation with safe zoom overscan so borders never show black gaps.
 */
@OptIn(UnstableApi::class)
class ShakeMatrixEffect(
    private val intensity: Float,
) : MatrixTransformation {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        val amp = intensity.coerceIn(0f, 1f) * 0.045f
        val zoom = 1f + amp * 2.2f
        val dx = (sin(t * 19.0) * 0.65 + cos(t * 31.0) * 0.35).toFloat() * amp
        val dy = (cos(t * 23.0) * 0.65 + sin(t * 37.0) * 0.35).toFloat() * amp
        return android.graphics.Matrix().apply {
            setScale(zoom, zoom)
            postTranslate(dx, dy)
        }
    }
}

/**
 * Interpolates SCALE, PAN_X, and PAN_Y keyframes across clip playback.
 */
@OptIn(UnstableApi::class)
class KeyframeTransformEffect(
    private val keyframes: List<Keyframe>,
) : MatrixTransformation {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val localUs = (presentationTimeUs - firstUs).coerceAtLeast(0L)
        val scale = interpolateProperty(keyframes, KeyframeProperty.SCALE, localUs, 1f).coerceIn(0.5f, 3.0f)
        val panX = interpolateProperty(keyframes, KeyframeProperty.PAN_X, localUs, 0f).coerceIn(-1f, 1f)
        val panY = interpolateProperty(keyframes, KeyframeProperty.PAN_Y, localUs, 0f).coerceIn(-1f, 1f)
        return android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(panX, panY)
        }
    }
}

/**
 * Interpolates OPACITY keyframes + optional fade-in/fade-out transitions into an RGB matrix.
 */
@OptIn(UnstableApi::class)
class KeyframeOpacityEffect(
    private val keyframes: List<Keyframe>,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val clipDurationUs: Long,
) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val localUs = (presentationTimeUs - firstUs).coerceAtLeast(0L)
        var alpha = interpolateProperty(keyframes, KeyframeProperty.OPACITY, localUs, 1f).coerceIn(0f, 1f)
        if (fadeInUs > 0L && localUs < fadeInUs) {
            alpha *= (localUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0L && clipDurationUs > 0L && localUs > clipDurationUs - fadeOutUs) {
            val rem = (clipDurationUs - localUs).coerceAtLeast(0L)
            alpha *= (rem.toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
        }
        return floatArrayOf(
            alpha, 0f, 0f, 0f,
            0f, alpha, 0f, 0f,
            0f, 0f, alpha, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/**
 * Single-pass OpenGL ES 2.0 fragment shader effect rendering Sharpen, Warm Glow, Vignette,
 * and Film Grain identically during Media3 Transformer export.
 */
@OptIn(UnstableApi::class)
class ShaderAdjustEffect(
    private val vignette: Float = 0f,
    private val filmGrain: Float = 0f,
    private val glow: Float = 0f,
    private val sharpen: Float = 0f,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        vignette <= 0.01f && filmGrain <= 0.01f && glow <= 0.01f && sharpen <= 0.01f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ShaderAdjustProgram(vignette, filmGrain, glow, sharpen)
    }

    private class ShaderAdjustProgram(
        private val vignette: Float,
        private val filmGrain: Float,
        private val glow: Float,
        private val sharpen: Float,
    ) : BaseGlShaderProgram(false, 1) {
        private var program: GlProgram? = null
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = max(1, inputWidth)
            height = max(1, inputHeight)
            if (program == null) {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
                    setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
            }
            return Size(width, height)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val p = program ?: return
            p.use()
            p.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            p.setFloatsUniform("uTexelStep", floatArrayOf(1f / width, 1f / height))
            p.setFloatUniform("uTime", ((presentationTimeUs % 10_000_000L) / 1_000_000.0).toFloat())
            p.setFloatUniform("uVignette", vignette.coerceIn(0f, 1f))
            p.setFloatUniform("uGrain", filmGrain.coerceIn(0f, 1f))
            p.setFloatUniform("uGlow", glow.coerceIn(0f, 1f))
            p.setFloatUniform("uSharpen", sharpen.coerceIn(0f, 1f))
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            runCatching { program?.delete() }
            program = null
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2((aFramePosition.x + 1.0) * 0.5, (aFramePosition.y + 1.0) * 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelStep;
            uniform float uTime;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uGlow;
            uniform float uSharpen;
            varying vec2 vTexSamplingCoord;

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 col = base.rgb;

                if (uSharpen > 0.01 || uGlow > 0.01) {
                    vec3 cL = texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelStep.x, 0.0)).rgb;
                    vec3 cR = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelStep.x, 0.0)).rgb;
                    vec3 cU = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelStep.y)).rgb;
                    vec3 cD = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelStep.y)).rgb;
                    vec3 avg = (cL + cR + cU + cD) * 0.25;
                    if (uSharpen > 0.01) {
                        col = clamp(col + (col - avg) * (uSharpen * 1.4), 0.0, 1.0);
                    }
                    if (uGlow > 0.01) {
                        vec3 bright = max(avg - vec3(0.55), vec3(0.0));
                        col = clamp(col + bright * (uGlow * 0.75), 0.0, 1.0);
                    }
                }

                if (uVignette > 0.01) {
                    vec2 d = vTexSamplingCoord - vec2(0.5, 0.5);
                    float dist = dot(d, d) * 2.2;
                    float vig = clamp(1.0 - dist * uVignette * 0.85, 0.2, 1.0);
                    col *= vig;
                }

                if (uGrain > 0.01) {
                    float n = hash12(vTexSamplingCoord * 512.0 + vec2(uTime * 37.0, uTime * 19.0)) - 0.5;
                    col = clamp(col + vec3(n * uGrain * 0.14), 0.0, 1.0);
                }

                gl_FragColor = vec4(col, base.a);
            }
        """
    }
}

/**
 * Piecewise-stepped SpeedProvider implementing multi-point speed ramps on source timestamps.
 */
@OptIn(UnstableApi::class)
class SpeedRampProvider(
    private val baseSpeed: Double,
    points: List<SpeedRampPoint>,
    private val sourceDurationUs: Long,
) : SpeedProvider {
    private val sortedStops: List<Pair<Long, Float>> = if (points.size >= 2 && sourceDurationUs > 0L) {
        val buckets = 8
        val ordered = points.sortedBy { it.progress }
        (0 until buckets).map { i ->
            val frac = i.toFloat() / buckets
            val startUs = (frac * sourceDurationUs).toLong()
            val spd = interpolateRamp(ordered, frac).toFloat().coerceIn(0.25f, 4.0f)
            startUs to spd
        }
    } else {
        listOf(0L to baseSpeed.toFloat().coerceIn(0.25f, 4.0f))
    }

    override fun getSpeed(timeUs: Long): Float {
        if (sortedStops.size <= 1) return sortedStops.first().second
        val idx = sortedStops.indexOfLast { it.first <= timeUs }.coerceAtLeast(0)
        return sortedStops[idx].second
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (sortedStops.size <= 1) return C.TIME_UNSET
        return sortedStops.firstOrNull { it.first > timeUs }?.first ?: C.TIME_UNSET
    }

    private fun interpolateRamp(ordered: List<SpeedRampPoint>, progress: Float): Double {
        if (progress <= ordered.first().progress) return ordered.first().speed
        if (progress >= ordered.last().progress) return ordered.last().speed
        for (i in 0 until ordered.lastIndex) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (progress in a.progress..b.progress) {
                val span = (b.progress - a.progress).coerceAtLeast(1e-4f)
                val t = (progress - a.progress) / span
                return a.speed + (b.speed - a.speed) * t
            }
        }
        return baseSpeed
    }
}

fun interpolateProperty(
    keyframes: List<Keyframe>,
    property: KeyframeProperty,
    timeOffsetUs: Long,
    defaultValue: Float,
): Float {
    val list = keyframes.filter { it.property == property }.sortedBy { it.timeOffsetUs }
    if (list.isEmpty()) return defaultValue
    if (timeOffsetUs <= list.first().timeOffsetUs) return list.first().value
    if (timeOffsetUs >= list.last().timeOffsetUs) return list.last().value
    for (i in 0 until list.lastIndex) {
        val a = list[i]
        val b = list[i + 1]
        if (timeOffsetUs in a.timeOffsetUs..b.timeOffsetUs) {
            val span = (b.timeOffsetUs - a.timeOffsetUs).coerceAtLeast(1L).toFloat()
            val t = (timeOffsetUs - a.timeOffsetUs).toFloat() / span
            return a.value + (b.value - a.value) * t
        }
    }
    return defaultValue
}

private fun ease(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }

@OptIn(UnstableApi::class)
object Transitions {
    fun effects(style: Transition, i: Int, n: Int, lengthSec: Double, faceX: Float? = null,
                joinIn: Boolean = false, joinOut: Boolean = false): List<Effect> {
        if (joinIn || joinOut) {
            val list = mutableListOf<Effect>()
            val inSec = if (i == 0) 0.5 else if (joinIn) 0.0 else 0.3
            val outSec = if (i == n - 1) 0.9 else if (joinOut) 0.0 else 0.3
            if (style != Transition.CUT && style != Transition.AUTO || i == 0 || i == n - 1) list += DipEffect(lengthSec, inSec, outSec)
            return list
        }
        val pivot = faceX?.coerceIn(-1f, 1f) ?: 0f
        val drift = if (faceX != null) 1.04f else 1.08f
        val punchMul = if (faceX != null) 0.6f else 1f
        val first = i == 0
        val last = i == n - 1
        val shortShot = lengthSec < 1.2
        val list = mutableListOf<Effect>()
        when (style) {
            Transition.SMOOTH -> {
                val d = if (shortShot) 0.2 else 0.45
                list += DipEffect(lengthSec, if (first) 0.5 else d, if (last) 0.9 else d)
                val zoomIn = i % 2 == 0
                list += MotionEffect(lengthSec, driftFrom = if (zoomIn) 1.0f else drift, driftTo = if (zoomIn) drift else 1.0f,
                    panX = if (i % 3 == 0) 0.03f else -0.03f, pivotX = pivot)
            }
            Transition.FADE -> {
                val d = if (shortShot) 0.15 else 0.3
                list += DipEffect(lengthSec, if (first) 0.5 else d, if (last) 0.9 else d)
                list += MotionEffect(lengthSec, driftFrom = 1.0f, driftTo = if (faceX != null) 1.03f else 1.05f, pivotX = pivot)
            }
            Transition.FLASH -> {
                list += DipEffect(lengthSec, if (first) 0.3 else 0.14, if (last) 0.8 else 0.0, inWhite = !first)
                if (!first) list += MotionEffect(lengthSec, punch = 0.08f * punchMul, pivotX = pivot)
            }
            Transition.ZOOM -> {
                list += DipEffect(lengthSec, if (first) 0.3 else 0.06, if (last) 0.8 else 0.0)
                if (!first) list += MotionEffect(lengthSec, punch = 0.14f * punchMul, pivotX = pivot)
            }
            Transition.CUT, Transition.AUTO -> {
                if (first || last) list += DipEffect(lengthSec, if (first) 0.35 else 0.0, if (last) 0.8 else 0.0)
            }
        }
        return list
    }
}

@OptIn(UnstableApi::class)
class SaturationMatrix(private val s: Float) : RgbMatrix {
    private val m: FloatArray = run {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val a = 1 - s
        floatArrayOf(
            a * lr + s, a * lr, a * lr, 0f,
            a * lg, a * lg + s, a * lg, 0f,
            a * lb, a * lb, a * lb + s, 0f,
            0f, 0f, 0f, 1f,
        )
    }
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
}

@OptIn(UnstableApi::class)
object Looks {
    fun effects(look: ColorLook): List<Effect> = when (look) {
        ColorLook.NATURAL -> emptyList()
        ColorLook.WARM -> listOf(RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.02f).setBlueScale(0.88f).build())
        ColorLook.COOL -> listOf(RgbAdjustment.Builder().setRedScale(0.92f).setGreenScale(1.0f).setBlueScale(1.08f).build())
        ColorLook.VIVID -> listOf(SaturationMatrix(1.28f), Contrast(0.06f))
        ColorLook.CINEMATIC -> listOf(
            SaturationMatrix(0.82f),
            Contrast(0.15f),
            RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.96f).build(),
        )
        ColorLook.BW -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.1f))
    }

    fun buildClipAdjustments(
        item: VideoClipItem,
        includeShaders: Boolean = true,
    ): List<Effect> {
        val fx: EffectSettings = item.effects
        val out = mutableListOf<Effect>()
        if (abs(fx.brightness) > 0.01f) {
            out += Brightness(fx.brightness.coerceIn(-0.5f, 0.5f))
        }
        if (abs(fx.contrast) > 0.01f) {
            out += Contrast(fx.contrast.coerceIn(-0.5f, 0.8f))
        }
        if (abs(fx.saturation - 1f) > 0.01f) {
            out += SaturationMatrix(fx.saturation.coerceIn(0f, 2f))
        }
        if (includeShaders) {
            if (fx.blur > 0.01f) {
                out += GaussianBlur((fx.blur * 10f).coerceIn(0.5f, 12f))
            }
            if (fx.vignette > 0.01f || fx.filmGrain > 0.01f || fx.glow > 0.01f || fx.sharpen > 0.01f) {
                out += ShaderAdjustEffect(
                    vignette = fx.vignette,
                    filmGrain = fx.filmGrain,
                    glow = fx.glow,
                    sharpen = fx.sharpen,
                )
            }
        }
        if (fx.shake > 0.01f) {
            out += ShakeMatrixEffect(fx.shake)
        }
        if (item.keyframes.any {
                it.property == KeyframeProperty.SCALE ||
                    it.property == KeyframeProperty.PAN_X ||
                    it.property == KeyframeProperty.PAN_Y
            }
        ) {
            out += KeyframeTransformEffect(item.keyframes)
        }
        if (item.keyframes.any { it.property == KeyframeProperty.OPACITY } || fx.fadeInUs > 0L || fx.fadeOutUs > 0L) {
            out += KeyframeOpacityEffect(
                keyframes = item.keyframes,
                fadeInUs = fx.fadeInUs,
                fadeOutUs = fx.fadeOutUs,
                clipDurationUs = item.timelineDurationUs,
            )
        }
        return out
    }
}
