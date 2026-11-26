package com.promptcut.app

/**
 * Clear requests in a chat message are applied directly, so they always work even if the AI ignores them.
 * E.g. "make it natural, currently it's warm" → look = Natural (the word after "currently" is the old state).
 */
data class Intents(
    val color: ColorLook? = null,
    val musicOn: Boolean? = null,
    val transition: Transition? = null,
    val noTitle: Boolean = false,
    val speed: SpeedMode? = null,
    val sound: SoundMode? = null,
    val useAll: Boolean? = null,
    val mood: Mood? = null,
)

object QuickIntents {
    private val looks = listOf(
        Regex("black\\s*(and|&|n)\\s*white|\\bb\\s*[&/]\\s*w\\b|monochrome|gr[ae]yscale") to ColorLook.BW,
        Regex("\\bnatural\\b|\\bnormal colou?r|original colou?r|no filter|remove (the )?filter|real colou?r") to ColorLook.NATURAL,
        Regex("\\bwarm") to ColorLook.WARM,
        Regex("\\bcool (tone|look|colou?r)|\\bcold\\b|blue tone|\\bcool\\b") to ColorLook.COOL,
        Regex("vivid|colou?rful|saturat|bright colou?r|punchy colou?r") to ColorLook.VIVID,
        Regex("film look|filmic|cinematic (look|colou?r|grade)") to ColorLook.CINEMATIC,
    )
    private val transitions = listOf(
        Regex("hard cut|no transition|plain cut|without transition|simple cut") to Transition.CUT,
        Regex("dip to black|fade (to|through) black") to Transition.FADE,
        Regex("flash") to Transition.FLASH,
        Regex("zoom") to Transition.ZOOM,
        Regex("smooth|cross\\s*fade|dissolve|soft transition|fade|transition") to Transition.SMOOTH,
    )
    private val moods = listOf(
        Regex("\\bcalm|relax|peaceful|chill|soothing|serene|\\bslow\\b|gentle") to Mood.CALM,
        Regex("energetic|energy|hype|high[- ]energy|fast[- ]paced|upbeat|action|intense|thrilling") to Mood.ENERGETIC,
        Regex("emotional|sentimental|nostalgi|touching|heartfelt|\\bsad\\b") to Mood.EMOTIONAL,
        Regex("cinematic(?! (look|colou?r|grade))|epic|dramatic|movie[- ]like") to Mood.CINEMATIC,
        Regex("funny|comedy|comic|hilarious|playful") to Mood.FUNNY,
        Regex("\\bhappy|cheerful|joyful|joyous|fun\\b") to Mood.HAPPY,
    )
    private val musicOff = Regex("no (background )?music|without (any )?music|remove (the )?(music|song)|mute (the )?music|no song|only (the )?original (sound|audio)")
    private val musicOn = Regex("\\bmusic\\b|\\bsong\\b|\\bbgm\\b|soundtrack|background (track|audio)|\\btrack\\b")
    /** Words that introduce the CURRENT state, not the wanted one ("currently it's warm", "instead of warm"). */
    private val stateMarker = Regex("(currently|right now|now it'?s|it'?s now|instead of|rather than|not so|less|too|remove( the)?|no more|than)\\s+(it'?s\\s+|is\\s+|the\\s+|a\\s+)*$")

    fun parse(text: String): Intents {
        val t = text.lowercase()
        return Intents(
            color = pick(t, looks),
            musicOn = when { musicOff.containsMatchIn(t) -> false; musicOn.containsMatchIn(t) -> true; else -> null },
            transition = pick(t, transitions)?.takeIf { "transition" in t || "effect" in t || "fade" in t || "zoom" in t || "flash" in t || "smooth" in t || "cut" in t || "dissolve" in t },
            noTitle = Regex("no title|remove (the )?title|without (a )?title").containsMatchIn(t),
            speed = when {
                Regex("no (speed|fast)|normal speed|real speed|don'?t speed|not (so )?fast|remove (the )?(speed|fast)|without speed|no 2x|no 4x").containsMatchIn(t) -> SpeedMode.NATURAL
                Regex("slow[- ]?mo|slow motion").containsMatchIn(t) -> SpeedMode.SLOWMO
                Regex("speed (it )?up|fast[- ]?forward|speed ramp|timelapse|time-lapse").containsMatchIn(t) -> SpeedMode.DYNAMIC
                else -> null
            },
            sound = when {
                Regex("mute (the )?(original|clip|video)|remove (the )?original (sound|audio)|only (the )?music|music only|no original|without (the )?original").containsMatchIn(t) -> SoundMode.MUTE
                Regex("keep (the )?(voices?|talking|speech)|only (where|when) (people )?(talk|speak)").containsMatchIn(t) -> SoundMode.VOICES
                Regex("keep (the )?(original|all) (sound|audio)|with (the )?original (sound|audio)").containsMatchIn(t) -> SoundMode.FULL
                else -> null
            },
            useAll = when {
                Regex("(use|include|add) (all|every|each)( (the|my))? (clips?|videos?)|don'?t skip|skipp?ed (some|a|my)|missing (clip|video)").containsMatchIn(t) -> true
                else -> null
            },
            mood = pick(t, moods),
        )
    }

    private fun <T> pick(t: String, rules: List<Pair<Regex, T>>): T? {
        val hits = rules.flatMap { (re, v) -> re.findAll(t).map { it.range.first to v }.toList() }.sortedBy { it.first }
        return hits.firstOrNull { (pos, _) ->
            val before = t.substring(maxOf(0, pos - 24), pos)
            !stateMarker.containsMatchIn(before)
        }?.second
    }
}
