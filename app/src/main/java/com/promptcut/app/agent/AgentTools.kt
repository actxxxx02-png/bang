package com.promptcut.app.agent

import com.promptcut.app.Aspect
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.Mood
import com.promptcut.app.Transition
import com.promptcut.app.ai.AiToolCall
import com.promptcut.app.ai.AiToolSpec
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.timeline.CaptionAnimation
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.OverlayBounds
import com.promptcut.app.timeline.secToUs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strictly typed, validated editing tool invocations.
 * The AI agent can ONLY mutate timelines through these declarative tool types —
 * arbitrary code/script execution is impossible.
 */
sealed class AgentToolInvocation(open val toolName: String) {
    data class InspectClips(val clipIndex: Int? = null) : AgentToolInvocation("inspect_clips")

    data class DetectHighlights(
        val targetDurationSec: Double? = null,
        val mood: Mood? = null,
        val stylePrompt: String = "",
    ) : AgentToolInvocation("detect_highlights")

    data class TrimSegment(
        val itemId: String,
        val startSec: Double,
        val endSec: Double,
    ) : AgentToolInvocation("trim_segment")

    data class SplitSegment(
        val itemId: String,
        val splitAtSec: Double,
    ) : AgentToolInvocation("split_segment")

    data class DeleteSegment(val itemId: String) : AgentToolInvocation("delete_segment")

    data class DuplicateSegment(val itemId: String) : AgentToolInvocation("duplicate_segment")

    data class ReorderSegments(
        val fromIndex: Int,
        val toIndex: Int,
    ) : AgentToolInvocation("reorder_segments")

    data class SetCropResize(
        val itemId: String?,
        val aspect: Aspect?,
        val crop: CropBox?,
        val customWidth: Int? = null,
        val customHeight: Int? = null,
    ) : AgentToolInvocation("set_crop_resize")

    data class GenerateCaptions(
        val style: CaptionStyle = CaptionStyle.SUBTITLE_BAR,
    ) : AgentToolInvocation("generate_captions")

    data class EditCaption(
        val captionId: String?,
        val startSec: Double,
        val endSec: Double,
        val text: String,
        val style: CaptionStyle = CaptionStyle.SUBTITLE_BAR,
    ) : AgentToolInvocation("edit_caption")

    data class StyleCaptions(
        val style: CaptionStyle? = null,
        val font: CaptionFont? = null,
        val fontSizeSp: Int? = null,
        val positionYFraction: Float? = null,
        val animation: CaptionAnimation? = null,
        val autoPunctuate: Boolean = false,
        val highlightKeywords: Boolean? = null,
    ) : AgentToolInvocation("style_captions")

    data class SetTransition(
        val itemId: String?,
        val transition: Transition,
        val durationSec: Double = 0.35,
    ) : AgentToolInvocation("set_transition")

    data class SetLook(
        val colorLook: ColorLook,
        val titleOverlay: String? = null,
        val clearTitle: Boolean = false,
    ) : AgentToolInvocation("set_look")

    data class SetSpeed(
        val itemId: String,
        val speed: Double,
    ) : AgentToolInvocation("set_speed")

    data class SetEffects(
        val itemId: String?,
        val effects: EffectSettings,
    ) : AgentToolInvocation("set_effects")

    data class AddKeyframe(
        val itemId: String,
        val keyframe: Keyframe,
    ) : AgentToolInvocation("add_keyframe")

    data class AddOverlayClip(
        val sourceClipIndex: Int,
        val startSec: Double,
        val endSec: Double,
        val timelineStartSec: Double,
        val bounds: OverlayBounds,
    ) : AgentToolInvocation("add_overlay_clip")

    data class AddSfx(
        val sfxType: String,
        val timelineSec: Double,
        val volume: Float = 0.7f,
    ) : AgentToolInvocation("add_sfx")

    data class TrimSilence(
        val clipIndex: Int? = null,
    ) : AgentToolInvocation("trim_silence")

    data class SyncToBeats(
        val bpm: Double? = null,
    ) : AgentToolInvocation("sync_to_beats")

    data class ConfigureAudioMusic(
        val musicOn: Boolean,
        val musicQuery: String? = null,
        val musicVolume: Float? = null,
        val clipId: String? = null,
        val clipVolume: Float? = null,
        val clipMuted: Boolean? = null,
    ) : AgentToolInvocation("configure_audio_music")

    data class InspectTimelineQa(val targetDurationSec: Double? = null) : AgentToolInvocation("inspect_timeline_qa")
}

object AgentTools {
    val specs: List<AiToolSpec> = listOf(
        AiToolSpec(
            name = "inspect_clips",
            description = "Inspect analyzed metadata, speech, and highlights for clips.",
            parametersJsonSchema = """{"type":"object","properties":{"clip_index":{"type":"integer"}}}""",
        ),
        AiToolSpec(
            name = "detect_highlights",
            description = "Auto-assemble a story edit from detected highlights and story acts.",
            parametersJsonSchema = """{"type":"object","properties":{"target_duration_sec":{"type":"number"},"mood":{"type":"string"},"style_prompt":{"type":"string"}}}""",
        ),
        AiToolSpec(
            name = "trim_segment",
            description = "Trim source start and end seconds of a timeline video clip item.",
            parametersJsonSchema = """{"type":"object","required":["item_id","start_sec","end_sec"],"properties":{"item_id":{"type":"string"},"start_sec":{"type":"number"},"end_sec":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "split_segment",
            description = "Split a timeline video clip item at a timeline timestamp in seconds.",
            parametersJsonSchema = """{"type":"object","required":["item_id","split_at_sec"],"properties":{"item_id":{"type":"string"},"split_at_sec":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "delete_segment",
            description = "Remove a video clip item from the timeline.",
            parametersJsonSchema = """{"type":"object","required":["item_id"],"properties":{"item_id":{"type":"string"}}}""",
        ),
        AiToolSpec(
            name = "duplicate_segment",
            description = "Duplicate an existing timeline clip item right after itself.",
            parametersJsonSchema = """{"type":"object","required":["item_id"],"properties":{"item_id":{"type":"string"}}}""",
        ),
        AiToolSpec(
            name = "reorder_segments",
            description = "Move a timeline clip from from_index to to_index (0-based).",
            parametersJsonSchema = """{"type":"object","required":["from_index","to_index"],"properties":{"from_index":{"type":"integer"},"to_index":{"type":"integer"}}}""",
        ),
        AiToolSpec(
            name = "set_crop_resize",
            description = "Change output aspect ratio (9:16, 4:5, 16:9, 1:1, custom) and/or crop box [x0,y0,x1,y1].",
            parametersJsonSchema = """{"type":"object","properties":{"item_id":{"type":"string"},"aspect":{"type":"string"},"custom_width":{"type":"integer"},"custom_height":{"type":"integer"},"crop":{"type":"array","items":{"type":"number"}}}}""",
        ),
        AiToolSpec(
            name = "generate_captions",
            description = "Generate synchronized captions from detected speech on the timeline.",
            parametersJsonSchema = """{"type":"object","properties":{"style":{"type":"string","enum":["SUBTITLE_BAR","BOLD_BOX","MINIMAL"]}}}""",
        ),
        AiToolSpec(
            name = "edit_caption",
            description = "Add or update a timed caption on the caption track.",
            parametersJsonSchema = """{"type":"object","required":["start_sec","end_sec","text"],"properties":{"caption_id":{"type":"string"},"start_sec":{"type":"number"},"end_sec":{"type":"number"},"text":{"type":"string"},"style":{"type":"string"}}}""",
        ),
        AiToolSpec(
            name = "style_captions",
            description = "Format all captions with style, font, size, safe Y position, animation, punctuation, or keyword highlighting.",
            parametersJsonSchema = """{"type":"object","properties":{"style":{"type":"string"},"font":{"type":"string","enum":["SANS_BOLD","CONDENSED","MONO"]},"font_size_sp":{"type":"integer"},"position_y":{"type":"number"},"animation":{"type":"string","enum":["NONE","POP","FADE","SLIDE_UP"]},"auto_punctuate":{"type":"boolean"},"highlight_keywords":{"type":"boolean"}}}""",
        ),
        AiToolSpec(
            name = "set_transition",
            description = "Set transition style (auto, smooth, fade, flash, zoom, cut) for a clip or all clips.",
            parametersJsonSchema = """{"type":"object","required":["transition"],"properties":{"item_id":{"type":"string"},"transition":{"type":"string"},"duration_sec":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "set_look",
            description = "Set color look (natural, warm, cool, vivid, cinematic, bw) and optional title overlay.",
            parametersJsonSchema = """{"type":"object","required":["color"],"properties":{"color":{"type":"string"},"title":{"type":"string"},"clear_title":{"type":"boolean"}}}""",
        ),
        AiToolSpec(
            name = "set_speed",
            description = "Set playback speed (0.25 to 4.0) for a clip item.",
            parametersJsonSchema = """{"type":"object","required":["item_id","speed"],"properties":{"item_id":{"type":"string"},"speed":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "set_effects",
            description = "Set renderable visual shader effects (brightness, contrast, saturation, blur, sharpen, vignette, film_grain, glow, shake) for a clip or all clips.",
            parametersJsonSchema = """{"type":"object","properties":{"item_id":{"type":"string"},"brightness":{"type":"number"},"contrast":{"type":"number"},"saturation":{"type":"number"},"blur":{"type":"number"},"sharpen":{"type":"number"},"vignette":{"type":"number"},"film_grain":{"type":"number"},"glow":{"type":"number"},"shake":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "add_keyframe",
            description = "Add or update a keyframe (SCALE, OPACITY, VOLUME, PAN_X, PAN_Y) at time_offset_sec inside a clip.",
            parametersJsonSchema = """{"type":"object","required":["item_id","time_offset_sec","property","value"],"properties":{"item_id":{"type":"string"},"time_offset_sec":{"type":"number"},"property":{"type":"string","enum":["SCALE","OPACITY","VOLUME","PAN_X","PAN_Y"]},"value":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "add_overlay_clip",
            description = "Place a picture-in-picture or B-roll overlay clip on Video 2 at timeline_start_sec.",
            parametersJsonSchema = """{"type":"object","required":["clip_index","start_sec","end_sec","timeline_start_sec"],"properties":{"clip_index":{"type":"integer"},"start_sec":{"type":"number"},"end_sec":{"type":"number"},"timeline_start_sec":{"type":"number"},"x":{"type":"number"},"y":{"type":"number"},"width_fraction":{"type":"number"},"opacity":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "add_sfx",
            description = "Insert a built-in synthesized sound effect (whoosh, impact, pop, riser, chime) onto the SFX track.",
            parametersJsonSchema = """{"type":"object","required":["sfx_type","timeline_sec"],"properties":{"sfx_type":{"type":"string","enum":["whoosh","impact","pop","riser","chime"]},"timeline_sec":{"type":"number"},"volume":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "trim_silence",
            description = "Automatically trim detected long silent pauses from clips on the timeline.",
            parametersJsonSchema = """{"type":"object","properties":{"clip_index":{"type":"integer"}}}""",
        ),
        AiToolSpec(
            name = "sync_to_beats",
            description = "Detect or use BPM beat grid and snap main video cut endings onto musical beats.",
            parametersJsonSchema = """{"type":"object","properties":{"bpm":{"type":"number"}}}""",
        ),
        AiToolSpec(
            name = "configure_audio_music",
            description = "Configure music track and/or clip volume/mute.",
            parametersJsonSchema = """{"type":"object","properties":{"music_on":{"type":"boolean"},"music_query":{"type":"string"},"music_volume":{"type":"number"},"item_id":{"type":"string"},"clip_volume":{"type":"number"},"clip_muted":{"type":"boolean"}}}""",
        ),
        AiToolSpec(
            name = "inspect_timeline_qa",
            description = "Check timeline pacing, overlaps, openings, endings, and duration.",
            parametersJsonSchema = """{"type":"object","properties":{"target_duration_sec":{"type":"number"}}}""",
        ),
    )

    val supportedToolNames: Set<String> = specs.map { it.name }.toSet()

    fun parseToolCall(call: AiToolCall): AppResult<AgentToolInvocation> {
        val name = call.name.trim()
        if (name !in supportedToolNames) {
            return AppResult.Failure(AppError.Validation("Unsupported or disallowed tool: $name"))
        }
        val args = runCatching {
            if (call.argumentsJson.isBlank()) JSONObject() else JSONObject(call.argumentsJson)
        }.getOrElse {
            return AppResult.Failure(AppError.Validation("Invalid JSON arguments for tool $name"))
        }

        return when (name) {
            "inspect_clips" -> {
                val idx = if (args.has("clip_index") && !args.isNull("clip_index")) args.optInt("clip_index", -1).takeIf { it >= 0 } else null
                AppResult.Success(AgentToolInvocation.InspectClips(idx))
            }
            "detect_highlights" -> {
                val dur = args.optDouble("target_duration_sec").takeIf { !it.isNaN() && it in 2.0..1800.0 }
                val mood = args.optString("mood", "").takeIf { it.isNotBlank() }?.let { Mood.from(it) }
                AppResult.Success(AgentToolInvocation.DetectHighlights(dur, mood, args.optString("style_prompt", "").take(200)))
            }
            "trim_segment" -> {
                val id = args.optString("item_id", "").trim()
                val s = args.optDouble("start_sec")
                val e = args.optDouble("end_sec")
                if (id.isBlank() || s.isNaN() || e.isNaN() || s < 0.0 || e - s < 0.2) {
                    AppResult.Failure(AppError.Validation("trim_segment requires valid item_id and end_sec >= start_sec + 0.2"))
                } else AppResult.Success(AgentToolInvocation.TrimSegment(id, s, e))
            }
            "split_segment" -> {
                val id = args.optString("item_id", "").trim()
                val at = args.optDouble("split_at_sec")
                if (id.isBlank() || at.isNaN() || at < 0.0) {
                    AppResult.Failure(AppError.Validation("split_segment requires item_id and split_at_sec >= 0"))
                } else AppResult.Success(AgentToolInvocation.SplitSegment(id, at))
            }
            "delete_segment" -> {
                val id = args.optString("item_id", "").trim()
                if (id.isBlank()) AppResult.Failure(AppError.Validation("delete_segment requires item_id"))
                else AppResult.Success(AgentToolInvocation.DeleteSegment(id))
            }
            "duplicate_segment" -> {
                val id = args.optString("item_id", "").trim()
                if (id.isBlank()) AppResult.Failure(AppError.Validation("duplicate_segment requires item_id"))
                else AppResult.Success(AgentToolInvocation.DuplicateSegment(id))
            }
            "reorder_segments" -> {
                val from = args.optInt("from_index", -1)
                val to = args.optInt("to_index", -1)
                if (from < 0 || to < 0) AppResult.Failure(AppError.Validation("reorder_segments requires non-negative indices"))
                else AppResult.Success(AgentToolInvocation.ReorderSegments(from, to))
            }
            "set_crop_resize" -> {
                val id = args.optString("item_id", "").trim().ifBlank { null }
                val aspect = args.optString("aspect", "").takeIf { it.isNotBlank() }?.let { Aspect.from(it) }
                val customW = if (args.has("custom_width")) args.optInt("custom_width", 0).takeIf { it >= 100 } else null
                val customH = if (args.has("custom_height")) args.optInt("custom_height", 0).takeIf { it >= 100 } else null
                val cropArr = args.optJSONArray("crop")
                val crop = if (cropArr != null && cropArr.length() == 4) {
                    CropBox(cropArr.optDouble(0), cropArr.optDouble(1), cropArr.optDouble(2), cropArr.optDouble(3)).normalized()
                } else null
                AppResult.Success(AgentToolInvocation.SetCropResize(id, aspect, crop, customW, customH))
            }
            "generate_captions" -> {
                val st = runCatching { CaptionStyle.valueOf(args.optString("style", "SUBTITLE_BAR").uppercase()) }
                    .getOrDefault(CaptionStyle.SUBTITLE_BAR)
                AppResult.Success(AgentToolInvocation.GenerateCaptions(st))
            }
            "edit_caption" -> {
                val text = args.optString("text", "").trim()
                val s = args.optDouble("start_sec")
                val e = args.optDouble("end_sec")
                if (text.isBlank() || s.isNaN() || e.isNaN() || e <= s) {
                    AppResult.Failure(AppError.Validation("edit_caption requires text and valid start_sec < end_sec"))
                } else {
                    val st = runCatching { CaptionStyle.valueOf(args.optString("style", "SUBTITLE_BAR").uppercase()) }
                        .getOrDefault(CaptionStyle.SUBTITLE_BAR)
                    AppResult.Success(AgentToolInvocation.EditCaption(args.optString("caption_id", "").ifBlank { null }, s, e, text.take(120), st))
                }
            }
            "style_captions" -> {
                val style = args.optString("style", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { CaptionStyle.valueOf(it.uppercase()) }.getOrNull()
                }
                val font = args.optString("font", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { CaptionFont.valueOf(it.uppercase()) }.getOrNull()
                }
                val sizeSp = if (args.has("font_size_sp")) args.optInt("font_size_sp", 18).coerceIn(12, 36) else null
                val posY = if (args.has("position_y")) args.optDouble("position_y", 0.84).toFloat().coerceIn(0.12f, 0.88f) else null
                val anim = args.optString("animation", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { CaptionAnimation.valueOf(it.uppercase()) }.getOrNull()
                }
                val punct = args.optBoolean("auto_punctuate", false)
                val kw = if (args.has("highlight_keywords")) args.optBoolean("highlight_keywords") else null
                AppResult.Success(AgentToolInvocation.StyleCaptions(style, font, sizeSp, posY, anim, punct, kw))
            }
            "set_transition" -> {
                val id = args.optString("item_id", "").trim().ifBlank { null }
                val tr = Transition.from(args.optString("transition", "auto"))
                val dur = args.optDouble("duration_sec", 0.35).let { if (it.isNaN()) 0.35 else it.coerceIn(0.1, 1.5) }
                AppResult.Success(AgentToolInvocation.SetTransition(id, tr, dur))
            }
            "set_look" -> {
                val look = ColorLook.from(args.optString("color", "natural"))
                val title = args.optString("title", "").trim().ifBlank { null }?.take(60)
                val clear = args.optBoolean("clear_title", false)
                AppResult.Success(AgentToolInvocation.SetLook(look, title, clear))
            }
            "set_speed" -> {
                val id = args.optString("item_id", "").trim()
                val speed = args.optDouble("speed")
                if (id.isBlank() || speed.isNaN() || speed !in 0.25..4.0) {
                    AppResult.Failure(AppError.Validation("set_speed requires item_id and speed between 0.25 and 4.0"))
                } else AppResult.Success(AgentToolInvocation.SetSpeed(id, speed))
            }
            "set_effects" -> {
                val id = args.optString("item_id", "").trim().ifBlank { null }
                val effects = EffectSettings(
                    brightness = args.optDouble("brightness", 0.0).toFloat().coerceIn(-0.5f, 0.5f),
                    contrast = args.optDouble("contrast", 0.0).toFloat().coerceIn(-0.5f, 0.8f),
                    saturation = args.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
                    blur = args.optDouble("blur", 0.0).toFloat().coerceIn(0f, 1f),
                    sharpen = args.optDouble("sharpen", 0.0).toFloat().coerceIn(0f, 1f),
                    vignette = args.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f),
                    filmGrain = args.optDouble("film_grain", 0.0).toFloat().coerceIn(0f, 1f),
                    glow = args.optDouble("glow", 0.0).toFloat().coerceIn(0f, 1f),
                    shake = args.optDouble("shake", 0.0).toFloat().coerceIn(0f, 1f),
                )
                AppResult.Success(AgentToolInvocation.SetEffects(id, effects))
            }
            "add_keyframe" -> {
                val id = args.optString("item_id", "").trim()
                val offset = args.optDouble("time_offset_sec")
                val prop = runCatching { KeyframeProperty.valueOf(args.optString("property", "").uppercase()) }.getOrNull()
                val value = args.optDouble("value")
                if (id.isBlank() || offset.isNaN() || offset < 0.0 || prop == null || value.isNaN()) {
                    AppResult.Failure(AppError.Validation("add_keyframe requires item_id, time_offset_sec >= 0, property, and numeric value"))
                } else {
                    AppResult.Success(AgentToolInvocation.AddKeyframe(id, Keyframe(secToUs(offset), prop, value.toFloat())))
                }
            }
            "add_overlay_clip" -> {
                val clipIdx = args.optInt("clip_index", -1)
                val s = args.optDouble("start_sec")
                val e = args.optDouble("end_sec")
                val tl = args.optDouble("timeline_start_sec", 0.0)
                if (clipIdx < 0 || s.isNaN() || e.isNaN() || e - s < 0.2 || tl.isNaN() || tl < 0.0) {
                    AppResult.Failure(AppError.Validation("add_overlay_clip requires valid clip_index, start_sec < end_sec, and timeline_start_sec >= 0"))
                } else {
                    val bounds = OverlayBounds(
                        x = args.optDouble("x", 0.66).toFloat().coerceIn(0f, 0.85f),
                        y = args.optDouble("y", 0.06).toFloat().coerceIn(0f, 0.85f),
                        widthFraction = args.optDouble("width_fraction", 0.30).toFloat().coerceIn(0.15f, 0.90f),
                        opacity = args.optDouble("opacity", 1.0).toFloat().coerceIn(0.1f, 1.0f),
                    )
                    AppResult.Success(AgentToolInvocation.AddOverlayClip(clipIdx, s, e, tl, bounds))
                }
            }
            "add_sfx" -> {
                val sfxType = args.optString("sfx_type", "whoosh").trim()
                val sec = args.optDouble("timeline_sec")
                val vol = args.optDouble("volume", 0.7).toFloat().coerceIn(0.05f, 1.2f)
                if (sec.isNaN() || sec < 0.0) {
                    AppResult.Failure(AppError.Validation("add_sfx requires timeline_sec >= 0"))
                } else {
                    AppResult.Success(AgentToolInvocation.AddSfx(sfxType, sec, vol))
                }
            }
            "trim_silence" -> {
                val idx = if (args.has("clip_index") && !args.isNull("clip_index")) args.optInt("clip_index", -1).takeIf { it >= 0 } else null
                AppResult.Success(AgentToolInvocation.TrimSilence(idx))
            }
            "sync_to_beats" -> {
                val bpm = args.optDouble("bpm").takeIf { !it.isNaN() && it in 50.0..200.0 }
                AppResult.Success(AgentToolInvocation.SyncToBeats(bpm))
            }
            "configure_audio_music" -> {
                val musicOn = args.optBoolean("music_on", true)
                val query = args.optString("music_query", "").trim().ifBlank { null }?.take(60)
                val mv = if (args.has("music_volume")) args.optDouble("music_volume").toFloat().coerceIn(0f, 1f) else null
                val clipId = args.optString("item_id", "").trim().ifBlank { null }
                val cv = if (args.has("clip_volume")) args.optDouble("clip_volume").toFloat().coerceIn(0f, 1.5f) else null
                val muted = if (args.has("clip_muted")) args.optBoolean("clip_muted") else null
                AppResult.Success(AgentToolInvocation.ConfigureAudioMusic(musicOn, query, mv, clipId, cv, muted))
            }
            "inspect_timeline_qa" -> {
                val dur = args.optDouble("target_duration_sec").takeIf { !it.isNaN() && it > 0.0 }
                AppResult.Success(AgentToolInvocation.InspectTimelineQa(dur))
            }
            else -> AppResult.Failure(AppError.Validation("Unknown tool: $name"))
        }
    }

    fun parseToolCallsJsonArray(raw: String): List<AiToolCall> {
        val trimmed = raw.trim()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("tool_calls") ?: obj.optJSONArray("actions") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name", "").trim()
            if (name.isBlank()) return@mapNotNull null
            val args = t.optJSONObject("arguments")?.toString()
                ?: t.optJSONObject("params")?.toString()
                ?: t.optString("arguments_json", "{}")
            AiToolCall(id = "tc_$i", name = name, argumentsJson = args)
        }
    }
}
