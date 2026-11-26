package com.promptcut.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promptcut.app.ui.components.AgentPanel
import com.promptcut.app.ui.components.AudioStudioPanel
import com.promptcut.app.ui.components.CaptionsPanel
import com.promptcut.app.ui.components.ClipEditDialog
import com.promptcut.app.ui.components.EditInspectorPanel
import com.promptcut.app.ui.components.EffectsPanel
import com.promptcut.app.ui.components.ExportPanel
import com.promptcut.app.ui.components.PreviewStage
import com.promptcut.app.ui.components.ProjectsScreen
import com.promptcut.app.ui.components.SettingsScreen
import com.promptcut.app.ui.components.StylePanel
import com.promptcut.app.ui.components.TimelineEditor
import com.promptcut.app.ui.theme.PromptCutTheme

/**
 * Privacy policy URL hosted via GitHub Pages from docs/privacy-policy.html.
 * Enable Settings -> Pages -> Source: GitHub Actions in the repository to serve it live.
 */
const val PRIVACY_POLICY_URL = "https://actxxxx02-png.github.io/AngKur-v2/privacy-policy.html"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: EditorViewModel = viewModel()
            var themePref by remember { mutableStateOf(vm.settings.themePreference) }
            PromptCutTheme(preference = themePref) {
                when {
                    vm.showSettings -> SettingsScreen(
                        vm = vm,
                        onThemeChanged = { themePref = it },
                        onClose = { vm.showSettings = false },
                    )
                    vm.showProjectsSheet -> ProjectsScreen(
                        vm = vm,
                        onClose = { vm.showProjectsSheet = false },
                    )
                    else -> EditorScreen(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val project by vm.timelineProject.collectAsStateWithLifecycle()
    val agentState by vm.agentState.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var editingClipIndex by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { vm.addClips(it) }
    val pickVideos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pickSong(it) } }
    val pickSong = { songPicker.launch(arrayOf("audio/*")) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(vm.busy) {
        if (!vm.busy && vm.output != null) vm.activeTab = EditorTab.EXPORT
    }

    editingClipIndex?.let { idx ->
        vm.clips.firstOrNull { it.meta.index == idx }?.let { c ->
            ClipEditDialog(vm, c) { editingClipIndex = null }
        }
    }

    val submit: (String) -> Unit = { raw ->
        val txt = raw.trim()
        if (vm.plan == null && vm._timelineReadyForAi(project)) {
            vm.create(txt)
        } else if (txt.isNotEmpty()) {
            vm.send(txt)
        } else {
            vm.create("")
        }
        vm.activeTab = EditorTab.AGENT
        vm.panelExpanded = true
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            EditorMediaHelper.describeTimelineBrief(project),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = pickVideos, modifier = Modifier.size(48.dp).testTag("topbar_add_media")) {
                        Icon(Icons.Default.Add, contentDescription = "Add media")
                    }
                    IconButton(onClick = { vm.showProjectsSheet = true }, modifier = Modifier.size(48.dp).testTag("topbar_projects")) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Projects")
                    }
                    IconButton(onClick = { vm.startOver() }, modifier = Modifier.size(48.dp).testTag("topbar_reset")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset workspace")
                    }
                    IconButton(onClick = { vm.showSettings = true }, modifier = Modifier.size(48.dp).testTag("topbar_settings")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Session / Interrupted Render Recovery Banner
            if (vm.recoverableSession != null || vm.recoverableRenderJob != null) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Recover Previous Session", style = MaterialTheme.typography.labelLarge)
                            Text(
                                vm.recoverableRenderJob?.message ?: "Resume \"${vm.recoverableSession?.name ?: "edit"}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.dismissRecoverableBanner() }) { Text("Dismiss") }
                        Button(onClick = { vm.resumeInterruptedSession() }) { Text("Resume") }
                    }
                }
            }

            // Video Stage + Transport
            PreviewStage(
                project = project,
                clips = vm.clips,
                renderedOutput = vm.output,
                playheadUs = vm.playheadUs,
                isPlaying = vm.isPlaying,
                preview720p = vm.settings.preview720pMode,
                fullEffectsPreview = vm.settings.fullEffectsPreview,
                previewSpeed = vm.previewSpeed,
                showSafeGuides = vm.showSafeGuides,
                compareOriginal = vm.compareOriginal,
                audioMonitorMuted = vm.audioMonitorMuted,
                onPlayheadChange = { vm.playheadUs = it },
                onTogglePlay = { vm.isPlaying = !vm.isPlaying },
                onPreviewSpeedChange = { vm.previewSpeed = it },
                onToggleSafeGuides = { vm.showSafeGuides = !vm.showSafeGuides },
                onToggleCompareOriginal = { vm.compareOriginal = !vm.compareOriginal },
                onToggleAudioMute = { vm.audioMonitorMuted = !vm.audioMonitorMuted },
                onAddMedia = pickVideos,
            )

            // Multi-Track Timeline
            TimelineEditor(
                project = project,
                playheadUs = vm.playheadUs,
                zoom = vm.timelineZoom,
                selectedVideoItemId = vm.selectedVideoItemId,
                selectedCaptionId = vm.selectedCaptionId,
                canUndo = vm.canUndo,
                canRedo = vm.canRedo,
                segmentThumbnails = vm.segmentThumbnails,
                musicWaveform = vm.musicWaveform,
                clipWaveforms = vm.clipWaveforms,
                onPlayheadChange = { vm.playheadUs = it },
                onZoomChange = { vm.timelineZoom = it },
                onSelectVideoItem = {
                    vm.selectedVideoItemId = it
                    vm.activeTab = EditorTab.EDIT
                    vm.panelExpanded = true
                },
                onSelectCaption = {
                    vm.selectedCaptionId = it
                    vm.activeTab = EditorTab.CAPTIONS
                    vm.panelExpanded = true
                },
                onUndo = { vm.undo() },
                onRedo = { vm.redo() },
                onSplit = { vm.splitSelectedAtPlayhead() },
                onDuplicate = { vm.duplicateSelectedSegment() },
                onDelete = { vm.deleteSelectedSegment() },
                onMoveLeft = { vm.moveSelectedSegment(-1) },
                onMoveRight = { vm.moveSelectedSegment(1) },
                onTrimItem = { itemId, startUs, endUs -> vm.trimVideoItemEdges(itemId, startUs, endUs) },
                onToggleTrackLock = { vm.toggleTrackLock(it) },
                onToggleTrackVisibility = { vm.toggleTrackVisibility(it) },
            )

            // CapCut-style Dock Tabs + Collapse Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EditorTab.entries.forEach { tab ->
                        FilterChip(
                            selected = vm.activeTab == tab && vm.panelExpanded,
                            onClick = {
                                if (vm.activeTab == tab) vm.panelExpanded = !vm.panelExpanded
                                else {
                                    vm.activeTab = tab
                                    vm.panelExpanded = true
                                }
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                        )
                    }
                }
                IconButton(
                    onClick = { vm.panelExpanded = !vm.panelExpanded },
                    modifier = Modifier.size(48.dp).testTag("toggle_panel_button"),
                ) {
                    Icon(
                        if (vm.panelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (vm.panelExpanded) "Collapse tool panel" else "Expand tool panel",
                    )
                }
            }

            // Expandable Tool Panel Workspace
            AnimatedVisibility(visible = vm.panelExpanded, modifier = Modifier.weight(1f, fill = true)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    when (vm.activeTab) {
                        EditorTab.AGENT -> AgentPanel(
                            vm = vm,
                            agentState = agentState,
                            onPickVideos = pickVideos,
                            onEditClip = { editingClipIndex = it },
                            onQuickPrompt = { p -> submit(p) },
                        )
                        EditorTab.STYLE -> StylePanel(vm, pickSong = pickSong, onCreate = { submit(input) })
                        EditorTab.EDIT -> EditInspectorPanel(vm, project, onOpenCrop = { editingClipIndex = it })
                        EditorTab.CAPTIONS -> CaptionsPanel(vm, project)
                        EditorTab.AUDIO -> AudioStudioPanel(vm, onPickSong = pickSong)
                        EditorTab.EFFECTS -> EffectsPanel(vm, project)
                        EditorTab.EXPORT -> ExportPanel(
                            vm = vm,
                            onSaveGallery = { vm.saveToGallery { } },
                            onShare = {
                                vm.saveToGallery { uri ->
                                    if (uri != null) {
                                        runCatching {
                                            ctx.startActivity(
                                                Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND).setType("video/mp4")
                                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                                        .apply { vm.shareCredit?.let { putExtra(Intent.EXTRA_TEXT, it) } }
                                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                    "Share Video",
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (!vm.panelExpanded) {
                Spacer(Modifier.weight(1f))
            }

            // Status / Progress Bar
            vm.status?.let { st ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            st + if (vm.progress >= 0) " ${vm.progress}%" else "",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (vm.busy) TextButton(onClick = { vm.cancel() }) { Text("Cancel") }
                    }
                    if (vm.busy || vm.analyzing) {
                        if (vm.progress >= 0) LinearProgressIndicator(progress = { vm.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Bottom AI Prompt Bar
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (project.mainVideoTrack.items.isEmpty()) "Describe your edit or add clips…"
                            else "Tell the AI agent what to change…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.weight(1f).testTag("agent_prompt_input"),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) submit(input) }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = !vm.busy && input.isNotBlank() && vm.clips.isNotEmpty(),
                    onClick = { submit(input) },
                    modifier = Modifier.size(48.dp).testTag("agent_send_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt")
                }
            }
        }
    }
}

private fun EditorViewModel._timelineReadyForAi(project: com.promptcut.app.timeline.TimelineProject): Boolean =
    project.mainVideoTrack.items.isEmpty() || plan == null
