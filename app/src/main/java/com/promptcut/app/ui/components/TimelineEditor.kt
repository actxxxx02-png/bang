package com.promptcut.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptcut.app.round1
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlin.math.ceil

@Composable
fun TimelineEditor(
    project: TimelineProject,
    playheadUs: Long,
    zoom: Float,
    selectedVideoItemId: String?,
    selectedCaptionId: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    segmentThumbnails: Map<String, List<Bitmap>>,
    musicWaveform: FloatArray?,
    clipWaveforms: Map<Int, FloatArray>,
    onPlayheadChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSelectVideoItem: (String) -> Unit,
    onSelectCaption: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onTrimItem: (String, Long, Long) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onToggleTrackVisibility: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pxPerSecDp = (46f * zoom).dp
    val totalSec = usToSec(project.totalDurationUs).coerceAtLeast(8.0)
    val totalWidthDp = (pxPerSecDp * totalSec.toFloat())
    val density = LocalDensity.current
    val pxPerUs = with(density) { (pxPerSecDp.toPx() / 1_000_000f) }
    val scrollState = rememberScrollState()

    val overlayTrack = project.overlayVideoTrack
    val sfxTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.SFX }
    val voTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.VOICEOVER }
    val currentZoom by rememberUpdatedState(zoom)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        // Timeline Action Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp).testTag("undo_button")) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(48.dp).testTag("redo_button")) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onSplit,
                enabled = project.mainVideoTrack.items.isNotEmpty() && !project.mainVideoTrack.locked,
                modifier = Modifier.size(48.dp).testTag("split_button"),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = "Split at playhead", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onDuplicate,
                enabled = selectedVideoItemId != null && !project.mainVideoTrack.locked,
                modifier = Modifier.size(48.dp).testTag("duplicate_button"),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate clip", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onDelete,
                enabled = selectedVideoItemId != null && !project.mainVideoTrack.locked,
                modifier = Modifier.size(48.dp).testTag("delete_segment_button"),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete clip", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onMoveLeft,
                enabled = selectedVideoItemId != null && !project.mainVideoTrack.locked,
                modifier = Modifier.size(48.dp).testTag("move_left_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move clip left", modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onMoveRight,
                enabled = selectedVideoItemId != null && !project.mainVideoTrack.locked,
                modifier = Modifier.size(48.dp).testTag("move_right_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move clip right", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { onZoomChange((zoom - 0.25f).coerceIn(0.4f, 4.0f)) },
                modifier = Modifier.size(48.dp).testTag("zoom_out_button"),
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out timeline", modifier = Modifier.size(18.dp))
            }
            Text("${round1(zoom.toDouble())}x", style = MonoTimecodeStyle)
            IconButton(
                onClick = { onZoomChange((zoom + 0.25f).coerceIn(0.4f, 4.0f)) },
                modifier = Modifier.size(48.dp).testTag("zoom_in_button"),
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in timeline", modifier = Modifier.size(18.dp))
            }
        }

        // Fixed Track Control Column + Scrollable Multi-Track Canvas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            onZoomChange((currentZoom * zoomChange).coerceIn(0.4f, 4.0f))
                        }
                    }
                },
        ) {
            // Sticky Left Track Header Column (48dp touch-friendly track locks & mutes)
            Column(
                modifier = Modifier.width(64.dp).padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Spacer(Modifier.height(20.dp)) // Align with time ruler
                TrackHeaderCell(
                    label = "V1",
                    locked = project.mainVideoTrack.locked,
                    mutedOrHidden = !project.mainVideoTrack.visible,
                    heightDp = 48,
                    isAudio = false,
                    onToggleLock = { onToggleTrackLock(project.mainVideoTrack.id) },
                    onToggleMuteVis = { onToggleTrackVisibility(project.mainVideoTrack.id) },
                )
                if (overlayTrack != null && overlayTrack.items.isNotEmpty()) {
                    TrackHeaderCell(
                        label = "V2",
                        locked = overlayTrack.locked,
                        mutedOrHidden = !overlayTrack.visible,
                        heightDp = 28,
                        isAudio = false,
                        onToggleLock = { onToggleTrackLock(overlayTrack.id) },
                        onToggleMuteVis = { onToggleTrackVisibility(overlayTrack.id) },
                    )
                }
                TrackHeaderCell(
                    label = "A1",
                    locked = project.mainAudioTrack.locked,
                    mutedOrHidden = project.mainAudioTrack.muted,
                    heightDp = 28,
                    isAudio = true,
                    onToggleLock = { onToggleTrackLock(project.mainAudioTrack.id) },
                    onToggleMuteVis = { onToggleTrackVisibility(project.mainAudioTrack.id) },
                )
                if (voTrack != null && voTrack.items.isNotEmpty()) {
                    TrackHeaderCell(
                        label = "A2",
                        locked = voTrack.locked,
                        mutedOrHidden = voTrack.muted,
                        heightDp = 24,
                        isAudio = true,
                        onToggleLock = { onToggleTrackLock(voTrack.id) },
                        onToggleMuteVis = { onToggleTrackVisibility(voTrack.id) },
                    )
                }
                if (sfxTrack != null && sfxTrack.items.isNotEmpty()) {
                    TrackHeaderCell(
                        label = "A3",
                        locked = sfxTrack.locked,
                        mutedOrHidden = sfxTrack.muted,
                        heightDp = 24,
                        isAudio = true,
                        onToggleLock = { onToggleTrackLock(sfxTrack.id) },
                        onToggleMuteVis = { onToggleTrackVisibility(sfxTrack.id) },
                    )
                }
                TrackHeaderCell(
                    label = "T1",
                    locked = project.mainCaptionTrack.locked,
                    mutedOrHidden = !project.mainCaptionTrack.visible,
                    heightDp = 24,
                    isAudio = false,
                    onToggleLock = { onToggleTrackLock(project.mainCaptionTrack.id) },
                    onToggleMuteVis = { onToggleTrackVisibility(project.mainCaptionTrack.id) },
                )
            }

            // Scrollable Tracks + Playhead
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
            ) {
                val trackTotalHeightDp = 20 + 5 + 48 +
                    (if (overlayTrack != null && overlayTrack.items.isNotEmpty()) 33 else 0) +
                    33 +
                    (if (voTrack != null && voTrack.items.isNotEmpty()) 29 else 0) +
                    (if (sfxTrack != null && sfxTrack.items.isNotEmpty()) 29 else 0) +
                    29

                Box(
                    modifier = Modifier
                        .width(totalWidthDp)
                        .pointerInput(totalSec, pxPerUs) {
                            detectTapGestures { pos ->
                                val tappedUs = (pos.x / pxPerUs).toLong().coerceIn(0L, secToUs(totalSec))
                                onPlayheadChange(tappedUs)
                            }
                        },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        // Time Ruler + Beat Marker Ticks
                        val rulerColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val beatColor = MaterialTheme.colorScheme.primary
                        Box(Modifier.fillMaxWidth().height(20.dp)) {
                            Canvas(Modifier.fillMaxSize()) {
                                val secLimit = ceil(totalSec).toInt()
                                for (s in 0..secLimit) {
                                    val x = s * 1_000_000f * pxPerUs
                                    val major = s % 5 == 0
                                    drawLine(
                                        color = rulerColor.copy(alpha = if (major) 0.9f else 0.4f),
                                        start = Offset(x, if (major) 2f else 9f),
                                        end = Offset(x, size.height),
                                        strokeWidth = if (major) 2f else 1f,
                                    )
                                }
                                project.beatMarkersUs.forEach { beatUs ->
                                    val bx = beatUs * pxPerUs
                                    if (bx in 0f..size.width) {
                                        val diamond = Path().apply {
                                            moveTo(bx, size.height - 9f)
                                            lineTo(bx + 3.5f, size.height - 4.5f)
                                            lineTo(bx, size.height)
                                            lineTo(bx - 3.5f, size.height - 4.5f)
                                            close()
                                        }
                                        drawPath(diamond, color = beatColor)
                                    }
                                }
                            }
                            val secLimit = ceil(totalSec).toInt()
                            for (s in 0..secLimit step 5) {
                                Text(
                                    text = "${s}s",
                                    style = MonoTimecodeStyle,
                                    color = rulerColor,
                                    modifier = Modifier.offset(x = (pxPerSecDp * s.toFloat()) + 3.dp, y = 0.dp),
                                )
                            }
                        }

                        // V1 Video Track (with trim handles on selected item)
                        val vItems = project.mainVideoTrack.items
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (vItems.isEmpty()) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        "  V1 · Video track empty — add clips below",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                vItems.forEach { item ->
                                    val widthDp = (pxPerSecDp * usToSec(item.timelineDurationUs).toFloat()).coerceAtLeast(36.dp)
                                    val selected = item.id == selectedVideoItemId
                                    val thumbs = segmentThumbnails[item.id].orEmpty()
                                    val curItem by rememberUpdatedState(item)
                                    Box(
                                        modifier = Modifier
                                            .width(widthDp)
                                            .fillMaxHeight()
                                            .padding(end = 2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(6.dp),
                                            )
                                            .clickable { onSelectVideoItem(item.id) }
                                            .testTag("timeline_clip_${item.id}"),
                                    ) {
                                        if (thumbs.isNotEmpty()) {
                                            Row(Modifier.fillMaxSize()) {
                                                thumbs.forEach { bmp ->
                                                    Image(
                                                        bitmap = bmp.asImageBitmap(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                                    )
                                                }
                                            }
                                        }
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .background(Color(0x99000000))
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "#${item.sourceClipIndex + 1} · ${round1(usToSec(item.timelineDurationUs))}s",
                                                style = MonoTimecodeStyle,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (item.effects.hasActiveAdjustments || item.speedRamp.size >= 2) {
                                                Text("FX", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(end = 2.dp))
                                            }
                                            if (item.editedByUser) {
                                                Icon(Icons.Default.Lock, contentDescription = "Manual edit", tint = Color.White, modifier = Modifier.size(10.dp))
                                            }
                                        }

                                        // Draggable Left & Right Trim Handles when selected & track unlocked
                                        if (selected && !project.mainVideoTrack.locked) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .width(12.dp)
                                                    .fillMaxHeight()
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                                    .pointerInput(item.id, pxPerUs) {
                                                        detectHorizontalDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val dUs = ((dragAmount / pxPerUs) * curItem.effectiveSpeed).toLong()
                                                            val newS = (curItem.sourceStartUs + dUs).coerceIn(0L, curItem.sourceEndUs - 250_000L)
                                                            onTrimItem(curItem.id, newS, curItem.sourceEndUs)
                                                        }
                                                    },
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .width(12.dp)
                                                    .fillMaxHeight()
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                                    .pointerInput(item.id, pxPerUs) {
                                                        detectHorizontalDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val dUs = ((dragAmount / pxPerUs) * curItem.effectiveSpeed).toLong()
                                                            val newE = (curItem.sourceEndUs + dUs).coerceAtLeast(curItem.sourceStartUs + 250_000L)
                                                            onTrimItem(curItem.id, curItem.sourceStartUs, newE)
                                                        }
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // V2 Overlay Track (PIP)
                        if (overlayTrack != null && overlayTrack.items.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                overlayTrack.items.forEach { ov ->
                                    val xDp = pxPerSecDp * usToSec(ov.timelineStartUs).toFloat()
                                    val wDp = (pxPerSecDp * usToSec(ov.timelineDurationUs).toFloat()).coerceAtLeast(32.dp)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier
                                            .offset(x = xDp, y = 2.dp)
                                            .width(wDp)
                                            .height(24.dp)
                                            .border(0.8.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
                                    ) {
                                        Text(
                                            "PIP #${ov.sourceClipIndex + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // A1 Audio / Waveform Track
                        val audioItems = project.mainAudioTrack.items
                        val wave = musicWaveform ?: project.mainVideoTrack.items.firstOrNull()?.let { clipWaveforms[it.sourceClipIndex] }
                        val waveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (project.mainAudioTrack.muted) 0.25f else 0.72f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                        ) {
                            if (wave != null && wave.isNotEmpty()) {
                                Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp)) {
                                    val barW = (size.width / wave.size).coerceAtLeast(2f)
                                    wave.forEachIndexed { i, amp ->
                                        val h = (amp.coerceIn(0.08f, 1f) * size.height)
                                        val x = i * barW
                                        drawLine(
                                            color = waveColor,
                                            start = Offset(x, (size.height - h) / 2f),
                                            end = Offset(x, (size.height + h) / 2f),
                                            strokeWidth = (barW * 0.6f).coerceAtLeast(1.5f),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = audioItems.firstOrNull()?.title?.let { "Music: $it" } ?: "Original / Mood Audio",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                            )
                        }

                        // A2 Voiceover Track
                        if (voTrack != null && voTrack.items.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                voTrack.items.forEach { vo ->
                                    val xDp = pxPerSecDp * usToSec(vo.timelineStartUs).toFloat()
                                    val wDp = (pxPerSecDp * usToSec(vo.durationUs).toFloat()).coerceAtLeast(28.dp)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.offset(x = xDp, y = 2.dp).width(wDp).height(20.dp),
                                    ) {
                                        Text(vo.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }

                        // A3 SFX Track
                        if (sfxTrack != null && sfxTrack.items.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                sfxTrack.items.forEach { sfx ->
                                    val xDp = pxPerSecDp * usToSec(sfx.timelineStartUs).toFloat()
                                    val wDp = (pxPerSecDp * usToSec(sfx.durationUs).toFloat()).coerceAtLeast(30.dp)
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.offset(x = xDp, y = 2.dp).width(wDp).height(20.dp),
                                    ) {
                                        Text(sfx.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }

                        // T1 Caption Track
                        val captions = project.mainCaptionTrack.items
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            if (captions.isEmpty()) {
                                Text(
                                    "No captions yet (generate in Captions tab)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                                )
                            } else {
                                captions.forEach { cap ->
                                    val xDp = pxPerSecDp * usToSec(cap.startUs).toFloat()
                                    val wDp = (pxPerSecDp * usToSec(cap.durationUs).toFloat()).coerceAtLeast(26.dp)
                                    val isSel = cap.id == selectedCaptionId
                                    Surface(
                                        onClick = { onSelectCaption(cap.id) },
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier
                                            .offset(x = xDp, y = 2.dp)
                                            .width(wDp)
                                            .height(20.dp),
                                    ) {
                                        Text(
                                            text = cap.text,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // White Playhead Line
                    val playheadDp = with(density) { (playheadUs * pxPerUs).toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = playheadDp)
                            .width(2.dp)
                            .height(trackTotalHeightDp.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeaderCell(
    label: String,
    locked: Boolean,
    mutedOrHidden: Boolean,
    heightDp: Int,
    isAudio: Boolean,
    onToggleLock: () -> Unit,
    onToggleMuteVis: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MonoTimecodeStyle, modifier = Modifier.padding(start = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onToggleLock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Toggle $label lock",
                    tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onToggleMuteVis),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        isAudio && mutedOrHidden -> Icons.AutoMirrored.Filled.VolumeOff
                        isAudio -> Icons.AutoMirrored.Filled.VolumeUp
                        mutedOrHidden -> Icons.Default.VisibilityOff
                        else -> Icons.Default.Visibility
                    },
                    contentDescription = "Toggle $label visibility or mute",
                    tint = if (mutedOrHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
