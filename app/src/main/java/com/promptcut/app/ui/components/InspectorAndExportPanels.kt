package com.promptcut.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promptcut.app.Aspect
import com.promptcut.app.ColorLook
import com.promptcut.app.EditorViewModel
import com.promptcut.app.MusicSource
import com.promptcut.app.QaReport
import com.promptcut.app.Transition
import com.promptcut.app.audio.SfxType
import com.promptcut.app.render.DeviceCapabilities
import com.promptcut.app.render.ExportPreset
import com.promptcut.app.round1
import com.promptcut.app.round2
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionAnimation
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.OverlayBounds
import com.promptcut.app.timeline.SpeedRampPoint
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditInspectorPanel(
    vm: EditorViewModel,
    project: TimelineProject,
    onOpenCrop: (Int) -> Unit,
) {
    val item = vm.selectedVideoItem()
    if (item == null) {
        Text(
            "Select a clip on the timeline to trim, adjust speed ramps, audio, transitions, or keyframes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sourceClip = vm.clips.firstOrNull { it.meta.index == item.sourceClipIndex }
    val maxDur = sourceClip?.meta?.durationSec?.toFloat() ?: (usToSec(item.sourceEndUs).toFloat() + 5f)
    var trimRange by remember(item.id, item.sourceStartUs, item.sourceEndUs) {
        mutableStateOf(usToSec(item.sourceStartUs).toFloat()..usToSec(item.sourceEndUs).toFloat())
    }
    var vol by remember(item.id, item.volume) { mutableFloatStateOf(item.volume) }
    var kfValue by remember(item.id) { mutableFloatStateOf(1.1f) }
    var kfProp by remember { mutableStateOf(KeyframeProperty.SCALE) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Segment · Clip #${item.sourceClipIndex + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.trimSelectedSilence() }, modifier = Modifier.testTag("trim_silence_button")) {
                Text("Trim Silence")
            }
            OutlinedButton(onClick = { onOpenCrop(item.sourceClipIndex) }) { Text("Crop") }
        }

        Text("Trim Source: ${round2(trimRange.start.toDouble())}s – ${round2(trimRange.endInclusive.toDouble())}s", style = MonoTimecodeStyle)
        RangeSlider(
            value = trimRange,
            onValueChange = { trimRange = it },
            valueRange = 0f..maxDur.coerceAtLeast(0.5f),
            onValueChangeFinished = {
                vm.trimSelected(trimRange.start.toDouble(), trimRange.endInclusive.toDouble())
            },
        )

        Text("Speed (${round2(item.effectiveSpeed)}x)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { sp ->
                FilterChip(
                    selected = item.speedRamp.isEmpty() && kotlin.math.abs(item.speed - sp) < 0.05,
                    onClick = { vm.setSelectedSpeed(sp) },
                    label = { Text("${sp}x", style = MonoTimecodeStyle) },
                )
            }
        }

        Text("Speed Ramp Curves", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = item.speedRamp.isEmpty(),
                onClick = { vm.setSelectedSpeedRamp(emptyList()) },
                label = { Text("Flat", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = item.speedRamp.size == 3 && item.speedRamp[1].speed < 0.7,
                onClick = {
                    vm.setSelectedSpeedRamp(
                        listOf(
                            SpeedRampPoint(0f, 1.6),
                            SpeedRampPoint(0.5f, 0.5),
                            SpeedRampPoint(1f, 1.6),
                        )
                    )
                },
                label = { Text("Hero Dip", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = item.speedRamp.size == 2 && item.speedRamp.last().speed >= 1.8,
                onClick = {
                    vm.setSelectedSpeedRamp(
                        listOf(
                            SpeedRampPoint(0f, 0.65),
                            SpeedRampPoint(1f, 2.0),
                        )
                    )
                },
                label = { Text("Ramp Up", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = item.speedRamp.size == 4,
                onClick = {
                    vm.setSelectedSpeedRamp(
                        listOf(
                            SpeedRampPoint(0f, 1.0),
                            SpeedRampPoint(0.35f, 1.8),
                            SpeedRampPoint(0.65f, 0.65),
                            SpeedRampPoint(1f, 1.0),
                        )
                    )
                },
                label = { Text("Pulse", style = MaterialTheme.typography.labelSmall) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Volume ${(vol * 100).toInt()}%", style = MonoTimecodeStyle, modifier = Modifier.weight(1f))
            Text("Mute", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = item.muted, onCheckedChange = { vm.setSelectedAudio(vol, it) })
        }
        Slider(
            value = vol,
            onValueChange = { vol = it },
            valueRange = 0f..1.5f,
            onValueChangeFinished = { vm.setSelectedAudio(vol, item.muted) },
        )

        Text("Transition In", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Transition.entries.forEach { t ->
                FilterChip(
                    selected = item.transitionIn == t,
                    onClick = { vm.setSelectedTransition(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        HorizontalDivider()
        Text("Keyframes (${item.keyframes.size})", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyframeProperty.entries.forEach { p ->
                FilterChip(
                    selected = kfProp == p,
                    onClick = { kfProp = p },
                    label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        val kfRange = when (kfProp) {
            KeyframeProperty.OPACITY -> 0.1f..1f
            KeyframeProperty.PAN_X, KeyframeProperty.PAN_Y -> -0.6f..0.6f
            KeyframeProperty.SCALE -> 0.6f..2.2f
            KeyframeProperty.VOLUME -> 0f..1.5f
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                value = kfValue.coerceIn(kfRange.start, kfRange.endInclusive),
                onValueChange = { kfValue = it },
                valueRange = kfRange,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val offset = (vm.playheadUs - item.timelineStartUs).coerceIn(0L, item.timelineDurationUs)
                vm.addOrUpdateSelectedKeyframe(Keyframe(offset, kfProp, kfValue.coerceIn(kfRange.start, kfRange.endInclusive)))
            }) { Text("Set KF") }
        }
        if (item.keyframes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.keyframes.forEach { kf ->
                    AssistChip(
                        onClick = { vm.playheadUs = item.timelineStartUs + kf.timeOffsetUs },
                        label = { Text("${kf.property.label} ${round2(kf.value.toDouble())} @ ${round1(usToSec(kf.timeOffsetUs))}s", style = MonoTimecodeStyle) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionsPanel(vm: EditorViewModel, project: TimelineProject) {
    val ctx = LocalContext.current
    val captions = project.mainCaptionTrack.items
    val selected = vm.selectedCaption()
    var style by remember(selected?.id) { mutableStateOf(selected?.style ?: CaptionStyle.SUBTITLE_BAR) }
    var font by remember(selected?.id) { mutableStateOf(selected?.font ?: CaptionFont.SANS_BOLD) }
    var animation by remember(selected?.id) { mutableStateOf(selected?.animation ?: CaptionAnimation.NONE) }
    var fontSizeSp by remember(selected?.id) { mutableIntStateOf(selected?.fontSizeSp ?: 18) }
    var posY by remember(selected?.id) { mutableFloatStateOf(selected?.positionYFraction ?: 0.84f) }
    var autoPunctuate by remember { mutableStateOf(true) }
    var highlightKeywords by remember { mutableStateOf(true) }

    var text by remember(selected?.id) { mutableStateOf(selected?.text ?: "") }
    var startSec by remember(selected?.id) {
        mutableStateOf(selected?.let { round1(usToSec(it.startUs)).toString() } ?: round1(usToSec(vm.playheadUs)).toString())
    }
    var endSec by remember(selected?.id) {
        mutableStateOf(selected?.let { round1(usToSec(it.endUs)).toString() } ?: round1(usToSec(vm.playheadUs) + 2.0).toString())
    }
    var exportNotice by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.generateCaptionsFromSpeech(style) },
                modifier = Modifier.weight(1f).testTag("auto_captions_button"),
            ) { Text("Auto-Generate from Speech") }
        }

        Text("Style & Typography", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptionStyle.entries.forEach { st ->
                FilterChip(
                    selected = style == st,
                    onClick = { style = st },
                    label = { Text(st.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptionFont.entries.forEach { f ->
                FilterChip(
                    selected = font == f,
                    onClick = { font = f },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
            CaptionAnimation.entries.forEach { a ->
                FilterChip(
                    selected = animation == a,
                    onClick = { animation = a },
                    label = { Text(a.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Size ${fontSizeSp}sp", style = MonoTimecodeStyle, modifier = Modifier.width(78.dp))
            Slider(
                value = fontSizeSp.toFloat(),
                onValueChange = { fontSizeSp = it.toInt().coerceIn(14, 32) },
                valueRange = 14f..32f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Safe Y ${(posY * 100).toInt()}%", style = MonoTimecodeStyle, modifier = Modifier.width(78.dp))
            Slider(
                value = posY,
                onValueChange = { posY = it.coerceIn(0.12f, 0.88f) },
                valueRange = 0.12f..0.88f,
                modifier = Modifier.weight(1f),
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-punctuate & emphasize keywords", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = autoPunctuate && highlightKeywords, onCheckedChange = {
                autoPunctuate = it; highlightKeywords = it
            })
        }

        if (captions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.applyCaptionFormattingToAll(style, font, fontSizeSp, posY, animation, autoPunctuate, highlightKeywords)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply Style to All") }
                OutlinedButton(
                    onClick = {
                        val srt = vm.exportCaptionsText(asVtt = false)
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Subtitles SRT", srt))
                        exportNotice = "Copied .SRT (${captions.size} cues)"
                    },
                ) { Text("SRT") }
                OutlinedButton(
                    onClick = {
                        val vtt = vm.exportCaptionsText(asVtt = true)
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/vtt")
                                    .putExtra(Intent.EXTRA_SUBJECT, "${project.name}.vtt")
                                    .putExtra(Intent.EXTRA_TEXT, vtt),
                                "Export Subtitles (.VTT)",
                            )
                        )
                    },
                ) { Text("VTT") }
            }
            exportNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(if (selected == null) "New caption text" else "Edit caption text") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = startSec,
                onValueChange = { startSec = it },
                label = { Text("Start (s)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = endSec,
                onValueChange = { endSec = it },
                label = { Text("End (s)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val s = startSec.toDoubleOrNull() ?: 0.0
                    val e = endSec.toDoubleOrNull() ?: (s + 2.0)
                    vm.upsertCaption(
                        CaptionItem(
                            id = selected?.id ?: UUID.randomUUID().toString(),
                            startUs = secToUs(s),
                            endUs = secToUs(e),
                            text = text.trim(),
                            style = style,
                            font = font,
                            fontSizeSp = fontSizeSp,
                            positionYFraction = posY,
                            animation = animation,
                            editedByUser = true,
                        )
                    )
                    if (selected == null) text = ""
                },
            ) { Text(if (selected == null) "Add" else "Save") }
        }

        if (captions.isNotEmpty()) {
            HorizontalDivider()
            captions.forEach { c ->
                OutlinedCard(
                    onClick = {
                        vm.selectedCaptionId = c.id
                        vm.playheadUs = c.startUs
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.text, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${round1(usToSec(c.startUs))}s – ${round1(usToSec(c.endUs))}s · ${c.style.label} · ${c.font.label}",
                                style = MonoTimecodeStyle,
                            )
                        }
                        TextButton(onClick = { vm.deleteCaption(c.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioStudioPanel(vm: EditorViewModel, onPickSong: () -> Unit) {
    val project by vm.timelineProject.collectAsStateWithLifecycle()
    val musicTrack = project.mainAudioTrack
    val voTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.VOICEOVER }
    val sfxTrack = project.audioTracks.firstOrNull { it.type == AudioTrackType.SFX }

    var musicVol by remember(musicTrack.volume) { mutableFloatStateOf(musicTrack.volume) }
    var sfxVol by remember(sfxTrack?.volume) { mutableFloatStateOf(sfxTrack?.volume ?: 1f) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MusicSource.entries.forEach { src ->
                FilterChip(
                    selected = vm.musicSource == src,
                    onClick = {
                        vm.musicSource = src
                        if (src == MusicSource.MY_SONG && vm.song == null) onPickSong()
                        if (src == MusicSource.LIBRARY && vm.libResults.isEmpty()) vm.searchLibrary()
                    },
                    label = { Text(src.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (vm.musicSource) {
            MusicSource.LIBRARY -> {
                LibraryPanel(vm)
                vm.shareCredit?.let { credit ->
                    Text(
                        "Attribution: $credit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MusicSource.MOOD -> Text(
                "Offline procedural ${vm.mood.label.lowercase()} score synthesized on-device via MusicSynth (zero network required).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MusicSource.MY_SONG -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.song?.let { "${it.name}" + (it.tempo?.let { t -> " · ${t.bpm.toInt()} BPM" } ?: "") }
                        ?: "No audio file picked yet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onPickSong) { Text(if (vm.song == null) "Choose File" else "Change") }
            }
            MusicSource.ORIGINAL -> Text("Using original clip audio only.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Beat Detection & Cut Sync (${project.beatMarkersUs.size} beats)", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.detectBeatMarkersForProject() },
                modifier = Modifier.weight(1f).testTag("detect_beats_button"),
            ) { Text("Detect Beats") }
            Button(
                onClick = { vm.snapCutsToNearestBeat() },
                enabled = project.beatMarkersUs.isNotEmpty() && project.mainVideoTrack.items.size > 1,
                modifier = Modifier.weight(1f).testTag("snap_beats_button"),
            ) { Text("Snap Cuts to Beat") }
        }

        HorizontalDivider()
        Text("Built-In SFX Synth (inserts at ${round1(usToSec(vm.playheadUs))}s)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SfxType.entries.forEach { sfx ->
                OutlinedButton(
                    onClick = { vm.insertSfxAtPlayhead(sfx) },
                    modifier = Modifier.testTag("sfx_${sfx.key}_button"),
                ) {
                    Text("+ ${sfx.label}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HorizontalDivider()
        Text("Multi-Track Mixer & Mastering", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("A1 Music ${(musicVol * 100).toInt()}%", style = MonoTimecodeStyle, modifier = Modifier.weight(1f))
            Text("Mute", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = musicTrack.muted, onCheckedChange = {
                vm.setAudioTrackMix(musicTrack.id, musicVol, it, musicTrack.voiceEnhance, musicTrack.normalizeTargetDb)
            })
        }
        Slider(
            value = musicVol,
            onValueChange = { musicVol = it },
            valueRange = 0f..1.5f,
            onValueChangeFinished = {
                vm.setAudioTrackMix(musicTrack.id, musicVol, musicTrack.muted, musicTrack.voiceEnhance, musicTrack.normalizeTargetDb)
            },
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice Presence EQ & Clarity", style = MaterialTheme.typography.bodySmall)
                Text("110 Hz rumble high-pass + 2.2 kHz dialogue clarity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = musicTrack.voiceEnhance, onCheckedChange = {
                vm.setAudioTrackMix(musicTrack.id, musicVol, musicTrack.muted, voiceEnhance = it, normalizeTargetDb = musicTrack.normalizeTargetDb)
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Loudness Normalize (-14 dBFS)", style = MaterialTheme.typography.bodySmall)
                Text("Consistent RMS output with soft-knee limiter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = musicTrack.normalizeTargetDb != null, onCheckedChange = { want ->
                vm.setAudioTrackMix(
                    musicTrack.id,
                    musicVol,
                    musicTrack.muted,
                    voiceEnhance = musicTrack.voiceEnhance,
                    normalizeTargetDb = if (want) -14f else null,
                )
            })
        }

        if (sfxTrack != null && sfxTrack.items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("A3 SFX (${sfxTrack.items.size}) ${(sfxVol * 100).toInt()}%", style = MonoTimecodeStyle, modifier = Modifier.weight(1f))
                Switch(checked = sfxTrack.muted, onCheckedChange = { vm.setAudioTrackMix(sfxTrack.id, sfxVol, it) })
            }
            Slider(
                value = sfxVol,
                onValueChange = { sfxVol = it },
                valueRange = 0f..1.5f,
                onValueChangeFinished = { vm.setAudioTrackMix(sfxTrack.id, sfxVol, sfxTrack.muted) },
            )
        }
        if (voTrack != null && voTrack.items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("A2 Voiceover (${voTrack.items.size})", style = MonoTimecodeStyle, modifier = Modifier.weight(1f))
                Switch(checked = voTrack.muted, onCheckedChange = { vm.setAudioTrackMix(voTrack.id, voTrack.volume, it) })
            }
        }

        if (vm.sfxCues.isNotEmpty()) {
            HorizontalDivider()
            Text("Suggested Transition Cue Points", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.sfxCues.take(6).forEach { cue ->
                    AssistChip(
                        onClick = { vm.playheadUs = cue.timelineUs },
                        label = { Text("${cue.type} @ ${round1(usToSec(cue.timelineUs))}s", style = MonoTimecodeStyle) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsPanel(vm: EditorViewModel, project: TimelineProject) {
    var titleText by remember(project.titleOverlay) { mutableStateOf(project.titleOverlay ?: "") }
    var customW by remember(project.customAspectWidth) { mutableStateOf(project.customAspectWidth.toString()) }
    var customH by remember(project.customAspectHeight) { mutableStateOf(project.customAspectHeight.toString()) }

    val selItem = vm.selectedVideoItem()
    var fx by remember(selItem?.id, selItem?.effects) { mutableStateOf(selItem?.effects ?: EffectSettings()) }
    val overlayItem = project.overlayVideoTrack?.items?.firstOrNull()
    var ovBounds by remember(overlayItem?.id, overlayItem?.overlayBounds) {
        mutableStateOf(overlayItem?.overlayBounds ?: OverlayBounds())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Color Look", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorLook.entries.forEach { l ->
                FilterChip(
                    selected = project.colorLook == l,
                    onClick = { vm.setProjectLook(l) },
                    label = { Text(l.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text("Aspect Ratio", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Aspect.entries.forEach { a ->
                FilterChip(
                    selected = project.aspect == a,
                    onClick = { vm.setProjectAspect(a) },
                    label = { Text(a.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        if (project.aspect == Aspect.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = customW, onValueChange = { customW = it }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = customH, onValueChange = { customH = it }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    val w = customW.toIntOrNull() ?: 1080
                    val h = customH.toIntOrNull() ?: 1350
                    vm.updateCustomAspect(w, h)
                }) { Text("Set") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                label = { Text("Opening Title Overlay") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.setProjectTitle(titleText) }) { Text("Apply") }
        }

        HorizontalDivider()
        if (selItem != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Clip #${selItem.sourceClipIndex + 1} GL Shaders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    fx = EffectSettings()
                    vm.setSelectedEffects(fx)
                }) { Text("Reset FX") }
            }
            EffectSliderRow("Brightness", fx.brightness, -0.5f..0.5f, { round2(it.toDouble()).toString() }) {
                fx = fx.copy(brightness = it)
            }
            EffectSliderRow("Contrast", fx.contrast, -0.5f..0.5f, { round2(it.toDouble()).toString() }) {
                fx = fx.copy(contrast = it)
            }
            EffectSliderRow("Saturation", fx.saturation, 0f..2f, { round2(it.toDouble()).toString() }) {
                fx = fx.copy(saturation = it)
            }
            EffectSliderRow("Sharpen", fx.sharpen, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(sharpen = it)
            }
            EffectSliderRow("Vignette", fx.vignette, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(vignette = it)
            }
            EffectSliderRow("Film Grain", fx.filmGrain, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(filmGrain = it)
            }
            EffectSliderRow("Glow", fx.glow, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(glow = it)
            }
            EffectSliderRow("Blur", fx.blur, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(blur = it)
            }
            EffectSliderRow("Camera Shake", fx.shake, 0f..1f, { "${(it * 100).toInt()}%" }) {
                fx = fx.copy(shake = it)
            }
            Button(
                onClick = { vm.setSelectedEffects(fx) },
                modifier = Modifier.fillMaxWidth().testTag("apply_clip_effects_button"),
            ) { Text("Apply Clip Effects") }
        }

        HorizontalDivider()
        Text("V2 Overlay / Picture-in-Picture", style = MaterialTheme.typography.labelMedium)
        if (vm.clips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.clips.take(6).forEach { clip ->
                    OutlinedButton(onClick = { vm.addOverlayClipFromClip(clip.meta.index) }) {
                        Text("+ PIP Clip #${clip.meta.index + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (overlayItem != null) {
            EffectSliderRow("PIP X", ovBounds.x, 0f..0.75f, { "${(it * 100).toInt()}%" }) {
                ovBounds = ovBounds.copy(x = it)
            }
            EffectSliderRow("PIP Y", ovBounds.y, 0f..0.75f, { "${(it * 100).toInt()}%" }) {
                ovBounds = ovBounds.copy(y = it)
            }
            EffectSliderRow("PIP Width", ovBounds.widthFraction, 0.18f..0.70f, { "${(it * 100).toInt()}%" }) {
                ovBounds = ovBounds.copy(widthFraction = it)
            }
            EffectSliderRow("PIP Opacity", ovBounds.opacity, 0.2f..1f, { "${(it * 100).toInt()}%" }) {
                ovBounds = ovBounds.copy(opacity = it)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.updateOverlayBounds(overlayItem.id, ovBounds) }, modifier = Modifier.weight(1f)) {
                    Text("Update PIP")
                }
                OutlinedButton(onClick = { vm.removeOverlayItem(overlayItem.id) }) { Text("Remove PIP") }
            }
        }

        if (vm.storyBeats.isNotEmpty()) {
            HorizontalDivider()
            Text("Detected Story Arc & Highlights", style = MaterialTheme.typography.labelMedium)
            vm.storyBeats.take(5).forEach { b ->
                Text(
                    "${b.act.label}: clip #${b.clipIndex + 1} (${round1(b.startSec)}–${round1(b.endSec)}s) — ${b.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EffectSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(format(value), style = MonoTimecodeStyle, modifier = Modifier.width(44.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportPanel(
    vm: EditorViewModel,
    onSaveGallery: () -> Unit,
    onShare: () -> Unit,
) {
    val project by vm.timelineProject.collectAsStateWithLifecycle()
    val es = vm.exportSettings
    val caps = vm.deviceCapabilities
    val durationSec = usToSec(project.totalDurationUs).coerceAtLeast(1.0)
    val estMb = DeviceCapabilities.estimateOutputMb(es.videoBitrateBps, es.audioBitrateBps, durationSec)
    val warning = DeviceCapabilities.preflightWarning(caps, es, durationSec)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Device Hardware Profile", style = MaterialTheme.typography.labelLarge)
                Text(caps.summaryLabel(), style = MonoTimecodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "RAM: ${caps.availableRamMb} / ${caps.totalRamMb} MB · Est. output: ${String.format(Locale.US, "%.1f", estMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (warning != null) {
            Card(Modifier.fillMaxWidth()) {
                Text(warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }

        Text("Export Quality Preset", style = MaterialTheme.typography.labelMedium)
        val availablePresets = ExportPreset.entries.filter { p ->
            p.resolution.shortEdge <= caps.maxResolution.shortEdge && (p.fps <= 30 || caps.supports60Fps)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availablePresets.forEach { preset ->
                val selected = es.resolution == preset.resolution && es.fps == preset.fps
                FilterChip(
                    selected = selected,
                    onClick = { vm.applyExportPreset(preset) },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text("Audio Bitrate", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(128, 192, 256, 320).forEach { kbps ->
                FilterChip(
                    selected = es.audioBitrateBps == kbps * 1000,
                    onClick = { vm.updateAudioBitrate(kbps) },
                    label = { Text("${kbps} kbps", style = MonoTimecodeStyle) },
                )
            }
        }

        if (caps.supportsHevc) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Use H.265 (HEVC) Encoder", style = MaterialTheme.typography.bodySmall)
                    Text("Smaller files at same quality; falls back to H.264 if needed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = es.useHevc, onCheckedChange = { vm.setUseHevc(it) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Burn captions into exported video", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = es.burnCaptions, onCheckedChange = { vm.exportSettings = es.copy(burnCaptions = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Safe shot-by-shot render mode", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = es.forceSafeMode, onCheckedChange = {
                vm.exportSettings = es.copy(forceSafeMode = it)
                vm.settings.safeRender = it
            })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { vm.renderQuickPreviewExport() },
                enabled = !vm.busy && vm.clips.isNotEmpty(),
                modifier = Modifier.height(48.dp).testTag("export_preview_button"),
            ) { Text("720p Preview") }
            Button(
                onClick = { vm.renderTimelineOnly() },
                enabled = !vm.busy && vm.clips.isNotEmpty(),
                modifier = Modifier.weight(1f).height(48.dp).testTag("export_render_button"),
            ) { Text("Render ${es.resolution.label} (${es.fps} FPS)") }
        }

        if (vm.output != null && !vm.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSaveGallery, modifier = Modifier.weight(1f).height(48.dp).testTag("save_gallery_button")) {
                    Text("Save to Gallery")
                }
                Button(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp).testTag("share_video_button")) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }

        vm.exportDiagnostics?.let { diag ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("export_diagnostics_card")) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Export Verification: " + if (diag.verifiedOk) "VERIFIED MP4" else "CHECK WARNING",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${diag.codec} · ${diag.width}×${diag.height} · ${diag.fps} FPS · ${diag.audioBitrateBps / 1000} kbps audio",
                        style = MonoTimecodeStyle,
                    )
                    Text(
                        "Size: ${String.format(Locale.US, "%.2f", diag.fileSizeBytes / (1024.0 * 1024.0))} MB · Render: ${round1(diag.renderTimeMs / 1000.0)}s · Video=${diag.hasVideoTrack} Audio=${diag.hasAudioTrack}" +
                            if (diag.usedSafeMode) " (Safe Mode)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        vm.qa?.let { report ->
            QaCard(report = report, onApplyRepair = { vm.applySuggestedRepair(it) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QaCard(report: QaReport, onApplyRepair: (com.promptcut.app.SuggestedRepair) -> Unit) {
    var open by remember(report) { mutableStateOf(!report.ok) }
    OutlinedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().testTag("qa_report_card")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (report.ok) "Quality Check Passed (${report.items.size} checks)"
                else "Quality Check: ${report.issues.size} warning(s)",
                style = MaterialTheme.typography.labelLarge,
            )
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.items.forEach { it ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    (if (it.ok) "OK " else "! ") + it.label + if (it.detail.isNotBlank()) " · ${it.detail}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                if (report.suggestedRepairs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Suggested Repairs (tap to apply)", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        report.suggestedRepairs.forEach { r ->
                            OutlinedButton(onClick = { onApplyRepair(r) }) {
                                Text(r.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
