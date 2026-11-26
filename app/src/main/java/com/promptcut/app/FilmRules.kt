package com.promptcut.app

import kotlin.math.max
import kotlin.math.min

enum class SpeedMode(val label: String) {
    NATURAL("No speed changes"), SLOWMO("Slow-motion moments"), DYNAMIC("Speed ramps (max 2×)");
}

enum class OrderMode(val label: String) {
    MIX("Mix scenes"), FILMED("Filming order"), AI("AI's order");
}

enum class SoundMode(val label: String) {
    VOICES("Keep voices & cheers"), MUTE("Mute original"), FULL("Keep all original");
}

/**
 * Editing rules a film-maker would follow, applied after the AI's plan so the result is dependable
 * whatever the AI returns:
 * - speed only as the user allows (never above 2×)
 * - every shot trimmed to a length that fits the mood, centred on its best moment, never cutting a sentence
 * - every clip used at least once (if asked)
 * - shots in the order they were filmed (an opening "hook" may stay first)
 * - the clip's own sound only where it matters (voices, cheers) or as the user chose
 * - reframing point on the people in the shot
 */
object FilmRules {

    /** Shot length range in seconds (on screen) for each mood. */
    fun pace(m: Mood): ClosedFloatingPointRange<Double> = when (m) {
        Mood.ENERGETIC -> 1.0..3.0
        Mood.HAPPY, Mood.FUNNY -> 1.5..4.5
        Mood.EMOTIONAL, Mood.CINEMATIC -> 2.5..7.0
        Mood.CALM -> 3.0..9.0
    }

    private const val MAX_SPEECH_SHOT = 12.0

    fun apply(
        plan: EditPlan,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        speed: SpeedMode,
        sound: SoundMode,
        musicOn: Boolean,
        useAllClips: Boolean,
        order: OrderMode,
        cuts: Map<Int, List<Span>> = emptyMap(),
        targetSec: Double? = null,
        /** No mood picked by the user: keep the AI's shot lengths instead of trimming to a mood's pace. */
        keepAiLengths: Boolean = false,
    ): EditPlan {
        val metas = clips.associateBy { it.index }
        val pace = pace(plan.mood)

        // 1. Speed as allowed.
        var segs = plan.segments.map { s ->
            val sp = when (speed) {
                SpeedMode.NATURAL -> 1.0
                SpeedMode.SLOWMO -> s.speed.coerceIn(0.5, 1.0)
                SpeedMode.DYNAMIC -> s.speed.coerceIn(0.5, 2.0)
            }
            s.copy(speed = sp)
        }

        // 2. Trim long shots to their best part; stretch very short ones a little.
        if (!keepAiLengths) segs = segs.map { s -> trim(s, metas[s.clip]?.durationSec ?: return@map s, insights[s.clip], pace) }

        // 3. Every clip at least once.
        if (useAllClips) {
            val used = segs.map { it.clip }.toSet()
            clips.filter { it.index !in used && it.durationSec >= 0.8 }.forEach { c ->
                segs = segs + bestMoment(c, insights[c.index], pace)
            }
        }

        // 4. Order: mix scenes like a film-maker, or filming order, or keep the AI's order.
        when (order) {
            OrderMode.MIX -> segs = mixScenes(segs, clips, insights, pace)
            OrderMode.FILMED -> if (segs.size > 1) {
                val key = orderKey(clips)
                val hook = segs.first().takeIf { "hook" in it.why.lowercase() }
                val rest = (if (hook != null) segs.drop(1) else segs).sortedWith(compareBy({ key[it.clip] ?: Long.MAX_VALUE }, { it.start }))
                segs = listOfNotNull(hook) + rest
            }
            OrderMode.AI -> {}
        }

        // Parts the user asked to remove are never shown, whatever the AI chose.
        segs = TimeCuts.apply(segs, cuts)

        // The length the user asked for is met with real footage (trimming must not shrink a 20 s request to 9 s).
        if (targetSec != null) segs = fitLength(segs, targetSec, metas, cuts, insights, pace)

        // 5. Sound per shot, and 6. reframing point.
        segs = segs.map { s ->
            val ins = insights[s.clip]
            val hasAudio = metas[s.clip]?.hasAudio == true
            val gain = when {
                !hasAudio -> 0.0
                !musicOn -> 1.0
                sound == SoundMode.MUTE -> 0.0
                sound == SoundMode.FULL -> 0.8
                s.sound != null -> if (s.sound) 1.0 else 0.0
                else -> if (meaningfulSound(s, ins)) 1.0 else 0.0
            }
            val mid = (s.start + s.end) / 2
            val focus = ins?.shots?.firstOrNull { mid >= it.start && mid <= it.end }?.focusX
            s.copy(soundGain = gain, focusX = focus)
        }

        return plan.copy(segments = segs, originalVolume = 1.0)
    }

    // ------------------------------------------------------------------ length

    /**
     * Brings the edit to [target] seconds on screen:
     * too short → first lets the chosen shots run longer into the footage around them, then adds unused footage;
     * too long → shortens the longest shots around their centre, then drops the least interesting ones.
     * Removed parts are never used. If there isn't enough usable footage, uses all of it.
     */
    fun fitLength(
        input: List<Segment>, target: Double, metas: Map<Int, ClipMeta>, cuts: Map<Int, List<Span>>,
        insights: Map<Int, ClipInsight>, pace: ClosedFloatingPointRange<Double>,
    ): List<Segment> {
        if (input.isEmpty()) return input
        val segs = input.toMutableList()
        fun total() = segs.sumOf { it.outputSec }
        val tol = max(0.6, target * 0.03)

        /** Usable source time of a clip: not cut by the user and not used by another shot (except [self]). */
        fun freeAround(i: Int): Pair<Double, Double> {
            val s = segs[i]
            val dur = metas[s.clip]?.durationSec ?: return s.start to s.end
            var lo = 0.0; var hi = dur
            (cuts[s.clip].orEmpty().map { it.start to it.end } +
                segs.filterIndexed { j, o -> j != i && o.clip == s.clip }.map { it.start to it.end }).forEach { (a, b) ->
                if (b <= s.start + 1e-6) lo = max(lo, b)
                if (a >= s.end - 1e-6) hi = min(hi, a)
            }
            return lo to hi
        }

        if (total() < target - tol) {
            // 1. Let shots run longer (up to a comfortable shot length), a bit at a time across all shots.
            val maxShot = max(pace.endInclusive * 2.5, 10.0)
            repeat(4) {
                for (i in segs.indices) {
                    val need = target - total()
                    if (need <= 0.05) break
                    val s = segs[i]
                    val (lo, hi) = freeAround(i)
                    val share = need / (segs.size - i).coerceAtLeast(1) * s.speed
                    val cap = (maxShot - s.outputSec).coerceAtLeast(0.0) * s.speed
                    val grow = min(share, cap)
                    // Grow on both sides (the AI's lead-in often got trimmed), using the other side if one is blocked.
                    var before = min(grow / 2, s.start - lo).coerceAtLeast(0.0)
                    val after = min(grow - before, hi - s.end).coerceAtLeast(0.0)
                    before = min(grow - after, s.start - lo).coerceAtLeast(0.0)
                    segs[i] = s.copy(start = round2(s.start - max(0.0, before)), end = round2(s.end + max(0.0, after)))
                }
            }
            // 2. Still short: add unused footage (largest free stretches first), in filming order within the clip.
            var guard = 0
            while (total() < target - tol && guard++ < 40) {
                val free = metas.values.flatMap { m ->
                    val taken = (cuts[m.index].orEmpty().map { it.start to it.end } +
                        segs.filter { it.clip == m.index }.map { it.start to it.end }).sortedBy { it.first }
                    val gaps = mutableListOf<Pair<Double, Double>>()
                    var at = 0.0
                    taken.forEach { (a, b) -> if (a - at > 0.8) gaps += at to a; at = max(at, b) }
                    if (m.durationSec - at > 0.8) gaps += at to m.durationSec
                    gaps.map { Triple(m.index, it.first, it.second) }
                }.maxByOrNull { it.third - it.second } ?: break
                val (clip, a, b) = free
                val len = min(min(b - a, max(pace.endInclusive, 3.0)), target - total() + 0.05).coerceAtLeast(min(0.8, b - a))
                val start = a + ((b - a) - len) / 2
                val seg = Segment(clip, round2(start), round2(start + len), 1.0, "added to reach the length you asked for")
                val after = segs.indexOfLast { it.clip == clip && it.start < start }
                val pos = if (after >= 0) after + 1 else segs.indexOfFirst { it.clip == clip }.let { if (it >= 0) it else segs.size }
                segs.add(pos, seg)
            }
        } else if (total() > target + tol) {
            // 1. Shorten the longest shots around their centre (never below the mood's shortest shot).
            val floor = max(pace.start, 1.0)
            repeat(6) {
                val over = total() - target
                if (over <= 0.05) return@repeat
                val order = segs.indices.sortedByDescending { segs[it].outputSec }
                for (i in order) {
                    val extra = total() - target
                    if (extra <= 0.05) break
                    val s = segs[i]
                    // Take at most half of what this shot can lose per round, so the trimming is shared.
                    val cut = min(extra, (s.outputSec - floor) / 2 + 0.05).coerceIn(0.0, s.outputSec - floor) * s.speed
                    if (cut <= 0.01) continue
                    segs[i] = s.copy(start = round2(s.start + cut / 2), end = round2(s.end - cut / 2))
                }
            }
            // 2. Still long: drop the least interesting shots (keeping the opening and one shot per clip).
            while (total() > target + tol && segs.size > 1) {
                val perClip = segs.groupingBy { it.clip }.eachCount()
                val victim = segs.indices.drop(1).filter { (perClip[segs[it].clip] ?: 0) > 1 }
                    .minByOrNull { interestOf(segs[it], insights) } ?: break
                if (total() - segs[victim].outputSec < target - tol) break
                segs.removeAt(victim)
            }
        }
        return segs
    }

    // ------------------------------------------------------------------ scene mixing

    /**
     * Groups clips into scenes: normally one clip = one scene, but clips showing the same kind of thing
     * (their main recognised objects overlap a lot, e.g. two roadside rides) count as one scene.
     */
    fun sceneGroups(clips: List<ClipMeta>, insights: Map<Int, ClipInsight>): Map<Int, Int> {
        val labels = clips.associate { c ->
            c.index to (insights[c.index]?.shots.orEmpty().flatMap { it.labels }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key }.toSet())
        }
        val group = HashMap<Int, Int>()
        val reps = mutableListOf<Pair<Int, Set<String>>>()   // group id → labels
        clips.forEach { c ->
            val l = labels[c.index].orEmpty()
            val match = reps.firstOrNull { (_, rl) ->
                l.size >= 2 && rl.size >= 2 && (l intersect rl).size.toDouble() / (l union rl).size >= 0.6
            }
            if (match != null) group[c.index] = match.first
            else { reps += c.index to l; group[c.index] = c.index }
        }
        return group
    }

    /**
     * Film-maker ordering: open on the main setting, then alternate different scenes (greenery → train →
     * water → greenery again …), never the same scene twice in a row if avoidable, and give scenes with
     * lots of repetitive footage less screen time.
     */
    fun mixScenes(input: List<Segment>, clips: List<ClipMeta>, insights: Map<Int, ClipInsight>, pace: ClosedFloatingPointRange<Double>): List<Segment> {
        if (input.size <= 2) return input
        // One scene only (e.g. a single clip): nothing to alternate with, keep the story in time order.
        if (input.map { sceneGroups(clips, insights)[it.clip] ?: it.clip }.distinct().size < 2) {
            val hook = input.first().takeIf { "hook" in it.why.lowercase() }
            return listOfNotNull(hook) + (if (hook != null) input.drop(1) else input).sortedWith(compareBy({ it.clip }, { it.start }))
        }
        val group = sceneGroups(clips, insights)
        val filmed = orderKey(clips)
        fun g(s: Segment) = group[s.clip] ?: s.clip
        var segs = input

        // Balance: no scene may take much more than its fair share of the video.
        val groups = segs.groupBy { g(it) }
        if (groups.size >= 2) {
            val totals = groups.mapValues { (_, v) -> v.sumOf { it.outputSec } }
            val keep = mutableListOf<Segment>()
            groups.forEach { (gid, gs) ->
                // Fair share = 1.5× what a typical OTHER scene gets (so a long ride can't inflate its own share).
                val others = totals.filterKeys { it != gid }.values.sorted()
                val typical = if (others.isEmpty()) totals[gid]!! else others[others.size / 2]
                val cap = maxOf(typical * 1.5, pace.start * minOf(gs.size, 2))
                var list = gs.sortedByDescending { interestOf(it, insights) }
                // Drop the weakest shots of an over-long scene (keep at least two if it has them, for a reprise).
                while (list.size > 2 && list.sumOf { it.outputSec } > cap) list = list.dropLast(1)
                // Still too long: shorten its longest shots, not below the mood's minimum.
                var over = list.sumOf { it.outputSec } - cap
                list = list.sortedByDescending { it.outputSec }.map { s ->
                    if (over <= 0) s else {
                        val cut = minOf(over, s.outputSec - pace.start).coerceAtLeast(0.0)
                        over -= cut
                        val centre = (s.start + s.end) / 2
                        val half = (s.outputSec - cut) * s.speed / 2
                        s.copy(start = round2(centre - half), end = round2(centre + half))
                    }
                }
                keep += list
            }
            segs = keep
        }

        // Main setting = the scene with the most footage; it opens the video and comes back later.
        val main0 = segs.groupBy { g(it) }.maxByOrNull { (_, v) -> v.sumOf { it.outputSec } }!!.key
        val mainSegs = segs.filter { g(it) == main0 }
        val sceneCount = segs.map { g(it) }.distinct().size
        if (mainSegs.size == 1 && sceneCount >= 3) {
            // Only one shot of the main place: take a short second look from another part of that clip.
            val s0 = mainSegs[0]
            val c = clips.firstOrNull { it.index == s0.clip }
            val ins = insights[s0.clip]
            if (c != null) {
                val len = pace.start
                val away = maxOf(3.0, c.durationSec * 0.25)
                val mid0 = (s0.start + s0.end) / 2
                val candidates = (ins?.highlights?.map { it.time } ?: emptyList()) +
                    (ins?.shots?.filter { it.interest > 0 }?.map { (it.start + it.end) / 2 } ?: emptyList()) +
                    listOf(c.durationSec * 0.2, c.durationSec * 0.8)
                val t = candidates.filter { kotlin.math.abs(it - mid0) >= away }.maxByOrNull { kotlin.math.abs(it - mid0) }
                if (t != null && c.durationSec >= len * 3) {
                    val a = (t - len / 2).coerceIn(0.0, c.durationSec - len)
                    if (a + len <= s0.start || a >= s0.end) segs = segs + Segment(s0.clip, round2(a), round2(a + len), 1.0, "back to the main place",
                        sound = s0.sound)
                }
            }
        }
        val byGroup = segs.groupBy { g(it) }.mapValues { (_, v) -> v.sortedWith(compareBy({ filmed[it.clip] ?: 0L }, { it.start })).toMutableList() }
        val main = main0
        val hook = input.first().takeIf { "hook" in it.why.lowercase() && segs.contains(it) }
        val opening = hook ?: byGroup[main]!!.first()
        byGroup[g(opening)]!!.remove(opening)

        // The main place's other shots are spread out later (a return to the setting), not right after the opening.
        val mainRest = if (g(opening) == main) byGroup[main]!!.toMutableList().also { byGroup[main]!!.clear() } else mutableListOf()

        // Interleave the other scenes: always a different scene than the previous one,
        // preferring the scene with the most shots left (so nothing bunches up at the end).
        val body = mutableListOf<Segment>()
        while (byGroup.values.any { it.isNotEmpty() }) {
            val last = body.lastOrNull()?.let { g(it) } ?: g(opening)
            val candidates = byGroup.filter { it.value.isNotEmpty() }
            val pick = candidates.filter { it.key != last }.ifEmpty { candidates }
                .maxWithOrNull(compareBy<Map.Entry<Int, MutableList<Segment>>>({ it.value.size }, { -(filmed[it.value.first().clip] ?: 0L) }))!!
            body += pick.value.removeAt(0)
        }
        // Slot the main place back in at evenly spaced points in the second part of the video.
        val k = mainRest.size
        mainRest.forEachIndexed { j, seg ->
            val n = body.size
            if (n == 0) { body.add(seg); return@forEachIndexed }
            var pos = (n * (0.5 + 0.5 * (j + 1) / (k + 1))).toInt().coerceIn(1, n)
            // avoid landing next to another shot of the same place
            while (pos < n && (g(body[pos - 1]) == main || g(body.getOrNull(pos) ?: body[pos - 1]) == main)) pos++
            body.add(pos, seg)
        }
        return listOf(opening) + body
    }

    private fun interestOf(s: Segment, insights: Map<Int, ClipInsight>): Double {
        val ins = insights[s.clip] ?: return 1.0
        val mid = (s.start + s.end) / 2
        val shot = ins.shots.firstOrNull { mid >= it.start && mid <= it.end }
        val hl = ins.highlights.filter { it.time in s.start..s.end }.maxOfOrNull { it.score } ?: 0.0
        return (shot?.interest ?: 1) * 2.0 + hl
    }

    /** True if people talk, sing, cheer or laugh during a good part of this shot. */
    fun meaningfulSound(s: Segment, ins: ClipInsight?): Boolean {
        if (ins == null) return false
        val len = (s.end - s.start).coerceAtLeast(0.01)
        fun covered(spans: List<Span>) = spans.sumOf { max(0.0, min(it.end, s.end) - max(it.start, s.start)) } / len
        return covered(ins.speech) >= 0.25 || covered(ins.loud) >= 0.35
    }

    private fun trim(s: Segment, clipDur: Double, ins: ClipInsight?, pace: ClosedFloatingPointRange<Double>): Segment {
        val maxSrc = pace.endInclusive * s.speed
        val minSrc = pace.start * s.speed
        var a = s.start; var b = s.end
        if (b - a > maxSrc) {
            // Centre on the best moment inside the shot (or a sentence, or the middle).
            val best = ins?.highlights?.filter { it.time in a..b }?.maxByOrNull { it.score }?.time
                ?: ins?.speech?.firstOrNull { it.start >= a && it.end <= b }?.let { (it.start + it.end) / 2 }
                ?: (a + b) / 2
            var na = (best - maxSrc / 2).coerceIn(a, b - maxSrc)
            var nb = na + maxSrc
            // Never cut a sentence: widen to include it (up to a limit).
            ins?.speech?.forEach { sp ->
                if (sp.start < nb && sp.end > na && sp.end - sp.start <= MAX_SPEECH_SHOT) {
                    na = min(na, max(sp.start - 0.2, a)); nb = max(nb, min(sp.end + 0.2, b))
                }
            }
            a = na; b = nb
        } else if (b - a < minSrc) {
            val need = minSrc - (b - a)
            b = min(clipDur, b + need / 2)
            a = max(0.0, b - minSrc).coerceAtMost(a)
        }
        return s.copy(start = round2(a), end = round2(b))
    }

    private fun bestMoment(c: ClipMeta, ins: ClipInsight?, pace: ClosedFloatingPointRange<Double>): Segment {
        val len = min((pace.start + pace.endInclusive) / 2, c.durationSec)
        val best = ins?.highlights?.maxByOrNull { it.score }
        val goodShot = ins?.shots?.filter { it.interest > 0 }?.maxByOrNull { it.interest * 10 + (it.end - it.start) }
        val centre = best?.time ?: goodShot?.let { (it.start + it.end) / 2 } ?: (c.durationSec / 2)
        val a = (centre - len / 2).coerceIn(0.0, max(0.0, c.durationSec - len))
        val why = "added so every clip appears" + (best?.let { ": ${it.reason}" } ?: "")
        return Segment(c.index, round2(a), round2(a + len), 1.0, why)
    }

    /** Filming time if every clip has one, otherwise the order the user picked them. */
    fun orderKey(clips: List<ClipMeta>): Map<Int, Long> =
        if (clips.all { it.recordedAt > 0 }) clips.associate { it.index to it.recordedAt }
        else clips.associate { it.index to it.index.toLong() }
}
