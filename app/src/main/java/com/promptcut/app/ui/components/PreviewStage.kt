package com.promptcut.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.promptcut.app.Clip
import com.promptcut.app.ColorLook
import com.promptcut.app.EditorMediaHelper
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.OverlayBounds
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val FRAME_STEP_US = 33_333L

@AndroidXOptIn(UnstableApi::class)
@Composable
fun PreviewStage(
    project: TimelineProject,
    clips: List<Clip>,
    renderedOutput: File?,
    playheadUs: Long,
    isPlaying: Boolean,
    preview720p: Boolean,
    fullEffectsPreview: Boolean,
    previewSpeed: Float,
    showSafeGuides: Boolean,
    compareOriginal: Boolean,
    audioMonitorMuted: Boolean,
    onPlayheadChange: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPreviewSpeedChange: (Float) -> Unit,
    onToggleSafeGuides: () -> Unit,
    onToggleCompareOriginal: () -> Unit,
    onToggleAudioMute: () -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val totalUs = project.totalDurationUs.coerceAtLeast(0L)
    val activeCaptions = remember(project, playheadUs) { project.activeCaptionsAt(playheadUs) }
    val activeOverlays = remember(project, playheadUs) { project.activeOverlaysAt(playheadUs) }
    val stageRatio = remember(project.aspect, project.customAspectWidth, project.customAspectHeight) {
        project.resolvedAspectRatio().coerceIn(0.4f, 2.5f)
    }

    LaunchedEffect(isPlaying, renderedOutput, totalUs, previewSpeed) {
        if (isPlaying && renderedOutput == null && totalUs > 0L) {
            var lastTick = System.currentTimeMillis()
            while (isPlaying) {
                delay(33L)
                val now = System.currentTimeMillis()
                val dtUs = ((now - lastTick) * 1000L * previewSpeed).toLong().coerceAtLeast(1_000L)
                lastTick = now
                val next = playheadUs + dtUs
                if (next >= totalUs) {
                    onPlayheadChange(0L)
                    onTogglePlay()
                    break
                } else {
                    onPlayheadChange(next)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(216.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                renderedOutput != null -> {
                    val player = remember { ExoPlayer.Builder(ctx).build() }
                    val latestIsPlaying by rememberUpdatedState(isPlaying)
                    val latestOnTogglePlay by rememberUpdatedState(onTogglePlay)
                    val latestOnPlayheadChange by rememberUpdatedState(onPlayheadChange)

                    LaunchedEffect(renderedOutput.absolutePath) {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(renderedOutput)))
                        player.prepare()
                        player.playWhenReady = latestIsPlaying
                    }
                    LaunchedEffect(isPlaying, renderedOutput.absolutePath) {
                        if (isPlaying && player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0L)
                        }
                        if (player.playWhenReady != isPlaying) {
                            player.playWhenReady = isPlaying
                        }
                    }
                    LaunchedEffect(isPlaying, renderedOutput.absolutePath) {
                        if (isPlaying) {
                            while (true) {
                                delay(100L)
                                latestOnPlayheadChange(player.currentPosition.coerceAtLeast(0L) * 1000L)
                            }
                        }
                    }
                    LaunchedEffect(playheadUs, isPlaying, renderedOutput.absolutePath) {
                        if (!isPlaying && kotlin.math.abs(player.currentPosition.coerceAtLeast(0L) * 1000L - playheadUs) > 80_000L) {
                            player.seekTo((playheadUs / 1000L).coerceAtLeast(0L))
                        }
                    }
                    LaunchedEffect(previewSpeed, audioMonitorMuted) {
                        player.setPlaybackSpeed(previewSpeed)
                        player.volume = if (audioMonitorMuted) 0f else 1f
                    }
                    DisposableEffect(player) {
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    latestOnPlayheadChange(0L)
                                    if (latestIsPlaying) latestOnTogglePlay()
                                }
                            }

                            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && playWhenReady != latestIsPlaying) {
                                    latestOnTogglePlay()
                                }
                            }
                        }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                            player.release()
                        }
                    }
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                this.player = player
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                project.mainVideoTrack.items.isNotEmpty() -> {
                    val activeItem = remember(project, playheadUs) {
                        project.mainVideoTrack.items.firstOrNull { playheadUs in it.timelineStartUs..it.timelineEndUs }
                            ?: project.mainVideoTrack.items.last()
                    }
                    val clip = remember(clips, activeItem.sourceClipIndex) {
                        clips.firstOrNull { it.meta.index == activeItem.sourceClipIndex }
                    }
                    var frameBmp by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(clip?.uri, activeItem.id, playheadUs / 120_000L, preview720p) {
                        if (clip == null) {
                            frameBmp = null
                            return@LaunchedEffect
                        }
                        val localOutUs = (playheadUs - activeItem.timelineStartUs).coerceAtLeast(0L)
                        val srcUs = (activeItem.sourceStartUs + (localOutUs * activeItem.effectiveSpeed).toLong())
                            .coerceIn(activeItem.sourceStartUs, activeItem.sourceEndUs)
                        val maxEdge = if (preview720p) 720 else 1080
                        val bmp = withContext(Dispatchers.IO) {
                            runCatching {
                                MediaMetadataRetriever().use { r ->
                                    r.setDataSource(ctx, clip.uri)
                                    r.getScaledFrameAtTime(srcUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
                                }
                            }.getOrNull()
                        }
                        if (bmp != null) frameBmp = bmp else if (frameBmp == null) frameBmp = clip.thumbnail
                    }

                    val shown = frameBmp ?: clip?.thumbnail
                    val applyEffects = fullEffectsPreview && !compareOriginal
                    val colorFilter = remember(activeItem.effects, project.colorLook, applyEffects) {
                        if (!applyEffects) null else buildPreviewColorFilter(activeItem.effects, project.colorLook)
                    }
                    val blurRadius = if (applyEffects && activeItem.effects.blur > 0.02f) {
                        (activeItem.effects.blur * 8f).dp
                    } else 0.dp

                    // Framed Aspect Stage
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(stageRatio, matchHeightConstraintsFirst = true)
                            .background(Color(0xFF070707))
                            .border(0.5.dp, Color(0xFF333333)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frameW = constraints.maxWidth
                        val frameH = constraints.maxHeight
                        if (shown != null) {
                            Image(
                                bitmap = shown.asImageBitmap(),
                                contentDescription = "Timeline frame preview",
                                contentScale = ContentScale.Crop,
                                colorFilter = colorFilter,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
                            )
                        } else {
                            Text("Decoding frame…", color = Color(0xFFA0A0A0), style = MaterialTheme.typography.bodySmall)
                        }

                        // Vignette & Glow preview layers
                        if (applyEffects && (activeItem.effects.vignette > 0.02f || activeItem.effects.glow > 0.02f)) {
                            Canvas(Modifier.fillMaxSize()) {
                                if (activeItem.effects.glow > 0.02f) {
                                    drawRect(Color.White.copy(alpha = activeItem.effects.glow * 0.14f))
                                }
                                if (activeItem.effects.vignette > 0.02f) {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = activeItem.effects.vignette * 0.72f)),
                                            radius = size.maxDimension * 0.75f,
                                        )
                                    )
                                }
                            }
                        }

                        // V2 Overlay PIP preview
                        if (!compareOriginal) {
                            activeOverlays.forEach { ov ->
                                val ovClip = clips.firstOrNull { it.meta.index == ov.sourceClipIndex }
                                val bounds = ov.overlayBounds ?: OverlayBounds()
                                val ovBmp = ovClip?.thumbnail
                                if (ovBmp != null) {
                                    val wPx = (frameW * bounds.widthFraction).roundToInt().coerceAtLeast(36)
                                    val hPx = (wPx * ovBmp.height / ovBmp.width.coerceAtLeast(1)).coerceAtLeast(24)
                                    val xPx = (frameW * bounds.x).roundToInt().coerceIn(0, (frameW - wPx).coerceAtLeast(0))
                                    val yPx = (frameH * bounds.y).roundToInt().coerceIn(0, (frameH - hPx).coerceAtLeast(0))
                                    Image(
                                        bitmap = ovBmp.asImageBitmap(),
                                        contentDescription = "Overlay PIP",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .offset { IntOffset(xPx, yPx) }
                                            .size(
                                                width = with(androidx.compose.ui.platform.LocalDensity.current) { wPx.toDp() },
                                                height = with(androidx.compose.ui.platform.LocalDensity.current) { hPx.toDp() },
                                            )
                                            .alpha(bounds.opacity)
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp)),
                                    )
                                }
                            }
                        }

                        // Safe Area Guides
                        if (showSafeGuides) {
                            Canvas(Modifier.fillMaxSize()) {
                                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                drawRect(
                                    color = Color.White.copy(alpha = 0.55f),
                                    topLeft = Offset(size.width * 0.08f, size.height * 0.12f),
                                    size = Size(size.width * 0.84f, size.height * 0.76f),
                                    style = Stroke(width = 1.5f, pathEffect = dash),
                                )
                            }
                        }

                        // Title overlay preview
                        if (!compareOriginal && !project.titleOverlay.isNullOrBlank() && usToSec(playheadUs) <= 3.0) {
                            Text(
                                text = project.titleOverlay,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }

                        // Live synchronized styled captions preview
                        if (!compareOriginal && activeCaptions.isNotEmpty()) {
                            val cap = activeCaptions.first()
                            val bg = when (cap.style) {
                                CaptionStyle.BOLD_BOX -> Color.Black.copy(alpha = cap.bgAlpha.coerceAtLeast(0.75f))
                                CaptionStyle.SUBTITLE_BAR -> Color.Black.copy(alpha = cap.bgAlpha)
                                CaptionStyle.MINIMAL -> Color.Transparent
                            }
                            val fontFamily = when (cap.font) {
                                CaptionFont.MONO -> FontFamily.Monospace
                                CaptionFont.CONDENSED, CaptionFont.SANS_BOLD -> FontFamily.SansSerif
                            }
                            val styledText = remember(cap.text, cap.highlightKeywords) {
                                buildAnnotatedString {
                                    val words = cap.text.split(" ")
                                    val kw = cap.highlightKeywords.map { it.lowercase() }.toSet()
                                    words.forEachIndexed { idx, w ->
                                        val clean = w.trim(',', '.', '!', '?', ':', ';').lowercase()
                                        if (clean in kw) {
                                            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, background = Color(0x55FFFFFF))) {
                                                append(w)
                                            }
                                        } else {
                                            append(w)
                                        }
                                        if (idx < words.lastIndex) append(" ")
                                    }
                                }
                            }
                            val alignBias = (cap.positionYFraction - 0.5f) * 2f
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(androidx.compose.ui.BiasAlignment(0f, alignBias.coerceIn(-0.85f, 0.85f))),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = styledText,
                                        color = Color.White,
                                        fontFamily = fontFamily,
                                        fontSize = (cap.fontSizeSp * 0.72f).coerceIn(11f, 22f).sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .background(bg, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    Surface(
                        onClick = onAddMedia,
                        color = Color(0xFF141414),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("empty_preview_add_media"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = "Add video clips", tint = Color.White, modifier = Modifier.size(28.dp))
                            Text("Tap to import video clips", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (preview720p) "720p proxy preview ready" else "1080p Studio preview ready",
                                color = Color(0xFF9A9A9A),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // Top stage badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StagePill(project.aspect.key)
                StagePill(if (preview720p) "720p Proxy" else "1080p Studio")
                if (compareOriginal) StagePill("Original (A/B)")
                if (renderedOutput != null) StagePill("Rendered MP4")
            }
        }

        // Studio Transport & Preview Tools Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${EditorMediaHelper.formatTimecode(playheadUs)} / ${EditorMediaHelper.formatTimecode(totalUs)}",
                style = MonoTimecodeStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 6.dp).testTag("playhead_timecode"),
            )
            IconButton(
                onClick = { onPlayheadChange((playheadUs - FRAME_STEP_US).coerceAtLeast(0L)) },
                modifier = Modifier.size(48.dp).testTag("step_back_button"),
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous frame", modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onTogglePlay,
                enabled = project.mainVideoTrack.items.isNotEmpty(),
                modifier = Modifier.size(48.dp).testTag("play_pause_button"),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = { onPlayheadChange((playheadUs + FRAME_STEP_US).coerceAtMost(totalUs)) },
                modifier = Modifier.size(48.dp).testTag("step_forward_button"),
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next frame", modifier = Modifier.size(20.dp))
            }

            val nextSpeed = when {
                previewSpeed < 0.75f -> 1.0f
                previewSpeed < 1.25f -> 1.5f
                previewSpeed < 1.75f -> 2.0f
                else -> 0.5f
            }
            FilterChip(
                selected = previewSpeed != 1.0f,
                onClick = { onPreviewSpeedChange(nextSpeed) },
                label = { Text("${previewSpeed}x", style = MonoTimecodeStyle) },
                modifier = Modifier.testTag("preview_speed_button"),
            )
            IconButton(
                onClick = onToggleSafeGuides,
                modifier = Modifier.size(48.dp).testTag("safe_guides_button"),
            ) {
                Icon(
                    Icons.Default.GridOn,
                    contentDescription = "Toggle safe guides",
                    tint = if (showSafeGuides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onToggleCompareOriginal,
                modifier = Modifier.size(48.dp).testTag("compare_ab_button"),
            ) {
                Icon(
                    Icons.Default.Compare,
                    contentDescription = "Compare original vs edited",
                    tint = if (compareOriginal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onToggleAudioMute,
                modifier = Modifier.size(48.dp).testTag("preview_mute_button"),
            ) {
                Icon(
                    if (audioMonitorMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (audioMonitorMuted) "Unmute monitor" else "Mute monitor",
                    tint = if (audioMonitorMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun buildPreviewColorFilter(effects: EffectSettings, look: ColorLook): ColorFilter? {
    val sat = when (look) {
        ColorLook.BW -> 0f
        ColorLook.CINEMATIC -> effects.saturation * 0.9f
        ColorLook.VIVID -> effects.saturation * 1.25f
        ColorLook.WARM -> effects.saturation * 1.08f
        else -> effects.saturation
    }.coerceIn(0f, 2f)

    val lookContrast = when (look) {
        ColorLook.CINEMATIC -> 0.12f
        ColorLook.VIVID -> 0.08f
        ColorLook.BW -> 0.18f
        else -> 0f
    }
    val lookBright = when (look) {
        ColorLook.VIVID -> 0.04f
        else -> 0f
    }
    val c = (1f + effects.contrast + lookContrast).coerceIn(0.4f, 1.8f)
    val b = ((effects.brightness + lookBright) * 255f)
    val t = (1f - c) / 2f * 255f + b

    val matrix = ColorMatrix().apply { setToSaturation(sat) }
    val contrastMat = ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    matrix.timesAssign(contrastMat)
    if (look == ColorLook.WARM) {
        matrix.timesAssign(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, 4f,
                    0f, 1.02f, 0f, 0f, 0f,
                    0f, 0f, 0.90f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    } else if (look == ColorLook.COOL) {
        matrix.timesAssign(
            ColorMatrix(
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, -4f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.10f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    return ColorFilter.colorMatrix(matrix)
}

@Composable
private fun StagePill(label: String) {
    Text(
        text = label,
        color = Color(0xFFE5E5E5),
        style = MonoTimecodeStyle,
        modifier = Modifier
            .background(Color(0xB3121212), RoundedCornerShape(6.dp))
            .border(0.5.dp, Color(0xFF3E3E3E), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
