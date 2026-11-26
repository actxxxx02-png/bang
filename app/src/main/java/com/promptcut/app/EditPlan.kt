package com.promptcut.app

import org.json.JSONArray
import org.json.JSONObject

/** What we know about one imported clip. Pure data so it can be unit-tested off-device. */
data class ClipMeta(
    val index: Int,
    val name: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    /** When it was filmed (epoch ms), 0 if unknown. Used to put shots in real order. */
    val recordedAt: Long = 0,
    /** Human-readable understanding of the clip (shots, people, sound, speech). Filled after analysis. */
    val description: String = "",
    /** Area the user wants to show (e.g. only the iPad screen); null = whole frame. */
    val crop: CropBox? = null,
)

enum class Aspect(val key: String, val width: Int, val height: Int, val label: String) {
    PORTRAIT("9:16", 1080, 1920, "Reel 9:16"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Feed 4:5"),
    LANDSCAPE("16:9", 1920, 1080, "Wide 16:9"),
    SQUARE("1:1", 1080, 1080, "Square 1:1"),
    CUSTOM("custom", 1080, 1920, "Custom");

    companion object {
        fun from(value: String?): Aspect = entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: PORTRAIT
    }
}

enum class Mood(val key: String, val label: String, val emoji: String, val bpm: Int, val beatsPerCut: Int) {
    HAPPY("happy", "Happy", "😊", 116, 2),
    ENERGETIC("energetic", "Energetic", "⚡", 126, 2),
    EMOTIONAL("emotional", "Emotional", "🥹", 70, 2),
    CALM("calm", "Calm", "🌿", 76, 2),
    CINEMATIC("cinematic", "Cinematic", "🎬", 84, 2),
    FUNNY("funny", "Funny", "😄", 112, 2);

    companion object {
        fun from(value: String?): Mood = entries.firstOrNull { it.key == value?.trim()?.lowercase() } ?: HAPPY
    }
}

enum class ColorLook(val key: String, val label: String) {
    NATURAL("natural", "Natural"),
    WARM("warm", "Warm"),
    COOL("cool", "Cool"),
    VIVID("vivid", "Vivid"),
    CINEMATIC("cinematic", "Film"),
    BW("bw", "B&W");

    companion object {
        fun from(value: String?): ColorLook = entries.firstOrNull { it.key == value?.trim()?.lowercase() } ?: NATURAL
    }
}

/** How one shot hands over to the next. */
enum class Transition(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    SMOOTH("smooth", "Smooth fade"),
    FADE("fade", "Dip to black"),
    FLASH("flash", "Flash"),
    ZOOM("zoom", "Zoom punch"),
    CUT("cut", "Hard cut");

    companion object {
        fun from(value: String?): Transition = entries.firstOrNull { it.key == value?.trim()?.lowercase() } ?: AUTO

        /** What "Auto" means for each mood. */
        fun forMood(m: Mood): Transition = when (m) {
            Mood.HAPPY, Mood.FUNNY -> ZOOM
            Mood.ENERGETIC -> FLASH
            Mood.EMOTIONAL, Mood.CALM -> SMOOTH
            Mood.CINEMATIC -> FADE
        }
    }
}

data class Segment(
    val clip: Int,
    val start: Double,
    val end: Double,
    val speed: Double = 1.0,
    /** Why the editor chose this moment, e.g. "opening: he walks in". Shown to the user. */
    val why: String = "",
    /** Keep this shot's own sound? null = decided by the Sound setting. */
    val sound: Boolean? = null,
    /** Horizontal point of interest (0 = left edge, 1 = right) for reframing; null = centre. */
    val focusX: Double? = null,
    /** Final volume of the shot's own sound (set by the film rules). */
    val soundGain: Double = 1.0,
) {
    /** Length of this segment in the final video (after speed change). */
    val outputSec: Double get() = (end - start) / speed
}

data class EditPlan(
    val summary: String,
    val aspect: Aspect,
    val title: String?,
    val segments: List<Segment>,
    val mood: Mood = Mood.HAPPY,
    val color: ColorLook = ColorLook.NATURAL,
    /** 0 = mute the clips' own sound, 1 = full. */
    val originalVolume: Double = 1.0,
    val musicVolume: Double = 0.8,
    val transition: Transition = Transition.AUTO,
    /** null = keep the user's music choice; true/false = the request asked to add/remove music. */
    val musicOn: Boolean? = null,
    /** Search words for a free song that fits (e.g. "upbeat acoustic birthday"). */
    val musicQuery: String = "",
) {
    val effectiveTransition: Transition get() = if (transition == Transition.AUTO) Transition.forMood(mood) else transition

    val totalSec: Double get() = segments.sumOf { it.outputSec }

    fun toJson(): String = JSONObject().apply {
        put("summary", summary)
        put("aspect", aspect.key)
        put("title", title ?: "")
        put("mood", mood.key)
        put("color", color.key)
        put("original_volume", round2(originalVolume))
        put("music_volume", round2(musicVolume))
        put("transition", transition.key)
        if (musicQuery.isNotBlank()) put("music_query", musicQuery)
        put("segments", JSONArray().apply {
            segments.forEach { s ->
                put(JSONObject().apply {
                    put("clip", s.clip); put("start", round2(s.start)); put("end", round2(s.end)); put("speed", s.speed)
                    if (s.why.isNotBlank()) put("why", s.why)
                    s.sound?.let { put("sound", if (it) "on" else "off") }
                })
            }
        })
    }.toString()
}

class PlanException(message: String) : Exception(message)

/** The AI's first look at the footage: what it is, and a few ready-made styles for the user to pick from. */
data class Preset(
    val name: String,
    val description: String,
    val mood: Mood,
    val lengthSec: Int,
    val aspect: Aspect,
    val color: ColorLook,
    val instructions: String,
)

data class Suggestions(val understanding: String, val occasion: String, val presets: List<Preset>)

object PlanCodec {
    const val MIN_SEGMENT_SEC = 0.4

    /**
     * Parses the model's reply and repairs what can be repaired (times out of range, bad speeds).
     * Throws [PlanException] with a message we can send back to the model if nothing usable remains.
     */
    fun parse(raw: String, clips: List<ClipMeta>): EditPlan {
        val obj = try {
            JSONObject(JsonRepair.repairObject(raw) ?: extractJson(raw))
        } catch (e: Exception) {
            throw PlanException("Reply was not valid JSON.")
        }
        val arr = obj.optJSONArray("segments") ?: throw PlanException("Missing \"segments\" array.")
        val byIndex = clips.associateBy { it.index }
        val segments = mutableListOf<Segment>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val clip = byIndex[s.optInt("clip", -1)] ?: continue
            val start = s.optDouble("start", 0.0).takeIf { !it.isNaN() }?.coerceIn(0.0, clip.durationSec) ?: 0.0
            val end = s.optDouble("end", clip.durationSec).takeIf { !it.isNaN() }?.coerceIn(0.0, clip.durationSec)
                ?: clip.durationSec
            if (end - start < MIN_SEGMENT_SEC) continue
            val speed = s.optDouble("speed", 1.0).takeIf { !it.isNaN() && it > 0 }?.coerceIn(0.25, 4.0) ?: 1.0
            val snd = when (s.optString("sound").trim().lowercase()) { "on", "true", "yes", "original" -> true; "off", "false", "no", "music" -> false; else -> null }
            segments += Segment(clip.index, start, end, speed, s.optString("why", "").trim().take(80), sound = snd)
        }
        if (segments.isEmpty()) throw PlanException("No usable segments: each needs a valid clip index and end > start within the clip's duration.")
        val title = obj.optString("title", "").trim().take(60).ifBlank { null }
        val originalVolume = when {
            obj.has("original_volume") -> obj.optDouble("original_volume", 1.0)
            obj.optBoolean("remove_original_audio", false) -> 0.0
            else -> 1.0
        }.let { if (it.isNaN()) 1.0 else it.coerceIn(0.0, 1.0) }
        return EditPlan(
            summary = obj.optString("summary", "").trim().ifBlank { "Edited ${segments.size} segments." },
            aspect = Aspect.from(obj.optString("aspect")),
            title = title,
            segments = segments,
            mood = Mood.from(obj.optString("mood")),
            color = ColorLook.from(obj.optString("color")),
            originalVolume = originalVolume,
            musicVolume = obj.optDouble("music_volume", 0.8).let { if (it.isNaN()) 0.8 else it.coerceIn(0.0, 1.0) },
            transition = Transition.from(obj.optString("transition")),
            musicOn = when (obj.optString("music").trim().lowercase()) { "on", "true", "yes" -> true; "off", "false", "no", "none" -> false; else -> null },
            musicQuery = obj.optString("music_query").trim().take(60),
        )
    }

    fun parseSuggestions(raw: String): Suggestions {
        val obj = try { JSONObject(JsonRepair.repairObject(raw) ?: extractJson(raw)) } catch (e: Exception) { throw PlanException("Reply was not valid JSON.") }
        val arr = obj.optJSONArray("presets") ?: JSONArray()
        val presets = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = p.optString("name").trim().ifBlank { return@mapNotNull null }
            Preset(
                name = name.take(40),
                description = p.optString("description").trim().take(140),
                mood = Mood.from(p.optString("mood")),
                lengthSec = p.optInt("length", 30).coerceIn(5, 180),
                aspect = Aspect.from(p.optString("aspect")),
                color = ColorLook.from(p.optString("color")),
                instructions = p.optString("instructions").trim().take(300),
            )
        }.take(4)
        return Suggestions(
            understanding = obj.optString("understanding").trim().take(300),
            occasion = obj.optString("occasion").trim().lowercase().take(30),
            presets = presets,
        )
    }

    /** Models sometimes wrap JSON in ``` fences or add text around it. */
    fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return raw
        return raw.substring(start, end + 1)
    }

    val SYSTEM_PROMPT = """
You are a skilled video editor inside a mobile app. You never touch pixels: you read a detailed description of
the user's clips (shots, people, faces, smiles, objects, sound and speech with timestamps, blurry/dark parts,
best moments) and return an EDIT PLAN as JSON. The app renders it and adds music.

Return ONLY a JSON object with exactly this shape:
{
  "summary": "one or two sentences telling the user the story you built",
  "aspect": "9:16" | "16:9" | "1:1",
  "mood": "happy" | "energetic" | "emotional" | "calm" | "cinematic" | "funny",
  "color": "natural" | "warm" | "cool" | "vivid" | "cinematic" | "bw",
  "transition": "auto" | "smooth" | "fade" | "flash" | "zoom" | "cut",
  "music": "keep" | "on" | "off",
  "music_query": "3-5 words to find a fitting song, e.g. 'upbeat acoustic summer' or 'emotional piano'",
  "title": "short on-screen title for the opening, or empty string",
  "original_volume": 0.0-1.0,
  "music_volume": 0.0-1.0,
  "segments": [ { "clip": 0, "start": 1.5, "end": 4.0, "speed": 1.0, "sound": "on", "why": "opening: she enters the room" } ]
}

HOW TO EDIT WITH MEANING (most important):
- Tell a small story, not a random montage: OPENING (sets the scene or a strong hook) -> BUILD (the event, the
  people, details) -> HIGHLIGHT (the best moments: smiles, laughter, the key action) -> ENDING (a natural close:
  a wave, a hug, a final smile, a wide shot, someone walking away).
- Each segment must be a complete moment. Start slightly before an action and end after it finishes.
  Cut at shot changes or pauses. NEVER cut in the middle of someone speaking: keep whole speech spans.
- Shots marked "interest LOW" (plain ground, road, wall, nothing happening) are dull: never open or end on
  them, and use them only briefly if needed. Open on a HIGH-interest shot or a wide, beautiful view.
- Use the "best moments" and the smiling/laughing/loud-cheering parts. Skip parts marked blurry, dark or shaky
  unless nothing else shows that moment.
- Don't reuse the same seconds twice. Follow the user's order preference (mix scenes / filming order).
  A strong highlight may be a 1-2 second hook at the very start (say "hook" in its "why").
- Rhythm: don't put two shots of the same place or the same look back to back, and don't let one repetitive
  clip (a long ride, the same trees) take more than its fair share.
- Like a real film-maker: trim every clip to its best part, vary wide views and close-ups, give each shot one
  clear purpose, and end on a calm or emotional beat.
- "speed": use 1 unless the user's style allows speed changes (it will say so). Never above 2.
- "sound": "on" keeps the shot's own sound (someone speaking, singing, cheering, laughing), "off" lets only the
  music play. Follow the user's sound preference when given.
- Pacing by mood: energetic 1-2 s per segment; happy/funny 1.5-3 s; emotional/cinematic 3-6 s (slow motion 0.5
  is great on the single best moment); calm 4-8 s.
- The target length is a total of (end-start)/speed; match it within about 10%. If the footage is too short,
  make it shorter rather than padding with weak moments.
- "why" says the segment's role and what happens, in a few words.
- Audio: when background music is used and nobody is speaking, original_volume 0.2 and music_volume 0.8.
  When someone speaks or sings something meaningful (e.g. "happy birthday"), keep original_volume 0.9 and
  music_volume 0.3. With no music, original_volume 1.
- The app adds background music and transitions itself. If the user asks for music/a song, set "music": "on";
  if they want no music, "off"; otherwise "keep". Transitions: smooth = soft fades with a slow zoom (emotional,
  calm), fade = dip to black (cinematic), flash = quick white flash (energetic), zoom = punchy zoom-in (happy,
  funny), cut = plain cuts. Use "auto" unless the user asks for something specific.
- If a current plan is given, APPLY THE USER'S CHANGE: change exactly what they asked (look, mood, music,
  transition, length, order, shots) and keep the rest. The summary must say what you changed.
- Segments: "start"/"end" in seconds inside the clip, end > start, at least 0.8 s; "speed" 0.25-4.
""".trimIndent()

    val SUGGEST_SYSTEM = """
You are a friendly video editor. Look at the description of the user's clips and say what the footage is about,
then offer 3 or 4 different editing styles that fit it. Return ONLY JSON:
{
  "understanding": "one short, warm sentence about what you see, e.g. 'A little boy in a party outfit, smiling and posing at home.'",
  "occasion": "birthday | travel | wedding | family | kids | friends | party | festival | sports | pets | food | vlog | other",
  "presets": [
    { "name": "Cute birthday reel", "description": "Bright and bouncy, quick cuts on the beat",
      "mood": "happy|energetic|emotional|calm|cinematic|funny", "length": 20, "aspect": "9:16|16:9|1:1",
      "color": "natural|warm|cool|vivid|cinematic|bw",
      "instructions": "Open on his smile, show the outfit, end with the wave" }
  ]
}
Make the presets clearly different (e.g. an upbeat reel, an emotional memory, a short funny cut, a cinematic one),
and base "instructions" on specific moments you see in the description. Lengths must not exceed the total footage.
""".trimIndent()

    val SELECT_SYSTEM = """
You are a video editor working on LONG footage. You get a scene-by-scene overview of the user's clips.
First step only: choose the parts worth using for the requested video. Return ONLY JSON:
{ "windows": [ { "clip": 0, "start": 120.0, "end": 185.0, "why": "cake cutting and singing" } ] }
Rules: pick the parts that best serve the request and tell a story (opening, the main events, the best
reactions, a natural ending). Prefer scenes with people, smiles, speech that matters, and action; skip poor
quality. Choose about 3 times the target length in total (at least 60 s), in 3-15 windows, each 10-120 s.
""".trimIndent()

    /** Parses the first-pass selection into padded, merged time windows per clip. */
    fun parseWindows(raw: String, clips: List<ClipMeta>): Map<Int, List<Span>> {
        val obj = try { JSONObject(JsonRepair.repairObject(raw) ?: extractJson(raw)) } catch (e: Exception) { throw PlanException("Reply was not valid JSON.") }
        val arr = obj.optJSONArray("windows") ?: throw PlanException("Missing \"windows\" array.")
        val byIndex = clips.associateBy { it.index }
        val raws = (0 until arr.length()).mapNotNull { i ->
            val w = arr.optJSONObject(i) ?: return@mapNotNull null
            val c = byIndex[w.optInt("clip", -1)] ?: return@mapNotNull null
            val a = (w.optDouble("start", 0.0) - 1.5).coerceAtLeast(0.0)
            val b = (w.optDouble("end", 0.0) + 1.5).coerceAtMost(c.durationSec)
            if (b - a < 1.0) null else c.index to Span(a, b, w.optString("why"))
        }
        if (raws.isEmpty()) throw PlanException("No usable windows.")
        return raws.groupBy({ it.first }, { it.second }).mapValues { (_, spans) ->
            val merged = mutableListOf<Span>()
            spans.sortedBy { it.start }.forEach { s ->
                val last = merged.lastOrNull()
                if (last != null && s.start <= last.end + 2.0) merged[merged.lastIndex] = Span(last.start, maxOf(last.end, s.end), last.text)
                else merged += s
            }
            merged
        }
    }

    fun describeClips(clips: List<ClipMeta>): String = buildString {
        appendLine("CLIPS:")
        clips.forEach { c ->
            if (c.description.isNotBlank()) appendLine(c.description.trimEnd())
            else appendLine("- clip ${c.index}: \"${c.name}\", ${round2(c.durationSec)} s, ${c.width}x${c.height}, audio=${if (c.hasAudio) "yes" else "no"}")
        }
    }

    fun buildUserPrompt(clips: List<ClipMeta>, request: String, currentPlan: EditPlan?, hasFrames: Boolean): String = buildString {
        append(describeClips(clips))
        if (hasFrames) appendLine("Sample frames from each clip follow, each labelled with its clip index and timestamp.")
        if (currentPlan != null) {
            appendLine()
            appendLine("CURRENT PLAN:")
            appendLine(currentPlan.toJson())
        }
        appendLine()
        appendLine("USER REQUEST: $request")
    }

    fun buildSuggestPrompt(clips: List<ClipMeta>, hasFrames: Boolean): String = buildString {
        append(describeClips(clips))
        appendLine("Total footage: ${round2(clips.sumOf { it.durationSec })} s.")
        if (hasFrames) appendLine("Sample frames from each clip follow.")
    }
}

/** Makes slightly broken model output usable: code fences, comments, trailing commas, cut-off endings. */
object JsonRepair {
    /** Returns a string that parses as a JSON object, or null. */
    fun repairObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var s = raw.substring(start)
        tryParse(PlanCodec.extractJson(s))?.let { return it }
        s = stripComments(s)
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")          // trailing commas
        tryParse(PlanCodec.extractJson(s))?.let { return it }
        return tryParse(closeTruncated(s))
    }

    /** Groq's 400 "json_validate_failed" error includes what the model wrote. */
    fun failedGeneration(errorBody: String): String? = runCatching {
        JSONObject(errorBody).optJSONObject("error")?.optString("failed_generation")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun tryParse(s: String): String? = runCatching { JSONObject(s); s }.getOrNull()

    private fun stripComments(s: String): String {
        val out = StringBuilder()
        var i = 0; var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++; continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> { while (i < s.length && s[i] != '\n') i++ }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> { i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 } }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Cut-off answer: drop the unfinished last element and close all open brackets. */
    private fun closeTruncated(s: String): String {
        val stack = ArrayDeque<Char>()
        var inStr = false; var esc = false
        var lastSafe = -1           // position after the last complete value inside a container
        var safeStack: List<Char> = emptyList()
        for ((i, c) in s.withIndex()) {
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeLast(); lastSafe = i + 1; safeStack = stack.toList() }
                // A comma is a safe cut point only between list items or top-level fields,
                // never inside a half-written object (that would invent missing values).
                ',' -> if (stack.lastOrNull() == '[' || stack.size == 1) { lastSafe = i; safeStack = stack.toList() }
            }
        }
        if (lastSafe < 0) return s
        val sb = StringBuilder(s.substring(0, lastSafe).trimEnd().trimEnd(','))
        safeStack.reversed().forEach { sb.append(if (it == '{') '}' else ']') }
        return sb.toString()
    }
}

internal fun round2(v: Double) = Math.round(v * 100.0) / 100.0
internal fun round1(v: Double) = Math.round(v * 10.0) / 10.0
