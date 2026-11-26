package com.promptcut.app.intelligence

import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.WordTiming
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pure Kotlin caption formatting, safe-area clamping, keyword emphasis,
 * word-level timing interpolation, and SRT/VTT subtitle export.
 */
object CaptionTools {
    private val STOP_WORDS = setOf(
        "the", "and", "that", "this", "with", "from", "your", "have", "what", "when",
        "where", "were", "they", "them", "just", "like", "then", "than", "into", "about",
        "some", "here", "there", "very", "also", "will", "would", "could", "should",
    )

    private val QUESTION_PREFIXES = listOf("who", "what", "when", "where", "why", "how", "is", "are", "do", "did", "can", "could", "would")

    fun clampSafeAreaY(positionY: Float): Float = positionY.coerceIn(0.12f, 0.88f)

    fun autoPunctuate(text: String): String {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return ""
        val withPronoun = cleaned
            .replace(Regex("\\bi\\b"), "I")
            .replace(Regex("\\bi'm\\b", RegexOption.IGNORE_CASE), "I'm")
            .replace(Regex("\\bi've\\b", RegexOption.IGNORE_CASE), "I've")
            .replace(Regex("\\bi'll\\b", RegexOption.IGNORE_CASE), "I'll")
            .replace(Regex("\\bdon't\\b", RegexOption.IGNORE_CASE), "don't")
        val capitalized = withPronoun.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
        val last = capitalized.last()
        if (last in setOf('.', '!', '?', '…')) return capitalized
        val firstWord = capitalized.substringBefore(' ').lowercase(Locale.US)
        val suffix = if (firstWord in QUESTION_PREFIXES && capitalized.split(' ').size >= 3) "?" else "."
        return capitalized + suffix
    }

    fun extractKeywords(text: String, maxKeywords: Int = 3): List<String> {
        val tokens = text.split(Regex("\\s+"))
            .map { it.trim(',', '.', '!', '?', ':', ';', '"', '\'') }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return tokens
            .filter { t ->
                val low = t.lowercase(Locale.US)
                (t.length >= 5 && low !in STOP_WORDS) ||
                    (t.length >= 2 && t == t.uppercase(Locale.US) && t.any { it.isLetter() }) ||
                    t.any { it.isDigit() }
            }
            .distinctBy { it.lowercase(Locale.US) }
            .take(maxKeywords)
    }

    fun interpolateWordTimings(text: String, durationUs: Long): List<WordTiming> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val safeDur = max(250_000L, durationUs)
        val weights = words.map { max(2, it.length) }
        val totalWeight = weights.sum().toDouble()
        var cursor = 0L
        return words.mapIndexed { idx, w ->
            val slice = if (idx == words.lastIndex) {
                (safeDur - cursor).coerceAtLeast(1L)
            } else {
                ((weights[idx] / totalWeight) * safeDur).roundToLong().coerceAtLeast(1L)
            }
            val start = cursor
            val end = (cursor + slice).coerceAtMost(safeDur)
            cursor = end
            WordTiming(word = w, startOffsetUs = start, endOffsetUs = end)
        }
    }

    fun enrichCaption(
        item: CaptionItem,
        applyPunctuation: Boolean = false,
        highlightKeywords: Boolean = true,
    ): CaptionItem {
        val finalText = if (applyPunctuation) autoPunctuate(item.text) else item.text.trim()
        val timings = interpolateWordTimings(finalText, item.durationUs)
        val keywords = if (highlightKeywords) extractKeywords(finalText) else emptyList()
        return item.copy(
            text = finalText,
            wordTimings = timings,
            highlightKeywords = keywords,
            positionYFraction = clampSafeAreaY(item.positionYFraction),
        )
    }

    fun exportSrt(captions: List<CaptionItem>): String = buildString {
        captions.sortedBy { it.startUs }.forEachIndexed { idx, c ->
            appendLine(idx + 1)
            appendLine("${formatTimestamp(c.startUs, ',')} --> ${formatTimestamp(c.endUs, ',')}")
            appendLine(c.text.trim())
            appendLine()
        }
    }

    fun exportVtt(captions: List<CaptionItem>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        captions.sortedBy { it.startUs }.forEach { c ->
            appendLine("${formatTimestamp(c.startUs, '.')} --> ${formatTimestamp(c.endUs, '.')}")
            appendLine(c.text.trim())
            appendLine()
        }
    }

    private fun formatTimestamp(us: Long, millisSep: Char): String {
        val totalMs = (us.coerceAtLeast(0L) / 1_000L)
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", hrs, min, sec, millisSep, ms)
    }
}
