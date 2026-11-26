package com.promptcut.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Span(val start: Double, val end: Double, val text: String = "") {
    val length get() = end - start
    fun contains(t: Double) = t > start && t < end
}

/** A shot = continuous camera view between two visual changes. */
data class Shot(
    val start: Double, val end: Double, val labels: List<String>, val faces: Int, val smiling: Boolean,
    /** 0 = dull (plain ground/wall, nothing happening), 1 = ok, 2 = strong (people, smiles, action). */
    val interest: Int = 1,
    /** Where the people are across the frame (0..1), null if no faces. Used to reframe. */
    val focusX: Double? = null,
)

data class Moment(val time: Double, val reason: String, val score: Double)

/** Everything the phone understood about a clip, turned into text any LLM (even text-only) can reason about. */
data class ClipInsight(
    val clip: Int,
    val shots: List<Shot> = emptyList(),
    val speech: List<Span> = emptyList(),
    val loud: List<Span> = emptyList(),
    val smiles: List<Span> = emptyList(),
    val bad: List<Span> = emptyList(),
    val highlights: List<Moment> = emptyList(),
    val gamingEvents: List<String> = emptyList(),
    val ocrHighlights: List<String> = emptyList(),
    val silenceSpans: List<Span> = emptyList(),
    val avgSharpness: Double = 0.0,
    val avgLuma: Double = 0.0,
    val avgMotion: Double = 0.0,
    val openingHookScore: Double = 0.0,
) {
    val shotBoundaries: List<Double> get() = shots.drop(1).map { it.start }

    /**
     * Text description for the AI.
     * - default: every shot (short footage)
     * - [compact]: shots merged into scenes of 8-60 s (long footage overview)
     * - [windows]: full detail but only inside these time ranges (second pass on long footage)
     */
    fun describe(meta: ClipMeta, compact: Boolean = false, windows: List<Span>? = null): String = buildString {
        fun inWin(a: Double, b: Double) = windows == null || windows.any { a < it.end && it.start < b }
        fun inWinT(t: Double) = windows == null || windows.any { t >= it.start && t <= it.end }
        val rec = if (meta.recordedAt > 0) ", recorded " + java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date(meta.recordedAt)) else ""
        appendLine("- clip ${meta.index}: \"${meta.name}\", ${round1(meta.durationSec)} s, ${meta.width}x${meta.height}, sound=${if (meta.hasAudio) "yes" else "no"}$rec")
        if (meta.crop != null) appendLine("    (the user cropped this clip to one area; only that area is shown)")
        if (windows != null) appendLine("    (only these parts are described; use only them: " + windows.joinToString { "${round1(it.start)}-${round1(it.end)} s" } + ")")

        if (compact) {
            val minScene = (meta.durationSec / 40).coerceIn(8.0, 60.0)
            val scenes = mutableListOf<MutableList<Shot>>()
            shots.forEach { sh ->
                val cur = scenes.lastOrNull()
                if (cur == null || cur.last().end - cur.first().start >= minScene) scenes += mutableListOf(sh) else cur += sh
            }
            scenes.forEach { sc ->
                val a = sc.first().start; val b = sc.last().end
                val labels = sc.flatMap { it.labels }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
                val faces = sc.maxOf { it.faces }
                val talk = speech.filter { it.start < b && a < it.end }
                val bits = listOfNotNull(
                    labels.joinToString(", ").ifBlank { null },
                    when { faces >= 2 -> "$faces people"; faces == 1 -> "1 person"; else -> null },
                    if (sc.any { it.smiling }) "smiling" else null,
                    if (loud.any { it.start < b && a < it.end }) "loud moments" else null,
                    if (talk.isNotEmpty()) "talking: " + talk.take(2).joinToString(" / ") { "\"${it.text.take(50)}\"" } else null,
                    if (bad.filter { it.start < b && a < it.end }.sumOf { it.length } > (b - a) / 2) "mostly poor quality" else null,
                )
                appendLine("    ${round1(a)}-${round1(b)} s: ${bits.joinToString("; ").ifBlank { "scene" }}")
            }
        } else {
            shots.filter { inWin(it.start, it.end) }.forEach { s ->
                val who = when {
                    s.faces >= 2 -> "${s.faces} people"
                    s.faces == 1 -> "1 person"
                    else -> null
                }
                val parts = listOfNotNull(
                    s.labels.take(4).joinToString(", ").ifBlank { null },
                    who,
                    if (s.smiling) "smiling" else null,
                    when (s.interest) { 0 -> "interest LOW (plain/dull)"; 2 -> "interest HIGH"; else -> null },
                )
                appendLine("    shot ${round1(s.start)}-${round1(s.end)} s: ${parts.joinToString("; ").ifBlank { "scene" }}")
            }
            val sm = smiles.filter { inWin(it.start, it.end) }
            if (sm.isNotEmpty()) appendLine("    smiles/laughing: " + sm.joinToString { "${round1(it.start)}-${round1(it.end)} s" })
            val sp = speech.filter { inWin(it.start, it.end) }
            if (sp.isNotEmpty()) appendLine("    speech (do not cut inside): " + sp.joinToString("; ") {
                "${round1(it.start)}-${round1(it.end)} s" + if (it.text.isNotBlank()) " \"${it.text.take(90)}\"" else ""
            })
            val loudOnly = loud.filter { l -> inWin(l.start, l.end) && speech.none { overlaps(it, l) } }
            if (loudOnly.isNotEmpty()) appendLine("    loud sound (cheering/music/laughter): " + loudOnly.joinToString { "${round1(it.start)}-${round1(it.end)} s" })
            val bd = bad.filter { inWin(it.start, it.end) }
            if (bd.isNotEmpty()) appendLine("    avoid (blurry/dark/shaky): " + bd.joinToString { "${round1(it.start)}-${round1(it.end)} s ${it.text}".trim() })
        }
        val hl = highlights.filter { inWinT(it.time) }
        if (hl.isNotEmpty()) appendLine("    best moments: " + hl.joinToString { "${round1(it.time)} s (${it.reason})" })
        if (gamingEvents.isNotEmpty()) appendLine("    gaming/HUD moments: " + gamingEvents.take(8).joinToString("; "))
        if (ocrHighlights.isNotEmpty()) appendLine("    on-screen text: " + ocrHighlights.take(5).joinToString(" | "))
    }

    private fun overlaps(a: Span, b: Span) = a.start < b.end && b.start < a.end
}

/** Beat grid of the music; cuts land on multiples of [unitSec] from the start of the video. */
data class BeatGrid(val beatSec: Double, val beatsPerCut: Int) {
    val unitSec get() = beatSec * beatsPerCut
}

/**
 * Deterministic polish after the AI plan, so cuts are never "random":
 * 1. snap cut points to nearby shot changes,
 * 2. never cut inside someone's speech,
 * 3. land cuts on the music's beat.
 */
object CutRefiner {
    private const val SHOT_SNAP = 0.6
    private const val SPEECH_PULL_START = 1.5
    private const val SPEECH_EXTEND_END = 2.5
    private const val MIN_LEN = 0.6

    fun refine(plan: EditPlan, durations: Map<Int, Double>, insights: Map<Int, ClipInsight>, grid: BeatGrid?): EditPlan {
        var outT = 0.0
        val out = plan.segments.mapIndexed { idx, seg ->
            val dur = durations[seg.clip] ?: return@mapIndexed seg
            val ins = insights[seg.clip]
            var start = seg.start
            var end = seg.end

            if (ins != null) {
                // 1. Shot boundaries
                nearest(ins.shotBoundaries, start, SHOT_SNAP)?.let { start = it }
                nearest(ins.shotBoundaries, end, SHOT_SNAP)?.let { end = it }
                // 2. Speech: pull start back to the beginning of a sentence, extend end to its finish
                ins.speech.firstOrNull { it.contains(start) }?.let { if (start - it.start <= SPEECH_PULL_START) start = it.start }
                ins.speech.firstOrNull { it.contains(end) }?.let { if (it.end - end <= SPEECH_EXTEND_END) end = min(it.end + 0.15, dur) }
            }
            start = start.coerceIn(0.0, dur)
            end = end.coerceIn(0.0, dur)
            if (end - start < MIN_LEN) { start = seg.start; end = seg.end }

            // 3. Beat: move the end so the cumulative output time lands on the grid (small nudge only)
            if (grid != null) {
                val unit = grid.unitSec
                val natural = outT + (end - start) / seg.speed
                var target = (natural / unit).roundToInt() * unit
                if (target <= outT + MIN_LEN) target = ceil((outT + MIN_LEN) / unit) * unit
                val newEnd = end + (target - natural) * seg.speed
                val insideSpeech = ins?.speech?.any { it.contains(newEnd) && newEnd < it.end - 0.05 && newEnd < end } == true
                val lastSegment = idx == plan.segments.lastIndex
                if (abs(target - natural) <= unit * 0.6 && newEnd <= dur && newEnd - start >= MIN_LEN && !insideSpeech && !lastSegment) {
                    end = newEnd
                }
            }
            outT += (end - start) / seg.speed
            seg.copy(start = round2(start), end = round2(end))
        }.filter { it.end - it.start >= PlanCodec.MIN_SEGMENT_SEC }
        return plan.copy(segments = removeOverlaps(out))
    }

    private fun nearest(points: List<Double>, t: Double, within: Double): Double? =
        points.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= within }

    /** If two consecutive segments from the same clip overlap after snapping, trim the second. */
    private fun removeOverlaps(segs: List<Segment>): List<Segment> {
        val res = mutableListOf<Segment>()
        segs.forEach { s ->
            val prev = res.lastOrNull()
            if (prev != null && prev.clip == s.clip && s.start < prev.end && s.end > prev.end) {
                val fixed = s.copy(start = prev.end)
                if (fixed.end - fixed.start >= PlanCodec.MIN_SEGMENT_SEC) res += fixed
            } else res += s
        }
        return res
    }
}

/** Simple tempo detection on a loudness envelope (one value per [hopSec]). */
object Beat {
    data class Tempo(val bpm: Double, val offsetSec: Double) {
        val beatSec get() = 60.0 / bpm
    }

    fun onsets(env: FloatArray): DoubleArray {
        val log = DoubleArray(env.size) { ln(1e-4 + env[it]) }
        return DoubleArray(env.size) { i -> if (i == 0) 0.0 else max(0.0, log[i] - log[i - 1]) }
    }

    fun detect(env: FloatArray, hopSec: Double): Tempo? {
        if (env.size * hopSec < 6) return null
        val on = onsets(env)
        val minLag = (60.0 / 180.0 / hopSec).toInt()
        val maxLag = (60.0 / 60.0 / hopSec).toInt().coerceAtMost(on.size / 3)
        if (maxLag <= minLag + 2) return null
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in lag until on.size) s += on[i] * on[i - lag]
            ac[lag] = s / (on.size - lag)
        }
        // Prefer tempos near 110 BPM (listeners' "natural" beat), so we don't pick half/double time.
        var best = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 / (lag * hopSec)
            val weight = kotlin.math.exp(-0.5 * (ln(bpm / 110.0) / 0.45).let { it * it })
            val score = ac[lag] * weight
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (bestScore <= 0) return null
        // Parabolic interpolation for a fractional lag (avoids drift over a long song).
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val denom = y0 - 2 * y1 + y2
        val frac = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val lagSec = (best + frac) * hopSec
        // Phase: offset whose beat positions collect the most onset energy.
        val lagFrames = best + frac
        var bestOff = 0; var bestSum = -1.0
        for (off in 0 until best) {
            var s = 0.0
            var k = 0
            while (true) {
                val i = (off + k * lagFrames).roundToInt()
                if (i >= on.size) break
                s += on[i]; k++
            }
            if (s > bestSum) { bestSum = s; bestOff = off }
        }
        return Tempo(60.0 / lagSec, bestOff * hopSec)
    }

    /** Start of the loudest [windowSec] of the song (usually the chorus), snapped to a beat. */
    fun bestStart(env: FloatArray, hopSec: Double, windowSec: Double, tempo: Tempo?): Double {
        val win = (windowSec / hopSec).toInt().coerceAtLeast(1)
        if (env.size <= win) return tempo?.offsetSec ?: 0.0
        var sum = 0.0
        for (i in 0 until win) sum += env[i]
        var bestI = 0; var bestSum = sum
        for (i in 1..env.size - win) {
            sum += env[i + win - 1] - env[i - 1]
            if (sum > bestSum * 1.02) { bestSum = sum; bestI = i }
        }
        var t = bestI * hopSec
        if (tempo != null) {
            val bar = tempo.beatSec * 4
            t = tempo.offsetSec + floor((t - tempo.offsetSec) / bar).coerceAtLeast(0.0) * bar
        }
        return t
    }
}
