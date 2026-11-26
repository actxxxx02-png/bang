package com.promptcut.app.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promptcut.app.EditorViewModel
import com.promptcut.app.data.db.ProjectEntity
import com.promptcut.app.round1
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectsScreen(vm: EditorViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val ctx = LocalContext.current
    val active by vm.activeProjects.collectAsStateWithLifecycle()
    val trashed by vm.trashedProjects.collectAsStateWithLifecycle()
    var showTrash by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var relinkTargetIndex by remember { mutableIntStateOf(-1) }

    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && relinkTargetIndex >= 0) {
            vm.relinkMissingClip(relinkTargetIndex, uri)
        }
        relinkTargetIndex = -1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("projects_close_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close projects")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistoryDialog = true }, modifier = Modifier.size(48.dp).testTag("edit_history_button")) {
                        Icon(Icons.Default.History, contentDescription = "Edit history")
                    }
                    TextButton(onClick = { showImportDialog = true }) { Text("Import") }
                    IconButton(onClick = { showNewDialog = true }, modifier = Modifier.size(48.dp).testTag("new_project_button")) {
                        Icon(Icons.Default.Add, contentDescription = "New project")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.missingAssetIndices.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth().testTag("missing_media_banner")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Missing Media Detected", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "One or more video files moved or lost permission. Relink them to restore full timeline playback and export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.missingAssetIndices.forEach { clipIdx ->
                                OutlinedButton(onClick = {
                                    relinkTargetIndex = clipIdx
                                    relinkLauncher.launch(arrayOf("video/*"))
                                }) {
                                    Text("Relink Clip #${clipIdx + 1}")
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showTrash, onClick = { showTrash = false }, label = { Text("Active (${active.size})") })
                FilterChip(selected = showTrash, onClick = { showTrash = true }, label = { Text("Trash (${trashed.size})") })
            }

            val list = if (showTrash) trashed else active
            if (list.isEmpty()) {
                Text(
                    if (showTrash) "Trash is empty." else "No saved projects yet. Tap + or start editing to autosave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { p ->
                        val thumbBmp = remember(p.thumbnailPath) {
                            p.thumbnailPath?.let { path ->
                                File(path).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                            }
                        }
                        OutlinedCard(
                            onClick = { if (!showTrash) vm.openSavedProject(p.id) },
                            modifier = Modifier.fillMaxWidth(),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (thumbBmp != null) {
                                            Image(
                                                bitmap = thumbBmp.asImageBitmap(),
                                                contentDescription = "${p.name} thumbnail",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${p.clipCount} clips · ${round1(p.durationSec)}s · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.updatedAtMs))}",
                                            style = MonoTimecodeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (p.missingAssetsCount > 0) {
                                            Text(
                                                "MISSING MEDIA (${p.missingAssetsCount})",
                                                style = MonoTimecodeStyle,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    if (!showTrash) {
                                        IconButton(onClick = { renameTarget = p }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename project")
                                        }
                                        IconButton(onClick = { vm.duplicateSavedProject(p.id) }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate project")
                                        }
                                        IconButton(
                                            onClick = {
                                                vm.exportProjectJsonString(p.id) { json ->
                                                    ctx.startActivity(
                                                        Intent.createChooser(
                                                            Intent(Intent.ACTION_SEND).setType("application/json")
                                                                .putExtra(Intent.EXTRA_SUBJECT, "${p.name}.promptcut.json")
                                                                .putExtra(Intent.EXTRA_TEXT, json),
                                                            "Export Project JSON",
                                                        )
                                                    )
                                                }
                                            },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = "Export project JSON")
                                        }
                                        IconButton(onClick = { vm.softDeleteSavedProject(p.id) }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "Move to trash")
                                        }
                                    } else {
                                        IconButton(onClick = { vm.restoreSavedProject(p.id) }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore project")
                                        }
                                        TextButton(onClick = { vm.purgeSavedProject(p.id) }) { Text("Purge") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistoryDialog) {
        val entries = vm.editHistoryEntries()
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Timeline Edit History") },
            text = {
                if (entries.isEmpty()) {
                    Text("No edits recorded yet in this session.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entries.forEachIndexed { idx, label ->
                            Text("${idx + 1}. $label", style = MonoTimecodeStyle)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) { Text("Close") }
            },
        )
    }

    if (showNewDialog) {
        var name by remember { mutableStateOf("New Edit") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New Project") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { vm.createNewProject(name); showNewDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { vm.renameProject(target.id, newName); renameTarget = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    if (showImportDialog) {
        var rawJson by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Project / Legacy Plan JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste exported PromptCut JSON or legacy v1 EditPlan JSON.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = rawJson, onValueChange = { rawJson = it }, minLines = 4, maxLines = 8)
                }
            },
            confirmButton = {
                Button(enabled = rawJson.isNotBlank(), onClick = {
                    vm.importProjectJsonString(rawJson)
                    showImportDialog = false
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancel") } },
        )
    }
}
