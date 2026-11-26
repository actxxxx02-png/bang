package com.promptcut.app.timeline

import com.promptcut.app.Aspect
import com.promptcut.app.ClipMeta
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.ExportResolution
import com.promptcut.app.Mood
import com.promptcut.app.PlanCodec
import com.promptcut.app.Transition
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned JSON codec for [TimelineProject] plus non-destructive migration
 * from legacy v1 [EditPlan] and v2 [TimelineProject] JSON strings.
 */
object TimelineCodec {

    fun toJson(project: TimelineProject): String = JSONObject().apply {
        put("id", project.id)
        put("schemaVersion", project.schemaVersion)
        put("name", project.name)
        put("createdAtMs", project.createdAtMs)
        put("updatedAtMs", project.updatedAtMs)
        put("aspect", project.aspect.key)
        put("customAspectWidth", project.customAspectWidth)
        put("customAspectHeight", project.customAspectHeight)
        put("fps", project.fps)
        put("resolution", project.resolution.name)
        put("videoBitrateBps", project.videoBitrateBps)
        put("audioBitrateBps", project.audioBitrateBps)
        put("preferredHevc", project.preferredHevc)
        put("colorLook", project.colorLook.key)
        put("mood", project.mood.key)
        put("titleOverlay", project.titleOverlay ?: "")
        put("summary", project.summary)
        if (project.beatMarkersUs.isNotEmpty()) {
            put("beatMarkersUs", JSONArray(project.beatMarkersUs))
        }
        project.legacyPlanJson?.let { put("legacyPlanJson", it) }

        put("videoTracks", JSONArray().apply {
            project.videoTracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("isMain", t.isMain)
                    put("muted", t.muted)
                    put("visible", t.visible)
                    put("locked", t.locked)
                    put("items", JSONArray().apply {
                        t.items.forEach { item ->
                            put(JSONObject().apply {
                                put("id", item.id)
                                put("sourceClipIndex", item.sourceClipIndex)
                                put("sourceUri", item.sourceUri)
                                put("timelineStartUs", item.timelineStartUs)
                                put("sourceStartUs", item.sourceStartUs)
                                put("sourceEndUs", item.sourceEndUs)
                                put("speed", item.speed)
                                if (item.speedRamp.isNotEmpty()) {
                                    put("speedRamp", JSONArray().apply {
                                        item.speedRamp.forEach { sp ->
                                            put(JSONObject().put("progress", sp.progress.toDouble()).put("speed", sp.speed))
                                        }
                                    })
                                }
                                put("volume", item.volume.toDouble())
                                put("muted", item.muted)
                                item.crop?.let { c ->
                                    put("crop", JSONArray().put(c.x0).put(c.y0).put(c.x1).put(c.y1))
                                }
                                item.focusX?.let { put("focusX", it) }
                                put("transitionIn", item.transitionIn.key)
                                put("transitionDurationUs", item.transitionDurationUs)
                                item.colorLookOverride?.let { put("colorLookOverride", it.key) }
                                if (item.effects.hasActiveAdjustments) {
                                    put("effects", JSONObject().apply {
                                        put("brightness", item.effects.brightness.toDouble())
                                        put("contrast", item.effects.contrast.toDouble())
                                        put("saturation", item.effects.saturation.toDouble())
                                        put("blur", item.effects.blur.toDouble())
                                        put("sharpen", item.effects.sharpen.toDouble())
                                        put("vignette", item.effects.vignette.toDouble())
                                        put("filmGrain", item.effects.filmGrain.toDouble())
                                        put("glow", item.effects.glow.toDouble())
                                        put("shake", item.effects.shake.toDouble())
                                        put("fadeInUs", item.effects.fadeInUs)
                                        put("fadeOutUs", item.effects.fadeOutUs)
                                    })
                                }
                                item.overlayBounds?.let { ob ->
                                    put("overlayBounds", JSONObject().apply {
                                        put("x", ob.x.toDouble())
                                        put("y", ob.y.toDouble())
                                        put("widthFraction", ob.widthFraction.toDouble())
                                        put("opacity", ob.opacity.toDouble())
                                    })
                                }
                                put("why", item.why)
                                put("editedByUser", item.editedByUser)
                                put("keyframes", JSONArray().apply {
                                    item.keyframes.forEach { kf ->
                                        put(JSONObject()
                                            .put("timeOffsetUs", kf.timeOffsetUs)
                                            .put("property", kf.property.name)
                                            .put("value", kf.value.toDouble()))
                                    }
                                })
                            })
                        }
                    })
                })
            }
        })

        put("audioTracks", JSONArray().apply {
            project.audioTracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("type", t.type.name)
                    put("muted", t.muted)
                    put("locked", t.locked)
                    put("volume", t.volume.toDouble())
                    put("voiceEnhance", t.voiceEnhance)
                    t.normalizeTargetDb?.let { put("normalizeTargetDb", it.toDouble()) }
                    put("items", JSONArray().apply {
                        t.items.forEach { item ->
                            put(JSONObject().apply {
                                put("id", item.id)
                                put("title", item.title)
                                put("sourceUri", item.sourceUri)
                                put("trackType", item.trackType.name)
                                put("timelineStartUs", item.timelineStartUs)
                                put("sourceStartUs", item.sourceStartUs)
                                put("durationUs", item.durationUs)
                                put("volume", item.volume.toDouble())
                                put("fadeInUs", item.fadeInUs)
                                put("fadeOutUs", item.fadeOutUs)
                                put("duckUnderSpeech", item.duckUnderSpeech)
                                item.bpm?.let { put("bpm", it) }
                                item.credit?.let { put("credit", it) }
                                put("editedByUser", item.editedByUser)
                            })
                        }
                    })
                })
            }
        })

        put("captionTracks", JSONArray().apply {
            project.captionTracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("visible", t.visible)
                    put("locked", t.locked)
                    put("items", JSONArray().apply {
                        t.items.forEach { c ->
                            put(JSONObject().apply {
                                put("id", c.id)
                                put("startUs", c.startUs)
                                put("endUs", c.endUs)
                                put("text", c.text)
                                put("style", c.style.name)
                                c.speaker?.let { put("speaker", it) }
                                put("editedByUser", c.editedByUser)
                                put("positionYFraction", c.positionYFraction.toDouble())
                                put("font", c.font.name)
                                put("fontSizeSp", c.fontSizeSp)
                                put("strokeWidth", c.strokeWidth.toDouble())
                                put("shadowEnabled", c.shadowEnabled)
                                put("bgAlpha", c.bgAlpha.toDouble())
                                put("animation", c.animation.name)
                                if (c.highlightKeywords.isNotEmpty()) {
                                    put("highlightKeywords", JSONArray(c.highlightKeywords))
                                }
                                if (c.wordTimings.isNotEmpty()) {
                                    put("wordTimings", JSONArray().apply {
                                        c.wordTimings.forEach { w ->
                                            put(JSONObject()
                                                .put("word", w.word)
                                                .put("startOffsetUs", w.startOffsetUs)
                                                .put("endOffsetUs", w.endOffsetUs))
                                        }
                                    })
                                }
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun fromJson(raw: String, clipsForLegacyFallback: List<ClipMeta> = emptyList()): AppResult<TimelineProject> {
        return try {
            val o = JSONObject(raw)
            if (!o.has("videoTracks") && o.has("segments")) {
                val legacyClips = clipsForLegacyFallback.ifEmpty {
                    val segs = o.optJSONArray("segments") ?: JSONArray()
                    val maxEndByClip = mutableMapOf<Int, Double>()
                    for (i in 0 until segs.length()) {
                        val s = segs.optJSONObject(i) ?: continue
                        val c = s.optInt("clip", -1)
                        val e = s.optDouble("end", 10.0)
                        if (c >= 0) maxEndByClip[c] = maxOf(maxEndByClip[c] ?: 0.0, e + 1.0)
                    }
                    maxEndByClip.map { (idx, dur) -> ClipMeta(idx, "clip_$idx.mp4", dur, 1080, 1920, true) }
                }
                val plan = PlanCodec.parse(raw, legacyClips)
                return AppResult.Success(
                    TimelineProject.fromEditPlan(
                        plan = plan,
                        clips = legacyClips,
                        preserveLegacyJson = raw,
                    )
                )
            }

            val vTracks = parseVideoTracks(o.optJSONArray("videoTracks"))
            val aTracks = parseAudioTracks(o.optJSONArray("audioTracks"))
            val cTracks = parseCaptionTracks(o.optJSONArray("captionTracks"))
            val beatsArr = o.optJSONArray("beatMarkersUs")
            val beatMarkers = if (beatsArr != null) {
                (0 until beatsArr.length()).mapNotNull { beatsArr.optLong(it, -1L).takeIf { v -> v >= 0L } }
            } else emptyList()

            AppResult.Success(
                TimelineProject(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    schemaVersion = o.optInt("schemaVersion", TimelineProject.CURRENT_SCHEMA_VERSION),
                    name = o.optString("name", "Untitled Edit"),
                    createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                    updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
                    aspect = Aspect.from(o.optString("aspect")),
                    customAspectWidth = o.optInt("customAspectWidth", 1080).coerceIn(100, 4096),
                    customAspectHeight = o.optInt("customAspectHeight", 1920).coerceIn(100, 4096),
                    fps = o.optInt("fps", 30).coerceIn(24, 60),
                    resolution = runCatching { ExportResolution.valueOf(o.optString("resolution")) }.getOrDefault(ExportResolution.P1080),
                    videoBitrateBps = o.optInt("videoBitrateBps", 8_000_000),
                    audioBitrateBps = o.optInt("audioBitrateBps", 192_000),
                    preferredHevc = o.optBoolean("preferredHevc", false),
                    colorLook = ColorLook.from(o.optString("colorLook")),
                    mood = Mood.from(o.optString("mood")),
                    titleOverlay = o.optString("titleOverlay", "").ifBlank { null },
                    summary = o.optString("summary", ""),
                    beatMarkersUs = beatMarkers,
                    videoTracks = vTracks.ifEmpty { listOf(VideoTrack()) },
                    audioTracks = aTracks.ifEmpty { listOf(AudioTrack()) },
                    captionTracks = cTracks.ifEmpty { listOf(CaptionTrack()) },
                    legacyPlanJson = o.optString("legacyPlanJson", "").ifBlank { null },
                )
            )
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Could not parse project timeline: ${t.message}", cause = t))
        }
    }

    private fun parseVideoTracks(arr: JSONArray?): List<VideoTrack> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val itemsArr = t.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { j ->
                val it = itemsArr.optJSONObject(j) ?: return@mapNotNull null
                val cropArr = it.optJSONArray("crop")
                val crop = if (cropArr != null && cropArr.length() == 4) {
                    CropBox(cropArr.getDouble(0), cropArr.getDouble(1), cropArr.getDouble(2), cropArr.getDouble(3)).normalized()
                } else null
                val kfArr = it.optJSONArray("keyframes") ?: JSONArray()
                val kfs = (0 until kfArr.length()).mapNotNull { k ->
                    val kf = kfArr.optJSONObject(k) ?: return@mapNotNull null
                    val prop = runCatching { KeyframeProperty.valueOf(kf.getString("property")) }.getOrNull() ?: return@mapNotNull null
                    Keyframe(kf.getLong("timeOffsetUs"), prop, kf.getDouble("value").toFloat())
                }
                val srArr = it.optJSONArray("speedRamp")
                val speedRamp = if (srArr != null) {
                    (0 until srArr.length()).mapNotNull { k ->
                        val sp = srArr.optJSONObject(k) ?: return@mapNotNull null
                        SpeedRampPoint(sp.optDouble("progress", 0.0).toFloat(), sp.optDouble("speed", 1.0))
                    }
                } else emptyList()
                val effObj = it.optJSONObject("effects")
                val effects = if (effObj != null) {
                    EffectSettings(
                        brightness = effObj.optDouble("brightness", 0.0).toFloat(),
                        contrast = effObj.optDouble("contrast", 0.0).toFloat(),
                        saturation = effObj.optDouble("saturation", 1.0).toFloat(),
                        blur = effObj.optDouble("blur", 0.0).toFloat(),
                        sharpen = effObj.optDouble("sharpen", 0.0).toFloat(),
                        vignette = effObj.optDouble("vignette", 0.0).toFloat(),
                        filmGrain = effObj.optDouble("filmGrain", 0.0).toFloat(),
                        glow = effObj.optDouble("glow", 0.0).toFloat(),
                        shake = effObj.optDouble("shake", 0.0).toFloat(),
                        fadeInUs = effObj.optLong("fadeInUs", 0L),
                        fadeOutUs = effObj.optLong("fadeOutUs", 0L),
                    )
                } else EffectSettings()
                val obObj = it.optJSONObject("overlayBounds")
                val overlayBounds = if (obObj != null) {
                    OverlayBounds(
                        x = obObj.optDouble("x", 0.66).toFloat(),
                        y = obObj.optDouble("y", 0.06).toFloat(),
                        widthFraction = obObj.optDouble("widthFraction", 0.30).toFloat(),
                        opacity = obObj.optDouble("opacity", 1.0).toFloat(),
                    )
                } else null
                VideoClipItem(
                    id = it.optString("id"),
                    sourceClipIndex = it.optInt("sourceClipIndex"),
                    sourceUri = it.optString("sourceUri"),
                    timelineStartUs = it.optLong("timelineStartUs"),
                    sourceStartUs = it.optLong("sourceStartUs"),
                    sourceEndUs = it.optLong("sourceEndUs"),
                    speed = it.optDouble("speed", 1.0),
                    speedRamp = speedRamp,
                    volume = it.optDouble("volume", 1.0).toFloat(),
                    muted = it.optBoolean("muted", false),
                    crop = crop,
                    focusX = if (it.has("focusX")) it.optDouble("focusX") else null,
                    transitionIn = Transition.from(it.optString("transitionIn")),
                    transitionDurationUs = it.optLong("transitionDurationUs", 350_000L),
                    colorLookOverride = it.optString("colorLookOverride", "").takeIf { s -> s.isNotBlank() }?.let { k -> ColorLook.from(k) },
                    effects = effects,
                    overlayBounds = overlayBounds,
                    keyframes = kfs,
                    why = it.optString("why", ""),
                    editedByUser = it.optBoolean("editedByUser", false),
                )
            }
            VideoTrack(
                id = t.optString("id", "video_main"),
                name = t.optString("name", "Video 1"),
                isMain = t.optBoolean("isMain", true),
                muted = t.optBoolean("muted", false),
                visible = t.optBoolean("visible", true),
                locked = t.optBoolean("locked", false),
                items = items,
            )
        }
    }

    private fun parseAudioTracks(arr: JSONArray?): List<AudioTrack> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val itemsArr = t.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { j ->
                val it = itemsArr.optJSONObject(j) ?: return@mapNotNull null
                AudioClipItem(
                    id = it.optString("id"),
                    title = it.optString("title"),
                    sourceUri = it.optString("sourceUri"),
                    trackType = runCatching { AudioTrackType.valueOf(it.optString("trackType")) }.getOrDefault(AudioTrackType.MUSIC),
                    timelineStartUs = it.optLong("timelineStartUs"),
                    sourceStartUs = it.optLong("sourceStartUs", 0L),
                    durationUs = it.optLong("durationUs"),
                    volume = it.optDouble("volume", 0.8).toFloat(),
                    fadeInUs = it.optLong("fadeInUs", 400_000L),
                    fadeOutUs = it.optLong("fadeOutUs", 1_400_000L),
                    duckUnderSpeech = it.optBoolean("duckUnderSpeech", true),
                    bpm = if (it.has("bpm")) it.optDouble("bpm") else null,
                    credit = it.optString("credit", "").ifBlank { null },
                    editedByUser = it.optBoolean("editedByUser", false),
                )
            }
            AudioTrack(
                id = t.optString("id", "audio_music"),
                name = t.optString("name", "Audio 1"),
                type = runCatching { AudioTrackType.valueOf(t.optString("type")) }.getOrDefault(AudioTrackType.MUSIC),
                muted = t.optBoolean("muted", false),
                locked = t.optBoolean("locked", false),
                volume = t.optDouble("volume", 1.0).toFloat(),
                voiceEnhance = t.optBoolean("voiceEnhance", false),
                normalizeTargetDb = if (t.has("normalizeTargetDb")) t.optDouble("normalizeTargetDb").toFloat() else null,
                items = items,
            )
        }
    }

    private fun parseCaptionTracks(arr: JSONArray?): List<CaptionTrack> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val itemsArr = t.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { j ->
                val c = itemsArr.optJSONObject(j) ?: return@mapNotNull null
                val kwArr = c.optJSONArray("highlightKeywords")
                val keywords = if (kwArr != null) (0 until kwArr.length()).mapNotNull { kwArr.optString(it).takeIf { s -> s.isNotBlank() } } else emptyList()
                val wtArr = c.optJSONArray("wordTimings")
                val wordTimings = if (wtArr != null) {
                    (0 until wtArr.length()).mapNotNull { k ->
                        val w = wtArr.optJSONObject(k) ?: return@mapNotNull null
                        WordTiming(w.optString("word"), w.optLong("startOffsetUs"), w.optLong("endOffsetUs"))
                    }
                } else emptyList()
                CaptionItem(
                    id = c.optString("id"),
                    startUs = c.optLong("startUs"),
                    endUs = c.optLong("endUs"),
                    text = c.optString("text"),
                    style = runCatching { CaptionStyle.valueOf(c.optString("style")) }.getOrDefault(CaptionStyle.SUBTITLE_BAR),
                    speaker = c.optString("speaker", "").ifBlank { null },
                    editedByUser = c.optBoolean("editedByUser", false),
                    wordTimings = wordTimings,
                    highlightKeywords = keywords,
                    positionYFraction = c.optDouble("positionYFraction", 0.84).toFloat(),
                    font = runCatching { CaptionFont.valueOf(c.optString("font")) }.getOrDefault(CaptionFont.SANS_BOLD),
                    fontSizeSp = c.optInt("fontSizeSp", 18),
                    strokeWidth = c.optDouble("strokeWidth", 2.0).toFloat(),
                    shadowEnabled = c.optBoolean("shadowEnabled", true),
                    bgAlpha = c.optDouble("bgAlpha", 0.65).toFloat(),
                    animation = runCatching { CaptionAnimation.valueOf(c.optString("animation")) }.getOrDefault(CaptionAnimation.NONE),
                )
            }
            CaptionTrack(
                id = t.optString("id", "captions_main"),
                name = t.optString("name", "Captions"),
                visible = t.optBoolean("visible", true),
                locked = t.optBoolean("locked", false),
                items = items,
            )
        }
    }
}
