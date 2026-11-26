package com.promptcut.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.promptcut.app.Aspect
import com.promptcut.app.ChatMessage
import com.promptcut.app.Clip
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.EditorViewModel
import com.promptcut.app.LENGTHS
import com.promptcut.app.Mood
import com.promptcut.app.MusicLibrary
import com.promptcut.app.MusicSource
import com.promptcut.app.OCCASIONS
import com.promptcut.app.Opt
import com.promptcut.app.OrderMode
import com.promptcut.app.SoundMode
import com.promptcut.app.Span
import com.promptcut.app.SpeedMode
import com.promptcut.app.Transition
import com.promptcut.app.agent.AgentState
import com.promptcut.app.round1
import com.promptcut.app.round2
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPanel(
    vm: EditorViewModel,
    agentState: AgentState,
    onPickVideos: () -> Unit,
    onEditClip: (Int) -> Unit,
    onQuickPrompt: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Source clips tray
        LazyRow(
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.clips, key = { it.meta.index }) { clip ->
                ClipTile(
                    clip = clip,
                    cut = vm.clipCuts.containsKey(clip.meta.index),
                    onOpen = { onEditClip(clip.meta.index) },
                    onRemove = { vm.removeClip(clip) },
                )
            }
            item {
                OutlinedCard(
                    onClick = onPickVideos,
                    modifier = Modifier.size(84.dp).testTag("add_videos_card"),
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add videos")
                        Text(if (vm.clips.isEmpty()) "Add clips" else "Add more", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Manual-edit / destructive-change protection approval card
        vm.pendingManualEditReason?.let { reason ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().testTag("manual_edit_approval_card"),
                border = CardDefaults.outlinedCardBorder().copy(width = 1.5.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Timeline Edit Confirmation", style = MaterialTheme.typography.titleSmall)
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.dismissManualEditApproval() }) { Text("Keep Existing") }
                        Button(onClick = { vm.approveManualEditOverwrite() }) { Text("Approve AI Change") }
                    }
                }
            }
        }

        // Live Agent State & Executed Tools
        if (agentState.isRunning || agentState.executedSteps.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${agentState.phase.label}: ${agentState.statusText}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (agentState.executedSteps.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            agentState.executedSteps.takeLast(6).forEach { st ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${st.toolName}: ${st.summary}", style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // V3 Quick Agent Prompts
        if (vm.clips.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "30s Gaming Edit" to "Create a 30-second gaming edit starting with the best moment, remove boring parts, add subtitles, sync with music, and make a strong ending.",
                    "Sync cuts to beat" to "Detect beats and snap video cuts cleanly to the musical beat.",
                    "Trim silences" to "Trim silent and dull sections while keeping speech.",
                    "Film grain & vignette" to "Add subtle cinematic vignette and film grain to the clips.",
                    "Keyword captions" to "Add bold captions with auto-punctuation and highlight important keywords.",
                ).forEach { (title, prompt) ->
                    AssistChip(
                        onClick = { onQuickPrompt(prompt) },
                        label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // Chat log
        if (vm.chat.isEmpty()) {
            Text(
                "Describe an entire edit or quick change in plain language. Context, track locks, and manual edits are respected across turns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.chat.takeLast(12).forEach { msg -> ChatBubble(msg) }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val bg = when {
        m.isError -> MaterialTheme.colorScheme.surfaceContainerHighest
        m.fromUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        m.fromUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            text = m.text,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylePanel(vm: EditorViewModel, pickSong: () -> Unit, onCreate: () -> Unit) {
    val project by vm.timelineProject.collectAsStateWithLifecycle()
    var customW by remember(project.customAspectWidth) { mutableStateOf(project.customAspectWidth.toString()) }
    var customH by remember(project.customAspectHeight) { mutableStateOf(project.customAspectHeight.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val s = vm.suggestions
        when {
            vm.analyzing && s == null -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Analyzing footage on-device…", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Detecting scenes, faces, smiles, sharpness, audio peaks, and speech.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            s != null && s.understanding.isNotBlank() -> Card(Modifier.fillMaxWidth()) {
                Text(s.understanding, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (s != null && s.presets.isNotEmpty()) {
            Text("Suggested Presets", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.presets.forEach { p ->
                    val sel = vm.selectedPreset == p
                    OutlinedCard(
                        onClick = { if (sel) vm.clearPreset() else vm.applyPreset(p) },
                        modifier = Modifier.width(205.dp),
                        border = if (sel) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
                        colors = if (sel) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text(
                                "${p.lengthSec}s · ${p.aspect.key} · ${p.color.label}",
                                style = MonoTimecodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Style Controls", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (vm.off.isNotEmpty()) TextButton(onClick = { vm.useDefaults() }) { Text("Defaults") }
            if (!vm.nothingSelected) TextButton(onClick = { vm.clearAll() }) { Text("Clear all") }
        }

        OptRow(vm, Opt.OCCASION, "Category", OCCASIONS, vm.occasion, { it.replaceFirstChar { c -> c.uppercase() } }) { vm.occasion = it }
        OptRow(vm, Opt.MOOD, "Mood", Mood.entries, vm.mood, { it.label }) { vm.mood = it }
        OptRow(vm, Opt.LENGTH, "Duration", LENGTHS, vm.lengthSec, { when { it == 0 -> "Auto"; it >= 120 -> "${it / 60}m"; else -> "${it}s" } }) { vm.setLength(it) }
        OptRow(vm, Opt.FORMAT, "Aspect", Aspect.entries, vm.aspect, { it.label }) { vm.setProjectAspect(it) }
        if (vm.aspect == Aspect.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = customW, onValueChange = { customW = it }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = customH, onValueChange = { customH = it }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    vm.updateCustomAspect(customW.toIntOrNull() ?: 1080, customH.toIntOrNull() ?: 1350)
                }) { Text("Set") }
            }
        }
        OptRow(vm, Opt.LOOK, "Look", ColorLook.entries, vm.color, { it.label }) { vm.setProjectLook(it) }
        OptRow(vm, Opt.TRANSITION, "Transitions", Transition.entries, vm.transition, {
            if (it == Transition.AUTO) "Auto (${Transition.forMood(vm.mood).label})" else it.label
        }) { vm.transition = it }
        OptRow(vm, Opt.MUSIC, "Music", MusicSource.entries, vm.musicSource, { it.label }) {
            vm.musicSource = it
            if (it == MusicSource.MY_SONG && vm.song == null) pickSong()
            if (it == MusicSource.LIBRARY && vm.libResults.isEmpty()) vm.searchLibrary()
        }
        OptRow(vm, Opt.SPEED, "Pacing", SpeedMode.entries, vm.speedMode, { it.label }) { vm.speedMode = it }
        OptRow(vm, Opt.SOUND, "Clip Audio", SoundMode.entries, vm.soundMode, { it.label }) { vm.soundMode = it }
        OptRow(vm, Opt.ORDER, "Order", OrderMode.entries, vm.orderMode, { it.label }) { vm.orderMode = it }

        Button(
            onClick = onCreate,
            enabled = !vm.busy && vm.clips.isNotEmpty() && (vm.musicSource != MusicSource.MY_SONG || vm.song != null) && vm.libLoadingId == null,
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("create_video_button"),
        ) { Text("Auto-Edit with AI", style = MaterialTheme.typography.titleSmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptRow(
    vm: EditorViewModel,
    opt: Opt,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    val on = vm.isOn(opt) && !(opt == Opt.USE_ALL && !vm.useAllClips)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (on) title else "$title · auto",
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val sel = on && o == selected
                FilterChip(
                    selected = sel,
                    onClick = { if (sel) vm.unset(opt) else { vm.turnOn(opt); onPick(o) } },
                    label = { Text(label(o), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@AndroidXOptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPanel(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playingId by remember { mutableStateOf<String?>(null) }
    val searchInView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.release() } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.libSong?.let { s ->
            Text(
                "Active track: ${s.name}" + (s.tempo?.let { " · ${it.bpm.toInt()} BPM" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.libQuery,
                onValueChange = { vm.libQuery = it },
                singleLine = true,
                placeholder = { Text(MusicLibrary.defaultQuery(vm.mood, vm.occasion)) },
                label = { Text("Search Creative Commons music") },
                modifier = Modifier.weight(1f).bringIntoViewRequester(searchInView)
                    .onFocusEvent { if (it.isFocused) scope.launch { delay(300); searchInView.bringIntoView() } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchLibrary() }),
            )
            TextButton(onClick = { vm.searchLibrary() }, enabled = !vm.libSearching) {
                Text(if (vm.libSearching) "…" else "Search")
            }
        }
        vm.libError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        vm.libResults.take(8).forEach { t ->
            val chosen = vm.libSong?.name == "${t.title} – ${t.artist}"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    if (playingId == t.id) { player.stop(); playingId = null }
                    else { player.setMediaItem(MediaItem.fromUri(t.url)); player.prepare(); player.play(); playingId = t.id }
                }) { Text(if (playingId == t.id) "Stop" else "Play") }
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        "${t.artist} · ${(t.durationSec / 60).toInt()}:${"%02d".format((t.durationSec % 60).toInt())}",
                        style = MonoTimecodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    vm.libLoadingId == t.id -> Text("Loading…", style = MaterialTheme.typography.labelSmall)
                    chosen -> Text("Selected", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    else -> TextButton(onClick = { player.stop(); playingId = null; vm.useLibraryTrack(t) }) { Text("Use") }
                }
            }
        }
    }
}

@Composable
fun ClipTile(clip: Clip, cut: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen),
    ) {
        clip.thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = clip.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            "#${clip.meta.index + 1} · ${round2(clip.meta.durationSec)}s",
            style = MonoTimecodeStyle,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        if (clip.meta.crop != null || cut) {
            Text(
                if (clip.meta.crop != null) "Crop" else "Cut",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xB3000000))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove clip", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun ClipEditDialog(vm: EditorViewModel, clip: Clip, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri) {
        frame = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, clip.uri)
                    r.getScaledFrameAtTime((clip.meta.durationSec * 500_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
                }
            }.getOrNull()
        }
    }
    var box by remember { mutableStateOf(clip.meta.crop ?: CropBox(0.1, 0.1, 0.9, 0.9)) }
    var cropOn by remember { mutableStateOf(clip.meta.crop != null) }
    val dur = clip.meta.durationSec
    val cuts = vm.clipCuts[clip.meta.index].orEmpty()
    val kept = remember {
        mutableStateOf(
            (cuts.firstOrNull { it.start <= 0.01 }?.end ?: 0.0).toFloat()..(cuts.firstOrNull { it.end >= dur - 0.01 }?.start ?: dur).toFloat()
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Clip #${clip.meta.index + 1} · ${round1(dur)}s") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom Crop Region", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = cropOn, onCheckedChange = { cropOn = it })
                }
                frame?.let { bmp ->
                    val ratio = bmp.width.toFloat() / bmp.height
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        if (cropOn) CropOverlay(box) { box = it }
                    }
                } ?: Text("Decoding preview frame…", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Source Range: ${round1(kept.value.start.toDouble())}–${round1(kept.value.endInclusive.toDouble())}s", style = MonoTimecodeStyle)
                RangeSlider(value = kept.value, onValueChange = { kept.value = it }, valueRange = 0f..dur.toFloat())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setCrop(clip.meta.index, if (cropOn) box else null)
                val middle = vm.clipCuts[clip.meta.index].orEmpty().filter { it.start > 0.01 && it.end < dur - 0.01 }
                val ends = listOfNotNull(
                    Span(0.0, kept.value.start.toDouble(), "outside kept part").takeIf { kept.value.start > 0.05f },
                    Span(kept.value.endInclusive.toDouble(), dur, "outside kept part").takeIf { kept.value.endInclusive < dur - 0.05 },
                )
                vm.setCuts(clip.meta.index, middle + ends)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun CropOverlay(box: CropBox, onChange: (CropBox) -> Unit) {
    val current by rememberUpdatedState(box)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var mode = 0
            detectDragGestures(
                onDragStart = { p ->
                    val b = current
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val corners = listOf(b.x0 to b.y0, b.x1 to b.y0, b.x0 to b.y1, b.x1 to b.y1)
                        .map { (x, y) -> Offset((x * w).toFloat(), (y * h).toFloat()) }
                    val near = corners.withIndex().minByOrNull { (it.value - p).getDistance() }!!
                    mode = if ((near.value - p).getDistance() < 60f) near.index + 1
                    else if (p.x / w in b.x0..b.x1 && p.y / h in b.y0..b.y1) 5 else 0
                },
                onDrag = { change, d ->
                    change.consume()
                    val dx = d.x / size.width; val dy = d.y / size.height
                    val b = current
                    val nb = when (mode) {
                        1 -> b.copy(x0 = b.x0 + dx, y0 = b.y0 + dy)
                        2 -> b.copy(x1 = b.x1 + dx, y0 = b.y0 + dy)
                        3 -> b.copy(x0 = b.x0 + dx, y1 = b.y1 + dy)
                        4 -> b.copy(x1 = b.x1 + dx, y1 = b.y1 + dy)
                        5 -> {
                            val mx = dx.toDouble().coerceIn(-b.x0, 1 - b.x1); val my = dy.toDouble().coerceIn(-b.y0, 1 - b.y1)
                            CropBox(b.x0 + mx, b.y0 + my, b.x1 + mx, b.y1 + my)
                        }
                        else -> b
                    }
                    val c = CropBox(nb.x0.coerceIn(0.0, 1.0), nb.y0.coerceIn(0.0, 1.0), nb.x1.coerceIn(0.0, 1.0), nb.y1.coerceIn(0.0, 1.0))
                    if (c.width >= 0.1 && c.height >= 0.1) onChange(c)
                },
            )
        }
    ) {
        val l = (box.x0 * size.width).toFloat(); val t = (box.y0 * size.height).toFloat()
        val r = (box.x1 * size.width).toFloat(); val btm = (box.y1 * size.height).toFloat()
        val shade = Color(0x99000000)
        drawRect(shade, size = Size(size.width, t))
        drawRect(shade, topLeft = Offset(0f, btm), size = Size(size.width, size.height - btm))
        drawRect(shade, topLeft = Offset(0f, t), size = Size(l, btm - t))
        drawRect(shade, topLeft = Offset(r, t), size = Size(size.width - r, btm - t))
        drawRect(Color.White, topLeft = Offset(l, t), size = Size(r - l, btm - t), style = Stroke(4f))
        listOf(l to t, r to t, l to btm, r to btm).forEach { (x, y) -> drawCircle(Color.White, 12f, Offset(x, y)) }
    }
}
