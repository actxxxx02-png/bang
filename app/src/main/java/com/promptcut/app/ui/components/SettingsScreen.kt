package com.promptcut.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promptcut.app.EditorViewModel
import com.promptcut.app.PRIVACY_POLICY_URL
import com.promptcut.app.ThemePreference
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.ApiKeyRecord
import com.promptcut.app.security.KeyHealthStatus
import com.promptcut.app.security.ProviderKeySummary
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: EditorViewModel,
    onThemeChanged: (ThemePreference) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyRevision by vm.settings.keyManager.revision.collectAsStateWithLifecycle()

    var selectedProvider by remember { mutableStateOf(vm.settings.manualProviderId) }
    var keyNameInput by remember(selectedProvider) { mutableStateOf("") }
    var keyInput by remember(selectedProvider) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var testingSingle by remember { mutableStateOf(false) }
    var testingKeyId by remember { mutableStateOf<String?>(null) }
    var testingAllProgress by remember { mutableStateOf<String?>(null) }
    var actionFeedback by remember(selectedProvider) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var availableModels by remember(selectedProvider) { mutableStateOf<List<String>>(emptyList()) }

    var renameTarget by remember { mutableStateOf<ApiKeyRecord?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    var routingAuto by remember { mutableStateOf(vm.settings.routingModeAuto) }
    var themePref by remember { mutableStateOf(vm.settings.themePreference) }
    var preview720p by remember { mutableStateOf(vm.settings.preview720pMode) }
    var fullShaders by remember { mutableStateOf(vm.settings.fullEffectsPreview) }
    var preferHevc by remember { mutableStateOf(vm.settings.preferredCodecHevc) }
    var audioKbps by remember { mutableIntStateOf(vm.settings.preferredAudioBitrateKbps) }
    var allowFrames by remember { mutableStateOf(vm.settings.privacy.allowFrameSamplingToAi) }
    var allowStt by remember { mutableStateOf(vm.settings.privacy.allowAudioToGroqStt) }
    var claudeConsent by remember { mutableStateOf(vm.settings.privacy.claudePaidConsentGranted) }
    var showClaudeConsentDialog by remember { mutableStateOf(false) }

    val nowMs = System.currentTimeMillis()
    val allKeys = remember(keyRevision) { vm.settings.keyManager.allKeys() }
    val providerKeys = remember(keyRevision, selectedProvider) { vm.settings.keyManager.keysFor(selectedProvider) }
    val canClose = remember(keyRevision, claudeConsent) { vm.settings.hasActiveKey }

    BackHandler(enabled = canClose) { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (canClose) "AI Keys, Health & Studio Settings" else "Connect an AI Provider") },
                navigationIcon = {
                    if (canClose) {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("settings_close_button")) {
                            Icon(Icons.Default.Close, contentDescription = "Close settings")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "PromptCut edits and renders locally on your phone. Add one or more keys per provider — PromptCut rotates automatically within the same provider on rate limit or invalid keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val secError = vm.settings.keyManager.migrationError ?: vm.settings.lastSecurityError
            if (!secError.isNullOrBlank()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Key Storage Notice", style = MaterialTheme.typography.titleSmall)
                        Text(secError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Routing strategy
            Text("Provider Routing", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = routingAuto,
                    onClick = {
                        routingAuto = true
                        vm.settings.routingModeAuto = true
                    },
                    label = { Text("Free-First Auto (Recommended)") },
                )
                FilterChip(
                    selected = !routingAuto,
                    onClick = {
                        routingAuto = false
                        vm.settings.routingModeAuto = false
                        vm.settings.manualProviderId = selectedProvider
                    },
                    label = { Text("Manual: ${selectedProvider.shortName}") },
                )
            }
            Text(
                "Auto routes visual understanding to Gemini (free) and text/speech to Groq (free), rotating across enabled keys first. Paid Claude is NEVER auto-selected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Provider cards with full status summary counts
            ProviderId.entries.forEach { pid ->
                val isSelected = pid == selectedProvider
                val summary = remember(keyRevision, pid) { vm.settings.keyManager.summaryFor(pid, nowMs) }
                OutlinedCard(
                    onClick = {
                        selectedProvider = pid
                        if (!routingAuto) vm.settings.manualProviderId = pid
                    },
                    border = if (isSelected) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
                    colors = if (isSelected) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.outlinedCardColors(),
                    modifier = Modifier.fillMaxWidth().testTag("provider_card_${pid.name}"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isSelected, onClick = {
                            selectedProvider = pid
                            if (!routingAuto) vm.settings.manualProviderId = pid
                        })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(pid.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    if (pid.isPaidByDefault) "PAID API" else "FREE TIER",
                                    style = MonoTimecodeStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(providerTagline(pid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                formatSummaryLine(summary),
                                style = MonoTimecodeStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (selectedProvider == ProviderId.CLAUDE) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Paid Claude API Consent", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Anthropic Claude bills your Anthropic API account. PromptCut only calls Claude when this consent is enabled and selected manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = claudeConsent,
                            onCheckedChange = { want ->
                                if (want) showClaudeConsentDialog = true else {
                                    claudeConsent = false
                                    vm.settings.privacy.claudePaidConsentGranted = false
                                }
                            },
                            modifier = Modifier.testTag("claude_consent_switch"),
                        )
                    }
                }
            }

            // Add new key for selected provider
            HorizontalDivider()
            Text("Manage ${selectedProvider.shortName} Keys (${providerKeys.size})", style = MaterialTheme.typography.titleSmall)

            OutlinedButton(
                onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selectedProvider.keyPageUrl))) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open ${selectedProvider.shortName} Key Console") }

            OutlinedTextField(
                value = keyNameInput,
                onValueChange = { keyNameInput = it.take(48) },
                label = { Text("Key Label (optional, e.g. Personal / Backup 1)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("add_key_name_field"),
            )

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.trim(); actionFeedback = null },
                label = { Text("Add ${selectedProvider.shortName} API Key (AES-GCM Keystore)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth().testTag("api_key_field"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let {
                        keyInput = it; actionFeedback = null
                    }
                }) { Text("Paste") }

                OutlinedButton(
                    enabled = keyInput.isNotBlank() && !testingSingle,
                    modifier = Modifier.testTag("save_untested_key_button"),
                    onClick = {
                        when (val addRes = vm.settings.keyManager.addKey(
                            providerId = selectedProvider,
                            name = keyNameInput,
                            rawKey = keyInput,
                            setPreferred = providerKeys.isEmpty(),
                        )) {
                            is AppResult.Success -> {
                                keyNameInput = ""
                                keyInput = ""
                                actionFeedback = true to "Saved '${addRes.value.name}' (${addRes.value.maskedKey}) as Untested. Tap Test to verify."
                            }
                            is AppResult.Failure -> {
                                actionFeedback = false to addRes.error.message
                            }
                        }
                    },
                ) { Text("Save") }

                Button(
                    enabled = keyInput.isNotBlank() && !testingSingle,
                    modifier = Modifier.weight(1f).testTag("test_save_key_button"),
                    onClick = {
                        if (selectedProvider == ProviderId.CLAUDE && !claudeConsent) {
                            showClaudeConsentDialog = true
                            return@Button
                        }
                        testingSingle = true
                        scope.launch {
                            val addRes = vm.settings.keyManager.addKey(
                                providerId = selectedProvider,
                                name = keyNameInput,
                                rawKey = keyInput,
                                setPreferred = true,
                            )
                            when (addRes) {
                                is AppResult.Success -> {
                                    val rec = addRes.value
                                    when (val testRes = vm.router.testSavedKey(rec.id)) {
                                        is AppResult.Success -> {
                                            if (!routingAuto) vm.settings.manualProviderId = selectedProvider
                                            val dec = vm.settings.keyManager.decryptKey(rec.id).getOrNull().orEmpty()
                                            if (dec.isNotBlank()) {
                                                val modelsRes = vm.router.providerFor(selectedProvider).listModels(dec)
                                                if (modelsRes is AppResult.Success) availableModels = modelsRes.value.take(6)
                                            }
                                            keyNameInput = ""
                                            keyInput = ""
                                            actionFeedback = true to "Verified '${rec.name}' (${rec.maskedKey}) — active model: ${testRes.value}"
                                        }
                                        is AppResult.Failure -> {
                                            keyNameInput = ""
                                            keyInput = ""
                                            actionFeedback = false to "Saved '${rec.name}' (${rec.maskedKey}), verification failed: ${testRes.error.message}"
                                        }
                                    }
                                }
                                is AppResult.Failure -> {
                                    actionFeedback = false to addRes.error.message
                                }
                            }
                            testingSingle = false
                        }
                    },
                ) { Text(if (testingSingle) "Testing…" else "Test & Save") }
            }

            actionFeedback?.let { (ok, msg) ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (ok) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // Existing keys list for selected provider
            if (providerKeys.isEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "No ${selectedProvider.shortName} keys saved yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                providerKeys.forEach { rec ->
                    ProviderKeyItemCard(
                        record = rec,
                        nowMs = nowMs,
                        isTesting = testingKeyId == rec.id,
                        onTest = {
                            if (rec.providerId == ProviderId.CLAUDE && !claudeConsent) {
                                showClaudeConsentDialog = true
                            } else {
                                testingKeyId = rec.id
                                scope.launch {
                                    val r = vm.router.testSavedKey(rec.id)
                                    actionFeedback = when (r) {
                                        is AppResult.Success -> true to "${rec.name}: verified (${r.value})"
                                        is AppResult.Failure -> false to "${rec.name}: ${r.error.message}"
                                    }
                                    testingKeyId = null
                                }
                            }
                        },
                        onToggleEnabled = { enabled ->
                            vm.settings.keyManager.setEnabled(rec.id, enabled)
                        },
                        onSetPreferred = {
                            vm.settings.keyManager.setPreferred(rec.id)
                            if (!routingAuto) vm.settings.manualProviderId = rec.providerId
                            actionFeedback = true to "Set '${rec.name}' as active preferred key for ${rec.providerId.shortName}."
                        },
                        onRename = {
                            renameTarget = rec
                            renameDraft = rec.name
                        },
                        onDelete = {
                            vm.settings.keyManager.deleteKey(rec.id)
                            actionFeedback = true to "Removed '${rec.name}'."
                        },
                    )
                }
            }

            if (availableModels.isNotEmpty()) {
                Text("Select ${selectedProvider.shortName} Model", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableModels.forEach { m ->
                        val active = vm.settings.modelFor(selectedProvider) == m
                        FilterChip(
                            selected = active,
                            onClick = { vm.settings.setModelFor(selectedProvider, m) },
                            label = { Text(m, style = MonoTimecodeStyle) },
                        )
                    }
                }
            }

            // Key Health Dashboard
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Key Health Dashboard", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Auth validity reflects real API checks. Providers do not expose exact remaining free-tier quota until limits occur.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    enabled = allKeys.any { it.enabled } && testingAllProgress == null,
                    modifier = Modifier.testTag("test_all_keys_button"),
                    onClick = {
                        testingAllProgress = "Testing enabled keys…"
                        scope.launch {
                            val results = vm.router.testAllEnabledKeys(concurrency = 2) { done, total, _ ->
                                testingAllProgress = "Testing $done / $total…"
                            }
                            val okCount = results.values.count { it is AppResult.Success }
                            actionFeedback = true to "Tested ${results.size} enabled key(s): $okCount verified."
                            testingAllProgress = null
                        }
                    },
                ) {
                    Text(testingAllProgress ?: "Test All")
                }
            }

            if (allKeys.isEmpty()) {
                Text(
                    "Add an API key above to monitor key health, cooldowns, and usage timestamps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                allKeys.forEach { rec ->
                    KeyHealthDashboardRow(rec, nowMs)
                }
            }

            if (canClose) {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("start_editing_button")) {
                    Text("Return to Editor")
                }
            }

            HorizontalDivider()
            Text("Studio & Performance", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreference.entries.forEach { tp ->
                    FilterChip(
                        selected = themePref == tp,
                        onClick = {
                            themePref = tp
                            vm.settings.themePreference = tp
                            onThemeChanged(tp)
                        },
                        label = { Text(tp.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preview Proxy 720p Mode (OFF = 1080p Studio)", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Off by default for crisp 1080p preview. Enable on low-RAM phones to lower preview memory.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = preview720p, onCheckedChange = {
                    preview720p = it
                    vm.settings.preview720pMode = it
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Full Preview Shaders", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Live color grading, blur, vignette, and glow preview overlays (on by default).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = fullShaders, onCheckedChange = {
                    fullShaders = it
                    vm.settings.fullEffectsPreview = it
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prefer H.265 (HEVC) Export", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Uses HEVC hardware encoder when available, with automatic H.264 fallback.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = preferHevc, onCheckedChange = {
                    preferHevc = it
                    vm.setUseHevc(it)
                })
            }
            Text("Default Audio Bitrate", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(128, 192, 256, 320).forEach { kbps ->
                    FilterChip(
                        selected = audioKbps == kbps,
                        onClick = {
                            audioKbps = kbps
                            vm.updateAudioBitrate(kbps)
                        },
                        label = { Text("${kbps} kbps", style = MonoTimecodeStyle) },
                    )
                }
            }

            HorizontalDivider()
            Text("Privacy Controls & Disclosures", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send sampled still frames to Vision AI", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Full videos are NEVER uploaded. Disable to send text metadata only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowFrames, onCheckedChange = {
                    allowFrames = it; vm.settings.privacy.allowFrameSamplingToAi = it
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Groq Whisper for speech subtitles", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Extracts compressed audio locally for transcription when an enabled Groq key is set.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowStt, onCheckedChange = {
                    allowStt = it; vm.settings.privacy.allowAudioToGroqStt = it
                })
            }
            vm.settings.privacy.disclosures().forEach { line ->
                Text("• $line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                "Session token totals: ${vm.settings.totalPromptTokensUsed} prompt · ${vm.settings.totalCompletionTokensUsed} completion",
                style = MonoTimecodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
            }) {
                Text("Full Privacy Policy")
            }

            HorizontalDivider()
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "About PromptCut",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val appVersion = remember(ctx) {
                        runCatching {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0.0"
                    }
                    Text(
                        "PromptCut v$appVersion · Developed by Angkur",
                        style = MonoTimecodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("developer_attribution"),
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename API Key") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(48) },
                    label = { Text("Key Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = renameDraft.isNotBlank(),
                    onClick = {
                        vm.settings.keyManager.renameKey(target.id, renameDraft)
                        renameTarget = null
                    },
                ) { Text("Save Name") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showClaudeConsentDialog) {
        AlertDialog(
            onDismissRequest = { showClaudeConsentDialog = false },
            title = { Text("Enable Paid Anthropic Claude?") },
            text = {
                Text(
                    "Anthropic Claude is a paid API billed directly by Anthropic to your API key. " +
                        "PromptCut will never switch to Claude automatically and will only use it when you manually select Claude.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(onClick = {
                    claudeConsent = true
                    vm.settings.privacy.claudePaidConsentGranted = true
                    showClaudeConsentDialog = false
                }) { Text("I Understand & Consent") }
            },
            dismissButton = {
                TextButton(onClick = { showClaudeConsentDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProviderKeyItemCard(
    record: ApiKeyRecord,
    nowMs: Long,
    isTesting: Boolean,
    onTest: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSetPreferred: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val eff = record.effectiveStatus(nowMs)
    val cooldownSec = record.remainingCooldownSec(nowMs)
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("key_card_${record.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.name, fontWeight = FontWeight.SemiBold)
                        if (record.isPreferred) {
                            Text("[PREFERRED]", style = MonoTimecodeStyle)
                        }
                    }
                    Text(
                        "${record.maskedKey} · ${eff.label}" +
                            (if (cooldownSec > 0) " (${cooldownSec}s cooldown)" else "") +
                            (record.activeModel?.let { " · $it" } ?: ""),
                        style = MonoTimecodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = record.enabled, onCheckedChange = onToggleEnabled)
            }

            if (!record.lastError.isNullOrBlank()) {
                Text(
                    "Last error: ${record.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = record.enabled && !isTesting,
                    onClick = onTest,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Test key", modifier = Modifier.padding(end = 4.dp))
                    Text(
                        when {
                            isTesting -> "Testing…"
                            eff == KeyHealthStatus.UNTESTED -> "Test"
                            else -> "Retest"
                        }
                    )
                }
                if (!record.isPreferred) {
                    OutlinedButton(onClick = onSetPreferred) {
                        Icon(Icons.Outlined.Star, contentDescription = "Set preferred", modifier = Modifier.padding(end = 4.dp))
                        Text("Make Active")
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename key")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete key")
                }
            }
        }
    }
}

@Composable
private fun KeyHealthDashboardRow(record: ApiKeyRecord, nowMs: Long) {
    val eff = record.effectiveStatus(nowMs)
    val cdSec = record.remainingCooldownSec(nowMs)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${record.providerId.shortName} · ${record.name}" + if (record.isPreferred) " [PREFERRED]" else "",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(eff.label.uppercase(), style = MonoTimecodeStyle)
            }
            Text(
                "Key: ${record.maskedKey} · Cooldown: ${if (cdSec > 0) "${cdSec}s" else "None"}",
                style = MonoTimecodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lastTestText = when {
                record.lastSuccessfulTestAtMs != null -> "Verified: ${formatTimestamp(record.lastSuccessfulTestAtMs, "")}"
                record.lastTestedAtMs != null -> "Failed: ${formatTimestamp(record.lastTestedAtMs, "")}"
                else -> "Not tested yet"
            }
            Text(
                "Last test: $lastTestText · Last used: ${formatTimestamp(record.lastUsedAtMs, "Never")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record.quotaNote?.let { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            record.lastError?.let { err ->
                Text("Error: $err", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun formatSummaryLine(s: ProviderKeySummary): String {
    val parts = mutableListOf(
        "Keys: ${s.totalCount}",
        "OK: ${s.workingCount}",
        "Invalid: ${s.invalidCount}",
        "RateLim: ${s.rateLimitedCount}",
    )
    if (s.quotaExhaustedCount > 0) parts += "QuotaOut: ${s.quotaExhaustedCount}"
    parts += "Untested: ${s.untestedCount}"
    parts += "Off: ${s.disabledCount}"
    return parts.joinToString(" · ")
}

private fun formatTimestamp(ms: Long?, emptyLabel: String): String {
    if (ms == null || ms <= 0L) return emptyLabel
    return SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))
}

private fun providerTagline(pid: ProviderId): String = when (pid) {
    ProviderId.GEMINI -> "Free tier with vision + frames. Recommended for visual clip analysis."
    ProviderId.GROQ -> "Fast free open models + Whisper speech transcription."
    ProviderId.CLAUDE -> "Paid Anthropic API using your own key (requires explicit opt-in & consent)."
}
