package com.promptcut.app

import kotlin.math.max
import kotlin.math.min

/** A crop area inside a clip, as fractions of the (upright) frame: 0,0 = top-left, 1,1 = bottom-right. */
data class CropBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    fun normalized(): CropBox? {
        val a = CropBox(min(x0, x1).coerceIn(0.0, 1.0), min(y0, y1).coerceIn(0.0, 1.0), max(x0, x1).coerceIn(0.0, 1.0), max(y0, y1).coerceIn(0.0, 1.0))
        return a.takeIf { it.width >= 0.08 && it.height >= 0.08 && !(it.width > 0.97 && it.height > 0.97) }
    }

    companion object {
        /** Gemini-style box [ymin, xmin, ymax, xmax] on a 0-1000 scale. */
        fun fromBox1000(b: List<Double>): CropBox? =
            if (b.size != 4) null else CropBox(b[1] / 1000, b[0] / 1000, b[3] / 1000, b[2] / 1000).normalized()
    }
}

/**
 * Understands precise cut requests written in chat, so they happen exactly (not left to the AI):
 *  "remove 10 sec to 13 sec", "cut from 0:10 to 0:13", "remove the last 3 seconds", "skip the first 5 s",
 *  "keep only 5 to 20 s", "in clip 2 remove 4-6 s".
 */
object TimeCuts {
    private const val NUM = "(\\d+(?:[.:]\\d+)?)"
    private const val UNIT = "\\s*(?:s|sec|secs|second|seconds)?"
    private val range = Regex("(?:from\\s+|between\\s+)?$NUM$UNIT\\s*(?:to|-|–|till|until|and)\\s*$NUM$UNIT")
    private val removeVerb = Regex("remove|cut|delete|skip|drop|exclude|without|don'?t (use|show|include)|avoid|trim out|get rid")
    private val keepOnly = Regex("(keep|use|show) only|only (keep|use|show)")
    private val last = Regex("(?:last|final|ending|end)\\s+$NUM$UNIT")
    private val first = Regex("(?:first|starting|opening|beginning)\\s+$NUM$UNIT")
    private val clipRef = Regex("(?:clip|video|#)\\s*#?(\\d+)")

    data class Result(val cuts: Map<Int, List<Span>>, val understood: List<String>)

    private fun num(s: String): Double =
        if (':' in s) s.split(':').let { it[0].toDouble() * 60 + it[1].toDouble() } else s.toDouble()

    /**
     * [durations]: clip index → length. A request without "clip N" applies to the only clip, or is left to the AI
     * when there are several clips (we can't know which one is meant).
     */
    fun parse(text: String, durations: Map<Int, Double>): Result {
        val t = text.lowercase()
        val cuts = HashMap<Int, MutableList<Span>>()
        val notes = mutableListOf<String>()
        // Split into sentences/clauses so "clip 2 …" applies to its own clause.
        t.split(Regex("[.;\\n]")).forEach { clause ->
            val clip = clipRef.find(clause)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in durations }
                ?: durations.keys.singleOrNull() ?: return@forEach
            val dur = durations[clip] ?: return@forEach
            val list = cuts.getOrPut(clip) { mutableListOf() }
            val remove = removeVerb.containsMatchIn(clause)
            val keep = keepOnly.containsMatchIn(clause)
            if (remove || keep) {
                range.findAll(clause).forEach { m ->
                    var a = num(m.groupValues[1]); var b = num(m.groupValues[2])
                    if (b < a) { val x = a; a = b; b = x }
                    a = a.coerceIn(0.0, dur); b = b.coerceIn(0.0, dur)
                    if (b - a < 0.1) return@forEach
                    if (keep && !remove) {
                        if (a > 0.05) list += Span(0.0, a, "outside kept part")
                        if (b < dur - 0.05) list += Span(b, dur, "outside kept part")
                        notes += "clip $clip: keep only ${round1(a)}–${round1(b)} s"
                    } else {
                        list += Span(a, b, "removed by you")
                        notes += "clip $clip: remove ${round1(a)}–${round1(b)} s"
                    }
                }
            }
            if (remove) {
                last.find(clause)?.let { m ->
                    val n = num(m.groupValues[1]).coerceIn(0.0, dur)
                    if (n > 0.1) { list += Span(dur - n, dur, "removed by you"); notes += "clip $clip: remove last ${round1(n)} s" }
                }
                first.find(clause)?.let { m ->
                    val n = num(m.groupValues[1]).coerceIn(0.0, dur)
                    if (n > 0.1) { list += Span(0.0, n, "removed by you"); notes += "clip $clip: remove first ${round1(n)} s" }
                }
            }
        }
        return Result(cuts.filterValues { it.isNotEmpty() }.mapValues { merge(it.value) }, notes)
    }

    fun merge(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        spans.sortedBy { it.start }.forEach { s ->
            val l = out.lastOrNull()
            if (l != null && s.start <= l.end + 0.05) out[out.lastIndex] = Span(l.start, max(l.end, s.end), l.text) else out += s
        }
        return out
    }

    /** Removes the cut parts from every segment; a segment split in two keeps both pieces if long enough. */
    fun apply(segs: List<Segment>, cuts: Map<Int, List<Span>>, minLen: Double = 0.6): List<Segment> {
        if (cuts.isEmpty()) return segs
        return segs.flatMap { s ->
            var pieces = listOf(s.start to s.end)
            cuts[s.clip].orEmpty().forEach { c ->
                pieces = pieces.flatMap { (a, b) ->
                    if (c.end <= a || c.start >= b) listOf(a to b)
                    else listOfNotNull((a to c.start).takeIf { c.start > a }, (c.end to b).takeIf { c.end < b })
                }
            }
            pieces.filter { (a, b) -> b - a >= minLen }.map { (a, b) -> s.copy(start = round2(a), end = round2(b)) }
        }
    }

    /**
     * The length the user asked for: "make a 20 seconds video", "20 sec calm reel", "make it 1 minute", "30s long",
     * "length 45 s". Time ranges for cutting ("10 sec to 13 sec") are not lengths.
     */
    fun targetLength(text: String): Double? {
        val t = text.lowercase().replace(range, " ")
        val unit = "(sec(?:ond)?s?|s|min(?:ute)?s?|mins?)"
        val pats = listOf(
            Regex("(\\d+(?:\\.\\d+)?)\\s*-?\\s*$unit\\b(?:\\s+[a-z]+){0,2}?\\s+(?:video|reel|clip|edit|film|short|story|montage|vlog)\\b"),
            Regex("(\\d+(?:\\.\\d+)?)\\s*-?\\s*$unit\\s+long\\b"),
            Regex("(?:length|duration|make it|keep it|total|limit it to|under|within|around|about)\\s*(?:of|:|to|=|is)?\\s*(?:about\\s+|around\\s+|exactly\\s+)?(\\d+(?:\\.\\d+)?)\\s*-?\\s*$unit\\b"),
        )
        for (re in pats) {
            val m = re.find(t) ?: continue
            val n = m.groupValues[1].toDoubleOrNull() ?: continue
            val sec = if (m.groupValues[2].startsWith("m")) n * 60 else n
            if (sec in 3.0..1800.0) return sec
        }
        return null
    }

    /** Asks for an object crop: "crop only the ipad", "focus on the tv", "zoom in on the cake". */
    private val cropAsk = Regex("(?:crop|focus|zoom(?: in)?|frame)\\s+(?:only\\s+)?(?:on\\s+|to\\s+|in on\\s+)?(?:only\\s+)?(?:the\\s+|my\\s+)?([a-z][a-z ]{1,24}?)(?:\\s+(?:only|screen|and|in|from|please)\\b|[.,;!]|$)")
    fun cropTarget(text: String): String? {
        val m = cropAsk.find(text.lowercase()) ?: return null
        val what = m.groupValues[1].trim()
        if (what.isEmpty() || what in setOf("it", "this", "that", "video", "clip", "clips", "videos", "out")) return null
        return what
    }
}
