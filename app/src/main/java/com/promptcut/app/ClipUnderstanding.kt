package com.promptcut.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.promptcut.app.intelligence.GamingMoment
import com.promptcut.app.intelligence.GamingSignals
import com.promptcut.app.intelligence.OcrAndGamingAnalyzer
import com.promptcut.app.intelligence.StoryAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * "Watches" each clip on the phone, offline: samples frames, finds shot changes, people, smiles and objects
 * (ML Kit), blur/darkness/shake, and loud or spoken parts from the audio. The result is text that lets
 * even a text-only AI (Groq) cut with meaning.
 */
object ClipUnderstanding {
    private const val SHORT_CLIP_SAMPLES = 40
    private const val MAX_SAMPLES_PER_CLIP = 240
    private const val STT_CHUNK_SEC = 600.0

    /** Short clips: adaptive ~2 frames/s (capped at 40). Long clips: scales smoothly up to 240 samples. */
    fun sampleCount(durationSec: Double): Int =
        if (durationSec <= 60) (durationSec / 0.5).toInt().coerceIn(1, SHORT_CLIP_SAMPLES)
        else (SHORT_CLIP_SAMPLES + (durationSec - 60) / 5).toInt().coerceAtMost(MAX_SAMPLES_PER_CLIP)
    private const val AUDIO_HOP = 0.05

    /** Labels too generic to help an editor. */
    private val boring = setOf(
        "Pattern", "Font", "Rectangle", "Circle", "Material", "Metal", "Wood", "Plastic", "Paper", "Room",
        "Event", "Moustache", "Eyelash", "Skin", "Hand", "Nail", "Sleeve", "Jacket", "Shelf", "Wall",
    )
    private val actionLabels = setOf(
        "Dance", "Party", "Cake", "Fun", "Sports", "Surfing", "Skateboard", "Swimming", "Running", "Concert",
        "Fireworks", "Balloon", "Wedding", "Bride", "Christmas", "Pet", "Dog", "Cat", "Baby", "Toy", "Beach",
        "Sunset", "Waterfall", "Mountain", "Stage", "Bicycle", "Food", "Smile", "Laugh", "Selfie",
    )
    /** Pleasant views: worth showing even without people. */
    private val scenicLabels = setOf(
        "Sky", "Sunset", "Mountain", "Lake", "River", "Sea", "Beach", "Waterfall", "Tree", "Flower", "Field",
        "Garden", "Cloud", "Forest", "Hill", "Temple", "Monument", "Bridge", "Castle", "Snow", "Rainbow", "Fireworks",
    )
    /** Labels that mean the camera is looking at a plain surface (ground, road, wall): dull on screen. */
    private val textureLabels = setOf(
        "Soil", "Asphalt", "Road", "Grass", "Floor", "Ground", "Concrete", "Gravel", "Pavement", "Sand", "Leaf",
        "Rock", "Dirt", "Carpet", "Tile", "Wall", "Plant", "Moss",
    )

    private class Sample(
        val t: Double, val hist: FloatArray, val luma: Double, val sharp: Double, val motion: Double,
        val labels: List<String>, val faces: Int, val smile: Float, val faceX: Double? = null,
        val ocrText: String = "", val lumaDelta: Double = 0.0,
    )

    suspend fun analyze(
        context: Context,
        clip: Clip,
        speechToText: (suspend (File) -> List<Span>)?,
        onStep: (String) -> Unit,
    ): ClipInsight = withContext(Dispatchers.Default) {
        val meta = clip.meta
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.62f).build())
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.1f)
                .build()
        )
        val ocr = OcrAndGamingAnalyzer()
        val samples = mutableListOf<Sample>()
        try {
            val n = sampleCount(meta.durationSec)
            val step = meta.durationSec / n
            val ocrStride = maxOf(1, n / 12) // Budget OCR to ~12 frames/clip for Redmi 11 Prime speed
            // For sparse sampling, nearest keyframe is close enough and much faster to decode.
            val option = if (step >= 2.0) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, clip.uri)
                var prevGray: FloatArray? = null
                var prevLuma: Double? = null
                for (k in 0 until n) {
                    ensureActive()
                    val t = (k + 0.5) * step
                    val w = 320
                    val h = if (meta.width > 0) (w * meta.height / meta.width).coerceIn(120, 640) else 320
                    val bmp = runCatching {
                        r.getScaledFrameAtTime((t * 1_000_000).toLong(), option, w, h)
                    }.getOrNull() ?: continue
                    val frameW = bmp.width.toFloat()
                    val small = Bitmap.createScaledBitmap(bmp, 64, (64 * bmp.height / bmp.width).coerceAtLeast(16), true)
                    val (hist, gray, luma) = pixelStats(small)
                    val sharp = laplacianVar(gray, small.width, small.height)
                    val motion = prevGray?.let { p -> meanAbsDiff(p, gray) } ?: 0.0
                    val lumaDelta = prevLuma?.let { abs(luma - it) } ?: 0.0
                    prevGray = gray
                    prevLuma = luma
                    small.recycle()

                    val image = InputImage.fromBitmap(bmp, 0)
                    val labels = runCatching { Tasks.await(labeler.process(image)) }.getOrNull()
                        ?.sortedByDescending { it.confidence }?.map { it.text }?.filter { it !in boring }?.take(4).orEmpty()
                    val faces = runCatching { Tasks.await(faceDetector.process(image)) }.getOrNull().orEmpty()
                    val smile = faces.maxOfOrNull { it.smilingProbability ?: 0f } ?: 0f
                    val ocrText = if (k % ocrStride == 0 || motion > 25.0) ocr.extractText(bmp) else ""
                    bmp.recycle()
                    // Centre of the biggest face, as a fraction of the frame width (for reframing).
                    val faceX = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        ?.let { (it.boundingBox.exactCenterX() / frameW).toDouble().coerceIn(0.0, 1.0) }
                    samples += Sample(t, hist, luma, sharp, motion, labels, faces.size, smile, faceX, ocrText, lumaDelta)
                    onStep("Watching clip ${meta.index + 1}: ${((k + 1) * 100) / n}%")
                }
            }
        } finally {
            labeler.close(); faceDetector.close(); ocr.close()
        }

        // ---- sound
        onStep("Listening to clip ${meta.index + 1}…")
        val env = if (meta.hasAudio) AudioTools.envelope(context, clip.uri, AUDIO_HOP, meta.durationSec + 1) else null
        val loud = env?.let { AudioTools.loudSpans(it, AUDIO_HOP) }.orEmpty()
        var speech = emptyList<Span>()
        if (speechToText != null && meta.hasAudio && loud.isNotEmpty()) {
            // Long clips are sent in 10-minute pieces (free speech-to-text accepts up to ~25 MB per file).
            val all = mutableListOf<Span>()
            var from = 0.0
            var piece = 1
            val pieces = kotlin.math.ceil(meta.durationSec / STT_CHUNK_SEC).toInt().coerceAtLeast(1)
            while (from < meta.durationSec) {
                ensureActive()
                val to = minOf(meta.durationSec, from + STT_CHUNK_SEC)
                onStep("Understanding speech in clip ${meta.index + 1}" + if (pieces > 1) " (part $piece of $pieces)…" else "…")
                val f = File(context.cacheDir, "stt_${meta.index}_$piece.m4a")
                if (AudioTools.extractAac(context, clip.uri, f, from, to)) {
                    val offset = from
                    all += runCatching { speechToText(f) }.getOrDefault(emptyList())
                        .map { Span(it.start + offset, it.end + offset, it.text) }
                }
                f.delete()
                from = to; piece++
            }
            speech = all.filter { it.text.isNotBlank() && it.text.trim().length > 2 }
        }

        buildInsight(meta, samples, env, loud, speech)
    }

    private fun buildInsight(meta: ClipMeta, samples: List<Sample>, env: FloatArray?, loud: List<Span>, speech: List<Span>): ClipInsight {
        if (samples.isEmpty()) return ClipInsight(meta.index, loud = loud, speech = speech)
        val step = if (samples.size > 1) samples[1].t - samples[0].t else meta.durationSec
        val half = step / 2

        // Shots: cut where the colour histogram jumps.
        val bounds = mutableListOf(0.0)
        for (i in 1 until samples.size) {
            val d = histDistance(samples[i - 1].hist, samples[i].hist)
            if (d > 0.55 && samples[i].t - half - bounds.last() >= 1.0) bounds += samples[i].t - half
        }
        bounds += meta.durationSec
        // Smiles, and bad (blurry / dark / shaky) moments.
        val medSharp = samples.map { it.sharp }.sorted()[samples.size / 2]
        val smiles = spansOf(samples, half) { it.smile > 0.7f }
        val bad = mutableListOf<Span>()
        bad += spansOf(samples, half) { it.sharp < medSharp * 0.35 && it.sharp < 60 }.map { it.copy(text = "blurry") }
        bad += spansOf(samples, half) { it.luma < 35 }.map { it.copy(text = "dark") }
        bad += spansOf(samples, half) { it.motion > 45 && it.sharp < medSharp * 0.6 }.map { it.copy(text = "shaky") }

        // Best moments: faces, smiles, action labels, loud reactions; minus quality problems.
        fun loudAt(t: Double): Double {
            if (env == null) return 0.0
            val i = (t / AUDIO_HOP).toInt().coerceIn(0, env.lastIndex)
            val max = env.maxOrNull()?.takeIf { it > 0 } ?: return 0.0
            return (env[i] / max).toDouble()
        }
        val scored = samples.map { s ->
            val reasons = mutableListOf<String>()
            var score = 0.0
            if (s.faces > 0) { score += 1.0 + 0.3 * min(s.faces, 4); reasons += if (s.faces == 1) "face" else "${s.faces} people" }
            if (s.smile > 0.7f) { score += 2.5; reasons.add(0, "smiling") }
            val act = s.labels.filter { it in actionLabels }
            if (act.isNotEmpty()) { score += 1.0; reasons += act.first().lowercase() }
            val l = loudAt(s.t)
            if (l > 0.6) { score += 1.2 * l; reasons += "loud reaction" }
            if (s.motion in 8.0..35.0) score += 0.4
            if (s.labels.any { it in scenicLabels }) { score += 0.8; reasons += "scenery" }
            if (s.faces == 0 && s.labels.isNotEmpty() && s.labels.all { it in textureLabels }) score -= 1.5 // plain ground/wall
            if (s.sharp < medSharp * 0.35) score -= 3.0
            if (s.luma < 35) score -= 2.0
            Moment(s.t, reasons.take(2).joinToString(", ").ifBlank { "clear shot" }, score)
        }
        val highlights = mutableListOf<Moment>()
        val maxHighlights = (4 + meta.durationSec / 60).toInt().coerceAtMost(20)
        val spacing = maxOf(2.0, meta.durationSec / 60)
        scored.sortedByDescending { it.score }.forEach { m ->
            if (m.score > 1.0 && highlights.size < maxHighlights && highlights.none { abs(it.time - m.time) < spacing }) highlights += m
        }

        // Shots, each with an "interest" level so even a text-only AI knows which ones are worth showing.
        val scoreAt = scored.associateBy { it.time }
        val shots = (0 until bounds.size - 1).map { b ->
            val s = bounds[b]; val e = bounds[b + 1]
            val inShot = samples.filter { it.t in s..e }
            val labels = inShot.flatMap { it.labels }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.map { it.key }.take(4)
            val mean = inShot.mapNotNull { scoreAt[it.t]?.score }.average().let { if (it.isNaN()) 0.0 else it }
            val smiling = inShot.any { it.smile > 0.7f }
            val textureOnly = inShot.none { it.faces > 0 } && labels.isNotEmpty() && labels.take(2).all { it in textureLabels }
            val interest = when {
                smiling || mean >= 2.0 -> 2
                textureOnly || mean < 0.3 -> 0
                else -> 1
            }
            val fx = inShot.mapNotNull { it.faceX }.takeIf { it.isNotEmpty() }?.average()
            Shot(s, e, labels, inShot.maxOfOrNull { it.faces } ?: 0, smiling, interest, fx)
        }

        val silence = env?.let { StoryAnalyzer.silenceSpans(it, AUDIO_HOP) }.orEmpty()
        val gamingMoments = mutableListOf<GamingMoment>()
        var prevScore: Int? = null
        val envMax = env?.maxOrNull()?.takeIf { it > 0f } ?: 1f
        samples.forEach { s ->
            val audioLvl = if (env != null && env.isNotEmpty()) {
                val i = (s.t / AUDIO_HOP).toInt().coerceIn(0, env.lastIndex)
                (env[i] / envMax).toDouble()
            } else 0.0
            val (ev, nextScore) = GamingSignals.detectFromFrameSignals(
                timeSec = s.t,
                ocrText = s.ocrText,
                prevScore = prevScore,
                motion = s.motion,
                lumaDelta = s.lumaDelta,
                audioLevel = audioLvl,
                hasFace = s.faces > 0,
                smileProb = s.smile,
            )
            prevScore = nextScore
            gamingMoments += ev
        }
        val boostedHighlights = (highlights + gamingMoments.map {
            Moment(it.timeSec, "${it.type.label} (${it.source})", 3.2 * it.confidence)
        }).sortedByDescending { it.score }
            .fold(mutableListOf<Moment>()) { acc, m ->
                if (acc.size < maxHighlights && acc.none { abs(it.time - m.time) < spacing }) acc += m
                acc
            }
        val ocrNotes = samples.mapNotNull { s ->
            val clean = s.ocrText.replace("\n", " ").trim()
            if (clean.length in 3..60) "${round1(s.t)}s: \"$clean\"" else null
        }.distinct().take(6)
        val gamingNotes = gamingMoments.map { "${round1(it.timeSec)}s: ${it.type.label} [${it.source}]" }.distinct().take(10)

        val avgSharp = samples.map { it.sharp }.average().let { if (it.isNaN()) 0.0 else it }
        val avgLuma = samples.map { it.luma }.average().let { if (it.isNaN()) 0.0 else it }
        val avgMotion = samples.map { it.motion }.average().let { if (it.isNaN()) 0.0 else it }
        val openingHook = scored.filter { it.time <= 3.0 }.maxOfOrNull { it.score } ?: 0.0

        return ClipInsight(
            clip = meta.index,
            shots = shots,
            speech = speech,
            loud = loud,
            smiles = smiles,
            bad = bad.sortedBy { it.start },
            highlights = boostedHighlights.sortedBy { it.time },
            gamingEvents = gamingNotes,
            ocrHighlights = ocrNotes,
            silenceSpans = silence,
            avgSharpness = avgSharp,
            avgLuma = avgLuma,
            avgMotion = avgMotion,
            openingHookScore = openingHook,
        )
    }

    private fun spansOf(samples: List<Sample>, half: Double, pred: (Sample) -> Boolean): List<Span> {
        val res = mutableListOf<Span>()
        var start: Double? = null
        var last = 0.0
        samples.forEach { s ->
            if (pred(s)) { if (start == null) start = s.t - half; last = s.t + half }
            else if (start != null) { res += Span(start!!.coerceAtLeast(0.0), last); start = null }
        }
        if (start != null) res += Span(start!!.coerceAtLeast(0.0), last)
        return res
    }

    private fun pixelStats(b: Bitmap): Triple<FloatArray, FloatArray, Double> {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val hist = FloatArray(64)
        val gray = FloatArray(px.size)
        var lumaSum = 0.0
        px.forEachIndexed { i, c ->
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            hist[(r shr 6) * 16 + (g shr 6) * 4 + (bl shr 6)] += 1f
            val y = 0.299f * r + 0.587f * g + 0.114f * bl
            gray[i] = y; lumaSum += y
        }
        for (i in hist.indices) hist[i] /= px.size
        return Triple(hist, gray, lumaSum / px.size)
    }

    private fun laplacianVar(g: FloatArray, w: Int, h: Int): Double {
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            sum += v; sum2 += v * v; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var s = 0.0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private fun histDistance(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s
    }
}
