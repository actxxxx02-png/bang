package com.promptcut.app.intelligence

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

enum class GamingEventType(val label: String) {
    KILL_FEED("Kill / Elimination"),
    HEADSHOT("Headshot"),
    VICTORY("Victory"),
    DEFEAT("Defeat"),
    SCORE_CHANGE("Score change"),
    EXPLOSION("Explosion / Impact"),
    INTENSE_COMBAT("Intense combat"),
    PLAYER_REACTION("Player reaction"),
}

data class GamingMoment(
    val timeSec: Double,
    val type: GamingEventType,
    val confidence: Float,
    val source: String,
    val detail: String,
)

/**
 * Pure Kotlin gaming & HUD event detector combining OCR text cues, score deltas,
 * motion bursts, luma flashes, face expressions, and audio peaks.
 * Honest attribution: never claims generic image labels alone recognized gameplay events.
 */
object GamingSignals {
    private val killKeywords = Regex("\\b(eliminat(?:ed|ion)|knocked(?:\\s+down)?|kill(?:ed|s)?|double\\s+kill|triple\\s+kill|quadra|ace|squad\\s+wipe|first\\s+blood|assist|\\+\\s*100)\\b")
    private val headshotKeywords = Regex("\\b(headshot|head\\s*shot|critical\\s+hit|one\\s+tap)\\b")
    private val victoryKeywords = Regex("\\b(victory|winner|booyah|chicken\\s+dinner|match\\s+won|mvp|champion|#\\s*1\\b|1st\\s+place)\\b")
    private val defeatKeywords = Regex("\\b(defeat|game\\s+over|eliminated\\s+by|better\\s+luck|you\\s+died|wasted)\\b")
    private val scorePattern = Regex("(?:score|pts|points|kills?|k/d|xp)\\s*[:\\-]?\\s*(\\d{1,6})|\\b(\\d{2,6})\\s*(?:pts|points)\\b")

    fun extractScore(ocrText: String): Int? {
        val m = scorePattern.find(ocrText.lowercase()) ?: return null
        val num = m.groupValues[1].ifBlank { m.groupValues[2] }
        return num.toIntOrNull()
    }

    fun detectFromFrameSignals(
        timeSec: Double,
        ocrText: String,
        prevScore: Int?,
        motion: Double,
        lumaDelta: Double,
        audioLevel: Double,
        hasFace: Boolean,
        smileProb: Float,
    ): Pair<List<GamingMoment>, Int?> {
        val events = mutableListOf<GamingMoment>()
        val lower = ocrText.lowercase().replace("\n", " ")

        if (headshotKeywords.containsMatchIn(lower)) {
            events += GamingMoment(timeSec, GamingEventType.HEADSHOT, 0.90f, "OCR", "HUD headshot text detected")
        }
        if (killKeywords.containsMatchIn(lower)) {
            events += GamingMoment(timeSec, GamingEventType.KILL_FEED, 0.85f, "OCR", "Elimination/kill feed cue")
        }
        if (victoryKeywords.containsMatchIn(lower)) {
            events += GamingMoment(timeSec, GamingEventType.VICTORY, 0.92f, "OCR", "Victory screen text")
        } else if (defeatKeywords.containsMatchIn(lower)) {
            events += GamingMoment(timeSec, GamingEventType.DEFEAT, 0.88f, "OCR", "Defeat / match end text")
        }

        val currentScore = extractScore(lower)
        if (currentScore != null && prevScore != null && currentScore > prevScore) {
            events += GamingMoment(
                timeSec,
                GamingEventType.SCORE_CHANGE,
                0.80f,
                "OCR",
                "Score increased $prevScore -> $currentScore",
            )
        }

        if (lumaDelta > 38.0 && audioLevel > 0.65) {
            events += GamingMoment(timeSec, GamingEventType.EXPLOSION, 0.72f, "motion+audio", "Bright flash with impact audio")
        } else if (motion > 28.0 && audioLevel > 0.55) {
            events += GamingMoment(timeSec, GamingEventType.INTENSE_COMBAT, 0.70f, "motion+audio", "High camera/HUD motion and loud SFX")
        }

        if (hasFace && (smileProb > 0.65f || audioLevel > 0.65)) {
            events += GamingMoment(
                timeSec,
                GamingEventType.PLAYER_REACTION,
                if (smileProb > 0.65f) 0.82f else 0.70f,
                "face+audio",
                if (smileProb > 0.65f) "Smiling/excited player reaction" else "Loud player reaction",
            )
        }

        return events to (currentScore ?: prevScore)
    }
}

/**
 * Android ML Kit OCR runner for sampled frames.
 */
class OcrAndGamingAnalyzer : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractText(bitmap: Bitmap): String {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            Tasks.await(recognizer.process(image)).text.trim()
        }.getOrDefault("")
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}
