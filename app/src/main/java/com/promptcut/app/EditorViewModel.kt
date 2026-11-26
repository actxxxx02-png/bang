package com.promptcut.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.promptcut.app.agent.AgentEvent
import com.promptcut.app.agent.AgentState
import com.promptcut.app.agent.EditingAgent
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.data.db.AppDatabase
import com.promptcut.app.data.db.ProjectEntity
import com.promptcut.app.data.db.ProjectRepository
import com.promptcut.app.data.db.RenderJobEntity
import com.promptcut.app.audio.SfxSynth
import com.promptcut.app.audio.SfxType
import com.promptcut.app.intelligence.CaptionTools
import com.promptcut.app.intelligence.SfxCue
import com.promptcut.app.intelligence.StoryAnalyzer
import com.promptcut.app.intelligence.StoryBeat
import com.promptcut.app.intelligence.VisualCache
import com.promptcut.app.render.DeviceCapabilities
import com.promptcut.app.render.ExportDiagnostics
import com.promptcut.app.render.ExportPreset
import com.promptcut.app.render.ExportSettings
import com.promptcut.app.render.RenderJobState
import com.promptcut.app.render.RenderJobStatus
import com.promptcut.app.render.RenderJobStore
import com.promptcut.app.security.KeyVault
import com.promptcut.app.timeline.AudioClipItem
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionAnimation
import com.promptcut.app.timeline.CaptionFont
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.EffectSettings
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.OverlayBounds
import com.promptcut.app.timeline.SpeedRampPoint
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val settings = Settings(app)
    val router = ProviderRouter(settings)
    val conversation = ConversationManager()
    val engine = TimelineEngine(TimelineProject.empty())
    val agent = EditingAgent(router, conversation, engine)

    private val db = AppDatabase.getInstance(app)
    private val repo = ProjectRepository(db)
    private val renderJobStore = RenderJobStore(app)

    // Reactive Timeline & Agent & Projects State
    private val _timelineProject = MutableStateFlow(engine.project)
    val timelineProject: StateFlow<TimelineProject> = _timelineProject.asStateFlow()
    val agentState: StateFlow<AgentState> = agent.state

    val activeProjects: StateFlow<List<ProjectEntity>> = repo.observeActiveProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashedProjects: StateFlow<List<ProjectEntity>> = repo.observeTrashedProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Editor Workspace & Playhead State
    var activeTab by mutableStateOf(EditorTab.STYLE)
    var panelExpanded by mutableStateOf(true)
    var showProjectsSheet by mutableStateOf(false)
    var showSettings by mutableStateOf(!settings.hasActiveKey)

    var playheadUs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var previewSpeed by mutableFloatStateOf(1.0f)
    var showSafeGuides by mutableStateOf(false)
    var compareOriginal by mutableStateOf(false)
    var audioMonitorMuted by mutableStateOf(false)
    var timelineZoom by mutableFloatStateOf(1.0f)
    var selectedVideoItemId by mutableStateOf<String?>(null)
    var selectedCaptionId by mutableStateOf<String?>(null)

    val deviceCapabilities: DeviceCapabilities by lazy { DeviceCapabilities.probe(app) }
    val missingAssetIndices = mutableStateListOf<Int>()

    // Visual strip & waveform caches
    val segmentThumbnails = mutableStateMapOf<String, List<Bitmap>>()
    var musicWaveform by mutableStateOf<FloatArray?>(null)
        private set
    val clipWaveforms = mutableStateMapOf<Int, FloatArray>()

    // Recovery & Approval Prompts
    var recoverableSession by mutableStateOf<ProjectEntity?>(null)
        private set
    var recoverableRenderJob by mutableStateOf<RenderJobEntity?>(null)
        private set
    var pendingManualEditPrompt by mutableStateOf<String?>(null)
        private set
    var pendingManualEditReason by mutableStateOf<String?>(null)
        private set
    var pendingPaidConsentError by mutableStateOf(false)
        private set

    // Footage
    val clips = mutableStateListOf<Clip>()
    val insights = mutableStateMapOf<Int, ClipInsight>()
    val clipCuts = mutableStateMapOf<Int, List<Span>>()
    var analyzing by mutableStateOf(false); private set
    var suggestions by mutableStateOf<Suggestions?>(null); private set
    var storyBeats by mutableStateOf<List<StoryBeat>>(emptyList()); private set
    var sfxCues by mutableStateOf<List<SfxCue>>(emptyList()); private set

    // Style & Export choices
    var occasion by mutableStateOf("other")
    var mood by mutableStateOf(Mood.HAPPY)
    var lengthSec by mutableIntStateOf(0)
    var aspect by mutableStateOf(Aspect.PORTRAIT)
    var color by mutableStateOf(ColorLook.NATURAL)
    var musicSource by mutableStateOf(MusicSource.LIBRARY)
    var transition by mutableStateOf(Transition.AUTO)
    var speedMode by mutableStateOf(SpeedMode.NATURAL)
    var soundMode by mutableStateOf(SoundMode.VOICES)
    var useAllClips by mutableStateOf(true)
    var orderMode by mutableStateOf(OrderMode.MIX)
    val off = mutableStateListOf<Opt>()

    var exportSettings by mutableStateOf(
        ExportSettings(
            resolution = settings.preferredResolution,
            fps = settings.preferredFps,
            videoBitrateBps = settings.preferredBitrateMbps * 1_000_000,
            audioBitrateBps = settings.preferredAudioBitrateKbps * 1000,
            useHevc = settings.preferredCodecHevc,
            forceSafeMode = settings.safeRender,
        )
    )
    var exportDiagnostics by mutableStateOf<ExportDiagnostics?>(null)
        private set

    fun isOn(o: Opt) = o !in off
    fun unset(o: Opt) {
        if (o !in off) off += o
        if (o == Opt.LENGTH) lengthSec = 0
        if (o == Opt.USE_ALL) useAllClips = false
        selectedPreset = null
    }
    fun turnOn(o: Opt) { off -= o }
    fun clearAll() { off.clear(); off += Opt.entries; lengthSec = 0; useAllClips = false; selectedPreset = null }
    fun useDefaults() { off.clear(); useAllClips = true }
    val nothingSelected get() = off.size == Opt.entries.size
    var askedLengthSec by mutableStateOf<Double?>(null); private set
    var song by mutableStateOf<Song?>(null); private set

    // Free song library
    val libResults = mutableStateListOf<LibraryTrack>()
    var libQuery by mutableStateOf("")
    var libSearching by mutableStateOf(false); private set
    var libSong by mutableStateOf<Song?>(null); private set
    var libLoadingId by mutableStateOf<String?>(null); private set
    var libError by mutableStateOf<String?>(null); private set
    var selectedPreset by mutableStateOf<Preset?>(null); private set

    // Chat, Plan, Render, QA
    val chat = mutableStateListOf<ChatMessage>()
    var plan by mutableStateOf<EditPlan?>(null); private set
    var output by mutableStateOf<File?>(null); private set
    var qa by mutableStateOf<QaReport?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var progress by mutableIntStateOf(-1); private set
    var busy by mutableStateOf(false); private set

    private var job: Job? = null
    private var analysisJob: Job? = null
    private var cachedFrames: List<Frame>? = null
    private var windows: Map<Int, List<Span>>? = null

    val totalFootage: Double get() = clips.sumOf { it.meta.durationSec }
    val canUndo: Boolean get() = engine.canUndo
    val canRedo: Boolean get() = engine.canRedo
    val undoLabel: String? get() = engine.undoLabel
    val redoLabel: String? get() = engine.redoLabel

    init {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { (busy || analyzing) to (status to progress) }.collect { (working, sp) ->
                if (working) {
                    WorkService.start(ctx, sp.first ?: "Working…")
                    WorkService.update(ctx, sp.first ?: "Working…", sp.second)
                } else WorkService.stop(ctx)
            }
        }
        viewModelScope.launch {
            renderJobStore.markInterruptedIfRunning()
            recoverableSession = repo.findInterruptedSession()
            recoverableRenderJob = repo.findInterruptedRenderJob()
        }
    }

    override fun onCleared() {
        WorkService.stop(ctx)
        VisualCache.clearMemory()
        super.onCleared()
    }

    private fun syncFromEngine(autosave: Boolean = true) {
        val p = engine.project
        _timelineProject.value = p
        plan = if (p.mainVideoTrack.items.isNotEmpty()) p.toEditPlan() else null
        aspect = p.aspect
        color = p.colorLook
        mood = p.mood
        sfxCues = StoryAnalyzer.suggestSfxCues(p, insights)
        if (selectedVideoItemId != null && p.mainVideoTrack.items.none { it.id == selectedVideoItemId }) {
            selectedVideoItemId = p.mainVideoTrack.items.firstOrNull()?.id
        }
        if (p.totalDurationUs > 0 && playheadUs > p.totalDurationUs) {
            playheadUs = p.totalDurationUs
        }
        conversation.selectedSegmentId = selectedVideoItemId
        conversation.selectedClipIndices = selectedVideoItem()?.sourceClipIndex?.let { setOf(it) } ?: emptySet()
        refreshTimelineVisuals(p)
        if (autosave) triggerAutosave()
    }

    fun selectedVideoItem(): VideoClipItem? =
        _timelineProject.value.mainVideoTrack.items.firstOrNull { it.id == selectedVideoItemId }

    fun selectedCaption(): CaptionItem? =
        _timelineProject.value.mainCaptionTrack.items.firstOrNull { it.id == selectedCaptionId }

    private fun refreshTimelineVisuals(project: TimelineProject) {
        val byIndex = clips.associateBy { it.meta.index }
        project.mainVideoTrack.items.forEach { item ->
            if (!segmentThumbnails.containsKey(item.id)) {
                val clip = byIndex[item.sourceClipIndex] ?: return@forEach
                viewModelScope.launch {
                    val strip = VisualCache.getThumbnailStrip(
                        ctx,
                        clip.uri,
                        clip.meta.index,
                        usToSec(item.sourceStartUs),
                        usToSec(item.sourceEndUs),
                        count = 4,
                    )
                    if (strip.isNotEmpty()) segmentThumbnails[item.id] = strip
                }
            }
        }
    }

    private fun triggerAutosave() {
        val current = _timelineProject.value
        if (clips.isEmpty() && current.mainVideoTrack.items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val thumbPath = clips.firstOrNull()?.thumbnail?.let { bmp ->
                    repo.saveProjectThumbnail(ctx, current.id, bmp)
                }
                repo.autosaveProject(
                    project = current,
                    clips = clips.map { it.meta },
                    clipUris = clips.associate { it.meta.index to it.uri.toString() },
                    clipHdr = clips.associate { it.meta.index to it.isHdr },
                    clipCuts = clipCuts.toMap(),
                    conversation = conversation,
                    lastOutputPath = output?.absolutePath,
                    thumbnailPath = thumbPath,
                    lastExportDiagnosticsJson = exportDiagnostics?.toJson(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ Timeline Commands & Undo/Redo

    fun undo() {
        if (engine.undo().isSuccess) {
            syncFromEngine()
            status = "Undid edit"
        }
    }

    fun redo() {
        if (engine.redo().isSuccess) {
            syncFromEngine()
            status = "Redid edit"
        }
    }

    fun splitSelectedAtPlayhead() {
        val item = selectedVideoItem() ?: _timelineProject.value.mainVideoTrack.items.firstOrNull {
            playheadUs in it.timelineStartUs..it.timelineEndUs
        } ?: return
        when (val r = engine.execute(TimelineCommand.SplitVideoClip(item.id, playheadUs))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun trimSelected(startSec: Double, endSec: Double) {
        val item = selectedVideoItem() ?: return
        val maxUs = clips.firstOrNull { it.meta.index == item.sourceClipIndex }?.meta?.durationSec?.let(::secToUs) ?: Long.MAX_VALUE
        when (val r = engine.execute(TimelineCommand.TrimVideoClip(item.id, secToUs(startSec), secToUs(endSec), maxUs))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun trimVideoItemEdges(itemId: String, newSourceStartUs: Long, newSourceEndUs: Long) {
        val item = _timelineProject.value.mainVideoTrack.items.firstOrNull { it.id == itemId } ?: return
        val maxUs = clips.firstOrNull { it.meta.index == item.sourceClipIndex }?.meta?.durationSec?.let(::secToUs) ?: Long.MAX_VALUE
        when (val r = engine.execute(TimelineCommand.TrimVideoClip(item.id, newSourceStartUs, newSourceEndUs, maxUs))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun deleteSelectedSegment() {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.DeleteVideoClip(item.id))) {
            is AppResult.Success -> {
                selectedVideoItemId = null
                syncFromEngine()
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun duplicateSelectedSegment() {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.DuplicateVideoClip(item.id))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun moveSelectedSegment(delta: Int) {
        val items = _timelineProject.value.mainVideoTrack.items
        val idx = items.indexOfFirst { it.id == selectedVideoItemId }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, items.lastIndex)
        if (target == idx) return
        when (val r = engine.execute(TimelineCommand.ReorderVideoClips(idx, target))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setSelectedSpeed(speed: Double) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.SetClipSpeed(item.id, speed))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setSelectedSpeedRamp(points: List<SpeedRampPoint>) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.SetClipSpeedRamp(item.id, points))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setSelectedAudio(volume: Float, muted: Boolean) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.SetClipVolume(item.id, volume, muted))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setSelectedTransition(t: Transition) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.SetClipTransition(item.id, t))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setSelectedEffects(effects: EffectSettings) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.SetClipEffects(item.id, effects))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun addOrUpdateSelectedKeyframe(keyframe: Keyframe) {
        val item = selectedVideoItem() ?: return
        when (val r = engine.execute(TimelineCommand.UpsertKeyframe(item.id, keyframe))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun addOverlayClipFromClip(
        clipIndex: Int,
        startSec: Double = usToSec(playheadUs),
        endSec: Double? = null,
        bounds: OverlayBounds = OverlayBounds(),
    ) {
        val c = clips.firstOrNull { it.meta.index == clipIndex } ?: return
        val srcEnd = endSec?.let(::secToUs)?.coerceAtMost(secToUs(c.meta.durationSec))
            ?: minOf(secToUs(c.meta.durationSec), 4_000_000L)
        if (srcEnd < TimelineEngine.MIN_CLIP_US) return
        val ovItem = VideoClipItem(
            sourceClipIndex = c.meta.index,
            sourceUri = c.uri.toString(),
            timelineStartUs = secToUs(startSec),
            sourceStartUs = 0L,
            sourceEndUs = srcEnd,
            muted = true,
            overlayBounds = bounds,
            why = "V2 PIP #${c.meta.index + 1}",
            editedByUser = true,
        )
        when (val r = engine.execute(TimelineCommand.AddVideoClip(ovItem, trackId = "video_overlay"))) {
            is AppResult.Success -> {
                syncFromEngine()
                status = "Added Clip #${c.meta.index + 1} to V2 Overlay."
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun updateOverlayBounds(itemId: String, bounds: OverlayBounds) {
        when (val r = engine.execute(TimelineCommand.SetOverlayBounds(itemId, bounds, trackId = "video_overlay"))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun removeOverlayItem(itemId: String) {
        when (val r = engine.execute(TimelineCommand.DeleteVideoClip(itemId, trackId = "video_overlay"))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun toggleTrackLock(trackId: String) {
        if (engine.execute(TimelineCommand.ToggleTrackLock(trackId)).isSuccess) syncFromEngine()
    }

    fun toggleTrackVisibility(trackId: String) {
        if (engine.execute(TimelineCommand.ToggleTrackVisibility(trackId)).isSuccess) syncFromEngine()
    }

    fun trimSelectedSilence() {
        val item = selectedVideoItem() ?: return
        val clip = clips.firstOrNull { it.meta.index == item.sourceClipIndex } ?: return
        val spans = insights[item.sourceClipIndex]?.silenceSpans.orEmpty()
        if (spans.isEmpty()) {
            status = "No detected silent sections in Clip #${item.sourceClipIndex + 1}."
            return
        }
        var newStartUs = item.sourceStartUs
        var newEndUs = item.sourceEndUs
        for (sp in spans) {
            val spStartUs = secToUs(sp.start)
            val spEndUs = secToUs(sp.end)
            if (spStartUs <= newStartUs + 150_000L && spEndUs > newStartUs && spEndUs < newEndUs - TimelineEngine.MIN_CLIP_US) {
                newStartUs = spEndUs
            } else if (spEndUs >= newEndUs - 150_000L && spStartUs < newEndUs && spStartUs > newStartUs + TimelineEngine.MIN_CLIP_US) {
                newEndUs = spStartUs
            }
        }
        val maxUs = secToUs(clip.meta.durationSec)
        if (newStartUs == item.sourceStartUs && newEndUs == item.sourceEndUs) {
            status = "No leading/trailing silence to trim on this segment."
            return
        }
        when (val r = engine.execute(TimelineCommand.TrimVideoClip(item.id, newStartUs, newEndUs, maxUs))) {
            is AppResult.Success -> {
                syncFromEngine()
                status = "Trimmed silent edges on Clip #${item.sourceClipIndex + 1}."
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun detectBeatMarkersForProject() {
        val musicUri = when (musicSource) {
            MusicSource.MY_SONG -> song?.uri
            MusicSource.LIBRARY -> libSong?.uri
            else -> _timelineProject.value.mainAudioTrack.items.firstOrNull()?.sourceUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                ?: clips.firstOrNull { it.meta.hasAudio }?.uri
        }
        if (musicUri == null) {
            status = "Select a song or clip with audio first to detect beats."
            return
        }
        viewModelScope.launch {
            status = "Detecting musical beats…"
            val beats = withContext(Dispatchers.Default) { AudioTools.detectBeats(ctx, musicUri) }
            if (beats.isEmpty()) {
                status = "No clear beats detected."
                return@launch
            }
            engine.execute(TimelineCommand.SetBeatMarkers(beats))
            syncFromEngine()
            status = "Detected ${beats.size} beat markers."
        }
    }

    fun snapCutsToNearestBeat() {
        val beats = _timelineProject.value.beatMarkersUs
        if (beats.isEmpty()) {
            status = "Detect beats first before snapping cuts."
            return
        }
        val items = _timelineProject.value.mainVideoTrack.items
        if (items.size <= 1) return
        val maxDurMap = clips.associate { it.meta.index to secToUs(it.meta.durationSec) }
        val cmds = mutableListOf<TimelineCommand>()
        items.forEach { item ->
            val targetEnd = beats.minByOrNull { kotlin.math.abs(it - item.timelineEndUs) } ?: return@forEach
            val deltaOut = targetEnd - item.timelineEndUs
            if (kotlin.math.abs(deltaOut) in 40_000L..450_000L) {
                val maxSrc = maxDurMap[item.sourceClipIndex] ?: Long.MAX_VALUE
                val candidateEnd = (item.sourceEndUs + (deltaOut * item.effectiveSpeed).toLong())
                    .coerceIn(item.sourceStartUs + TimelineEngine.MIN_CLIP_US, maxSrc)
                if (candidateEnd != item.sourceEndUs) {
                    cmds += TimelineCommand.TrimVideoClip(item.id, item.sourceStartUs, candidateEnd, maxSrc)
                }
            }
        }
        if (cmds.isEmpty()) {
            status = "Cuts are already aligned with beat markers."
            return
        }
        when (val r = engine.execute(TimelineCommand.Batch(cmds, "Snap cuts to beat"))) {
            is AppResult.Success -> {
                syncFromEngine()
                status = "Snapped ${cmds.size} cut(s) to beats."
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun insertSfxAtPlayhead(type: SfxType) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SfxSynth.ensureCachedWav(ctx, type) }
            withContext(Dispatchers.Main) {
                val sfxItem = AudioClipItem(
                    title = type.label,
                    sourceUri = "sfx://${type.key}",
                    trackType = AudioTrackType.SFX,
                    timelineStartUs = playheadUs,
                    durationUs = secToUs(type.durationSec),
                    volume = 0.85f,
                    fadeInUs = 10_000L,
                    fadeOutUs = 50_000L,
                    duckUnderSpeech = false,
                    editedByUser = true,
                )
                when (val r = engine.execute(TimelineCommand.AddAudioItem(sfxItem, trackId = "audio_sfx"))) {
                    is AppResult.Success -> {
                        syncFromEngine()
                        status = "Inserted ${type.label} SFX."
                    }
                    is AppResult.Failure -> status = r.error.message
                }
            }
        }
    }

    fun setAudioTrackMix(
        trackId: String,
        volume: Float,
        muted: Boolean,
        voiceEnhance: Boolean? = null,
        normalizeTargetDb: Float? = null,
    ) {
        when (val r = engine.execute(TimelineCommand.SetAudioTrackMix(trackId, volume, muted, voiceEnhance, normalizeTargetDb))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun setProjectLook(look: ColorLook) {
        color = look
        if (_timelineProject.value.mainVideoTrack.items.isNotEmpty()) {
            engine.execute(TimelineCommand.SetProjectStyle(colorLook = look))
            syncFromEngine()
        }
    }

    fun setProjectAspect(newAspect: Aspect) {
        aspect = newAspect
        if (_timelineProject.value.mainVideoTrack.items.isNotEmpty()) {
            engine.execute(TimelineCommand.SetProjectStyle(aspect = newAspect))
            syncFromEngine()
        }
    }

    fun updateCustomAspect(width: Int, height: Int) {
        val w = width.coerceIn(240, 4096)
        val h = height.coerceIn(240, 4096)
        aspect = Aspect.CUSTOM
        exportSettings = exportSettings.copy(customAspectWidth = w, customAspectHeight = h)
        engine.execute(TimelineCommand.SetProjectStyle(aspect = Aspect.CUSTOM, customAspectWidth = w, customAspectHeight = h))
        syncFromEngine()
    }

    fun setProjectTitle(title: String?) {
        val clean = title?.trim()?.ifBlank { null }
        engine.execute(TimelineCommand.SetProjectStyle(titleOverlay = clean, clearTitle = clean == null))
        syncFromEngine()
    }

    fun generateCaptionsFromSpeech(style: CaptionStyle = CaptionStyle.SUBTITLE_BAR) {
        val raw = StoryAnalyzer.generateCaptionsForTimeline(_timelineProject.value, insights, style)
        if (raw.isEmpty()) {
            status = "No speech found yet. Configure Groq for accurate speech-to-text or add captions manually."
            return
        }
        val enriched = raw.map { CaptionTools.enrichCaption(it, applyPunctuation = true, highlightKeywords = true) }
        when (val r = engine.execute(TimelineCommand.ReplaceCaptions(enriched))) {
            is AppResult.Success -> {
                syncFromEngine()
                status = "Added ${enriched.size} synchronized caption(s)."
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun applyCaptionFormattingToAll(
        style: CaptionStyle,
        font: CaptionFont,
        fontSizeSp: Int,
        positionYFraction: Float,
        animation: CaptionAnimation,
        applyPunctuation: Boolean,
        highlightKeywords: Boolean,
    ) {
        val current = _timelineProject.value.mainCaptionTrack.items
        if (current.isEmpty()) return
        val updated = current.map { cap ->
            CaptionTools.enrichCaption(
                cap.copy(
                    style = style,
                    font = font,
                    fontSizeSp = fontSizeSp.coerceIn(14, 34),
                    positionYFraction = CaptionTools.clampSafeAreaY(positionYFraction),
                    animation = animation,
                    editedByUser = true,
                ),
                applyPunctuation = applyPunctuation,
                highlightKeywords = highlightKeywords,
            )
        }
        when (val r = engine.execute(TimelineCommand.ReplaceCaptions(updated))) {
            is AppResult.Success -> syncFromEngine()
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun exportCaptionsText(asVtt: Boolean): String {
        val items = _timelineProject.value.mainCaptionTrack.items
        return if (asVtt) CaptionTools.exportVtt(items) else CaptionTools.exportSrt(items)
    }

    fun upsertCaption(caption: CaptionItem) {
        val enriched = CaptionTools.enrichCaption(caption, applyPunctuation = false, highlightKeywords = caption.highlightKeywords.isNotEmpty())
        when (val r = engine.execute(TimelineCommand.AddOrUpdateCaption(enriched))) {
            is AppResult.Success -> {
                selectedCaptionId = enriched.id
                syncFromEngine()
            }
            is AppResult.Failure -> status = r.error.message
        }
    }

    fun deleteCaption(captionId: String) {
        if (engine.execute(TimelineCommand.DeleteCaption(captionId)).isSuccess) {
            if (selectedCaptionId == captionId) selectedCaptionId = null
            syncFromEngine()
        }
    }

    fun editHistoryEntries(): List<String> = engine.historyEntries()

    // ------------------------------------------------------------------ Clips & Understanding

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            status = "Reading clips…"
            uris.forEach { uri ->
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val next = (clips.maxOfOrNull { it.meta.index } ?: -1) + 1
            val loaded = ClipAnalyzer.load(ctx, uris, next)
            clips += loaded
            cachedFrames = null

            // Append newly added clips to timeline immediately so user can scrub & inspect even before AI run!
            val cmds = loaded.map { c ->
                TimelineCommand.AddVideoClip(
                    VideoClipItem(
                        sourceClipIndex = c.meta.index,
                        sourceUri = c.uri.toString(),
                        timelineStartUs = 0L,
                        sourceStartUs = 0L,
                        sourceEndUs = secToUs(c.meta.durationSec),
                        muted = !c.meta.hasAudio,
                        why = c.meta.name,
                        editedByUser = false,
                    ),
                    byUser = false,
                )
            }
            if (cmds.isNotEmpty()) {
                engine.execute(TimelineCommand.Batch(cmds, "Add media", byUser = false))
                if (selectedVideoItemId == null) selectedVideoItemId = engine.project.mainVideoTrack.items.firstOrNull()?.id
                syncFromEngine()
            }

            loaded.forEach { c ->
                if (c.meta.hasAudio) {
                    viewModelScope.launch {
                        clipWaveforms[c.meta.index] = VisualCache.getWaveform(ctx, c.uri, "clip_${c.meta.index}")
                    }
                }
            }

            status = if (loaded.size < uris.size) "Some files couldn't be read and were skipped." else null
            understand()
        }
    }

    fun removeClip(clip: Clip) {
        clips.remove(clip)
        insights.remove(clip.meta.index)
        clipCuts.remove(clip.meta.index)
        clipWaveforms.remove(clip.meta.index)
        cachedFrames = null
        val toRemove = _timelineProject.value.mainVideoTrack.items.filter { it.sourceClipIndex == clip.meta.index }
            .map { TimelineCommand.DeleteVideoClip(it.id, byUser = false) }
        if (toRemove.isNotEmpty()) {
            engine.execute(TimelineCommand.Batch(toRemove, "Remove source clip", byUser = false))
            syncFromEngine()
        }
        output = null
        understand()
    }

    fun startOver() {
        job?.cancel(); analysisJob?.cancel()
        clips.clear(); insights.clear(); chat.clear(); clipCuts.clear(); askedLengthSec = null
        segmentThumbnails.clear(); clipWaveforms.clear(); musicWaveform = null
        plan = null; output = null; qa = null; status = null; busy = false; analyzing = false
        suggestions = null; selectedPreset = null; cachedFrames = null; windows = null
        storyBeats = emptyList(); sfxCues = emptyList()
        conversation.clear()
        engine.reset(TimelineProject.empty())
        _timelineProject.value = engine.project
        playheadUs = 0L; selectedVideoItemId = null; selectedCaptionId = null
        agent.reset()
    }

    private fun understand() {
        analysisJob?.cancel()
        windows = null
        if (clips.isEmpty()) { suggestions = null; return }
        analysisJob = viewModelScope.launch {
            analyzing = true
            try {
                val hasGroq = settings.keyManager.hasEligibleKey(ProviderId.GROQ)
                val stt: (suspend (File) -> List<Span>)? =
                    if (hasGroq && settings.privacy.allowAudioToGroqStt) {
                        { f ->
                            when (val r = router.transcribeAudioWithGroq(f)) {
                                is AppResult.Success -> r.value
                                is AppResult.Failure -> throw ApiException(r.error.message)
                            }
                        }
                    } else null
                for (i in clips.indices) {
                    val clip = clips.getOrNull(i) ?: break
                    if (insights.containsKey(clip.meta.index)) continue
                    val ins = runCatching {
                        ClipUnderstanding.analyze(ctx, clip, stt) { step -> status = step }
                    }.getOrElse { if (it is CancellationException) throw it; ClipInsight(clip.meta.index) }
                    insights[clip.meta.index] = ins
                    val idx = clips.indexOfFirst { it.meta.index == clip.meta.index }
                    if (idx >= 0) clips[idx] = Clip(clip.uri, clip.meta.copy(description = ins.describe(clip.meta)), clip.thumbnail, clip.isHdr)
                }
                storyBeats = StoryAnalyzer.analyzeStoryArc(clips.map { it.meta }, insights)
                sfxCues = StoryAnalyzer.suggestSfxCues(_timelineProject.value, insights)
                status = "Thinking about styles…"
                suggestStyles()
                status = null
                triggerAutosave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = KeyVault.redactSecrets(e.message ?: "unknown error", settings.allConfiguredKeys())
                status = "Couldn't get style ideas: $safeMsg. You can still edit directly."
            } finally {
                analyzing = false
            }
        }
    }

    private suspend fun suggestStyles() {
        val decision = when (val d = router.chooseProvider(needsVision = true)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> return
        }
        val pid = decision.providerId
        val frames = framesFor(pid)
        val metas = if (totalFootage > 300) clips.map { c ->
            c.meta.copy(description = insights[c.meta.index]?.describe(c.meta, compact = true) ?: c.meta.description)
        } else clips.map { it.meta }
        val req = com.promptcut.app.ai.AiRequest(
            systemPrompt = PlanCodec.SUGGEST_SYSTEM,
            userPrompt = PlanCodec.buildSuggestPrompt(metas, frames.isNotEmpty()),
            frames = frames,
            jsonMode = true,
            temperature = 0.4,
        )
        val res = router.complete(req, needsVision = frames.isNotEmpty())
        if (res is AppResult.Success) {
            val s = runCatching { PlanCodec.parseSuggestions(res.value.text) }.getOrNull() ?: return
            suggestions = s
            if (!busy && selectedPreset == null && s.occasion in OCCASIONS) occasion = s.occasion
        }
    }

    fun applyPreset(p: Preset) {
        off -= listOf(Opt.MOOD, Opt.FORMAT, Opt.LOOK, Opt.LENGTH)
        selectedPreset = p
        mood = p.mood; aspect = p.aspect; color = p.color
        lengthSec = LENGTHS.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - p.lengthSec) }?.takeIf { p.lengthSec < totalFootage + 1 } ?: 0
    }

    fun clearPreset() { selectedPreset = null }

    private suspend fun framesFor(providerId: ProviderId): List<Frame> {
        val canSee = router.providerFor(providerId).capabilities.supportsVision
        if (!canSee || !settings.privacy.allowFrameSamplingToAi) return emptyList()
        return cachedFrames ?: ClipAnalyzer.sampleFrames(ctx, clips).also { cachedFrames = it }
    }

    // ------------------------------------------------------------------ Per-clip edits & music

    fun setCrop(clipIndex: Int, crop: CropBox?) {
        val i = clips.indexOfFirst { it.meta.index == clipIndex }
        if (i < 0) return
        val c = clips[i]
        val norm = crop?.normalized()
        clips[i] = Clip(c.uri, c.meta.copy(crop = norm), c.thumbnail, c.isHdr)
        val cmds = _timelineProject.value.mainVideoTrack.items.filter { it.sourceClipIndex == clipIndex }
            .map { TimelineCommand.SetClipCrop(it.id, norm) }
        if (cmds.isNotEmpty()) {
            engine.execute(TimelineCommand.Batch(cmds, "Crop clip", byUser = true))
            syncFromEngine()
        }
    }

    fun setCuts(clipIndex: Int, cuts: List<Span>) {
        if (cuts.isEmpty()) clipCuts.remove(clipIndex) else clipCuts[clipIndex] = TimeCuts.merge(cuts)
        triggerAutosave()
    }

    fun pickSong(uri: Uri) {
        viewModelScope.launch {
            status = "Listening to your song…"
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = EditorMediaHelper.displayName(ctx, uri)
            val s = EditorMediaHelper.analyzeSong(ctx, uri, name)
            if (s == null) { status = "Couldn't read that audio file. Try another song."; return@launch }
            song = s
            musicSource = MusicSource.MY_SONG
            musicWaveform = s.envelope?.let { VisualCache.downsample(it, 80) }
            attachSongToTimeline(s)
            status = if (s.tempo != null) "Song ready: ${name.substringBeforeLast('.')} (${s.tempo.bpm.toInt()} BPM)." else "Song ready."
        }
    }

    fun searchLibrary(query: String? = null) {
        val q = (query ?: libQuery).ifBlank { MusicLibrary.defaultQuery(mood, occasion) }
        libQuery = q
        viewModelScope.launch {
            libSearching = true; libError = null
            try {
                val minSec = maxOf(20.0, usToSec(_timelineProject.value.totalDurationUs))
                var found = MusicLibrary.search(q, minSec)
                if (found.isEmpty()) {
                    val moodQ = MusicLibrary.defaultQuery(mood, occasion)
                    found = runCatching { MusicLibrary.search(moodQ, minSec) }.getOrDefault(emptyList())
                    libError = "\"$q\" wasn't in the free library. Showing ${mood.label.lowercase()} songs instead."
                }
                libResults.clear(); libResults += found
            } catch (e: Exception) {
                libError = KeyVault.redactSecrets(e.message ?: "Music search failed.", settings.allConfiguredKeys())
            } finally {
                libSearching = false
            }
        }
    }

    private suspend fun loadLibraryTrack(t: LibraryTrack): Song {
        libLoadingId = t.id
        try {
            val f = MusicLibrary.download(ctx, t)
            return EditorMediaHelper.analyzeSong(ctx, Uri.fromFile(f), "${t.title} – ${t.artist}", if (t.needsCredit) t.credit else null)
                ?: throw ApiException("Couldn't read that song. Pick another one.")
        } finally {
            libLoadingId = null
        }
    }

    fun useLibraryTrack(t: LibraryTrack) {
        viewModelScope.launch {
            try {
                val s = loadLibraryTrack(t)
                libSong = s
                musicSource = MusicSource.LIBRARY
                musicWaveform = s.envelope?.let { VisualCache.downsample(it, 80) }
                attachSongToTimeline(s)
                libError = null
            } catch (e: Exception) {
                libError = KeyVault.redactSecrets(e.message ?: "Couldn't load track.", settings.allConfiguredKeys())
            }
        }
    }

    private fun attachSongToTimeline(s: Song) {
        val item = AudioClipItem(
            title = s.name.substringBeforeLast('.'),
            sourceUri = s.uri.toString(),
            trackType = AudioTrackType.MUSIC,
            timelineStartUs = 0L,
            durationUs = secToUs(s.durationSec),
            volume = 0.8f,
            bpm = s.tempo?.bpm,
            credit = s.credit,
            editedByUser = true,
        )
        engine.execute(TimelineCommand.AddAudioItem(item, byUser = true))
        syncFromEngine()
    }

    private suspend fun autoPickSong(p: EditPlan): Song? {
        val q = p.musicQuery.ifBlank { libQuery.ifBlank { MusicLibrary.defaultQuery(p.mood, occasion) } }
        status = "Finding a free ${p.mood.label.lowercase()} song…"
        val found = runCatching { MusicLibrary.search(q, p.totalSec + 2) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { MusicLibrary.search(MusicLibrary.defaultQuery(p.mood, occasion), p.totalSec + 2) }.getOrNull().orEmpty() }
        if (found.isNotEmpty()) { libResults.clear(); libResults += found; libQuery = q }
        for (t in found.take(3)) {
            val s = runCatching { loadLibraryTrack(t) }.getOrNull()
            if (s != null) {
                musicWaveform = s.envelope?.let { VisualCache.downsample(it, 80) }
                return s
            }
        }
        return null
    }

    // ------------------------------------------------------------------ AI Agent Editing + Render

    private fun styleRequest(extra: String): String = buildString {
        append("Make a video. ")
        if (off.isNotEmpty()) append("Anything not stated here: decide yourself from the user's words and the footage. ")
        if (isOn(Opt.OCCASION)) append("Occasion: $occasion. ")
        if (isOn(Opt.MOOD)) append("Mood: ${mood.key}. ")
        if (isOn(Opt.LENGTH)) append(if (lengthSec > 0) "Target length: about $lengthSec s. " else if (totalFootage > 300) "Length: highlight 1-3 min. " else "Length: 15-40 s. ")
        if (isOn(Opt.FORMAT)) append("Format: ${aspect.key}. ")
        if (isOn(Opt.LOOK)) append("Colour look: ${color.key}. ")
        if (isOn(Opt.TRANSITION)) append("Transition: ${transition.key}. ")
        if (isOn(Opt.SPEED)) append("Speed mode: ${speedMode.label}. ")
        if (useAllClips && isOn(Opt.USE_ALL)) append("Use EVERY clip at least once (${clips.size} clips). ")
        if (isOn(Opt.ORDER)) append("Order: ${orderMode.label}. ")
        if (isOn(Opt.MUSIC)) append("Music: ${musicSource.label}. ")
        selectedPreset?.let { append("Style: ${it.name} - ${it.description}. ${it.instructions}. ") }
        if (extra.isNotBlank()) append("User instructions: $extra")
    }.let { if (nothingSelected && extra.isNotBlank()) extra else it }

    fun setLength(sec: Int) { lengthSec = sec; askedLengthSec = null }

    fun create(extra: String = "") {
        if (clips.isEmpty() || busy) return
        val label = if (nothingSelected && extra.isNotBlank()) extra else listOfNotNull(
            selectedPreset?.name ?: mood.label,
            if (lengthSec > 0 && isOn(Opt.LENGTH)) "${lengthSec}s" else null,
            if (isOn(Opt.FORMAT)) aspect.key else null,
            extra.ifBlank { null },
        ).joinToString(" · ")
        chat += ChatMessage(true, label)
        conversation.projectInstructions = styleRequest(extra)
        runAgentAndRender(styleRequest(extra), fresh = true, extraText = extra, allowOverwriteManual = false)
    }

    fun send(request: String) {
        val text = request.trim()
        if (text.isEmpty() || busy) return
        if (clips.isEmpty()) { chat += ChatMessage(false, "Add some video clips first.", true); return }
        chat += ChatMessage(true, text)
        val fresh = _timelineProject.value.mainVideoTrack.items.isEmpty()
        runAgentAndRender(if (fresh) styleRequest(text) else text, fresh = fresh, extraText = text, allowOverwriteManual = false)
    }

    fun approveManualEditOverwrite() {
        val p = pendingManualEditPrompt ?: return
        pendingManualEditPrompt = null
        pendingManualEditReason = null
        runAgentAndRender(p, fresh = false, extraText = p, allowOverwriteManual = true)
    }

    fun dismissManualEditApproval() {
        pendingManualEditPrompt = null
        pendingManualEditReason = null
        agent.reset()
        status = "Kept your manual edits untouched."
    }

    fun dismissPaidConsentBanner() { pendingPaidConsentError = false }

    private fun runAgentAndRender(request: String, fresh: Boolean, extraText: String, allowOverwriteManual: Boolean) {
        if (!settings.hasActiveKey) { showSettings = true; return }
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            pendingPaidConsentError = false
            try {
                analysisJob?.let { if (it.isActive) { status = "Finishing watching your clips…"; it.join() } }

                val userText = if (fresh) extraText else request
                val durations = clips.associate { it.meta.index to it.meta.durationSec }
                val parsed = TimeCuts.parse(userText, durations)
                parsed.cuts.forEach { (c, l) -> setCuts(c, clipCuts[c].orEmpty() + l) }
                if (parsed.understood.isNotEmpty()) chat += ChatMessage(false, "✂️ Applied exact cuts: " + parsed.understood.joinToString("; ") + ".")
                TimeCuts.targetLength(userText)?.let { askedLengthSec = it }

                val target = askedLengthSec ?: lengthSec.takeIf { it > 0 && isOn(Opt.LENGTH) }?.toDouble()
                val order = if (isOn(Opt.ORDER)) orderMode else OrderMode.AI
                val decision = when (val d = router.chooseProvider(needsVision = true)) {
                    is AppResult.Success -> d.value
                    is AppResult.Failure -> {
                        if (d.error is AppError.PaidConsentRequired) pendingPaidConsentError = true
                        chat += ChatMessage(false, d.error.message, true)
                        return@launch
                    }
                }
                val frames = framesFor(decision.providerId)
                val intents = QuickIntents.parse(userText)
                val useAll = useAllClips && (isOn(Opt.USE_ALL) || intents.useAll == true)

                var approvalRequired = false
                var failed = false
                agent.executeInstruction(
                    prompt = request,
                    clips = clips.map { it.meta },
                    insights = insights.toMap(),
                    clipUris = clips.associate { it.meta.index to it.uri.toString() },
                    frames = frames,
                    freshEdit = fresh,
                    allowOverwriteManualEdits = allowOverwriteManual,
                    postProcessPlan = { rawPlan ->
                        var p = rawPlan
                        if (fresh) {
                            p = p.copy(
                                aspect = if (isOn(Opt.FORMAT)) aspect else p.aspect,
                                mood = if (isOn(Opt.MOOD)) mood else p.mood,
                                color = if (isOn(Opt.LOOK)) color else p.color,
                                transition = if (isOn(Opt.TRANSITION)) transition else p.transition,
                            )
                        }
                        intents.mood?.let { p = p.copy(mood = it) }
                        p = p.copy(
                            color = intents.color ?: p.color,
                            transition = intents.transition ?: p.transition,
                            title = if (intents.noTitle) null else p.title,
                        )
                        val ruled = FilmRules.apply(
                            p, clips.map { it.meta }, insights.toMap(), speedMode, soundMode,
                            musicOn = musicSource != MusicSource.ORIGINAL,
                            useAllClips = useAll, order = order, cuts = clipCuts.toMap(),
                            targetSec = target, keepAiLengths = !isOn(Opt.MOOD) && intents.mood == null,
                        )
                        val grid = when {
                            musicSource == MusicSource.MOOD -> BeatGrid(60.0 / ruled.mood.bpm, ruled.mood.beatsPerCut)
                            else -> (if (musicSource == MusicSource.LIBRARY) libSong else song)?.tempo?.let { BeatGrid(it.beatSec, 2) }
                        }
                        EditorMediaHelper.pinEdges(ruled, CutRefiner.refine(ruled, durations, insights.toMap(), grid), durations, clipCuts)
                    },
                ).collect { ev ->
                    when (ev) {
                        is AgentEvent.PhaseChanged -> status = ev.message
                        is AgentEvent.ManualEditApprovalRequired -> {
                            approvalRequired = true
                            pendingManualEditPrompt = request
                            pendingManualEditReason = ev.reason
                            chat += ChatMessage(false, "🔒 ${ev.reason} Tap Approve below to apply AI changes.")
                        }
                        is AgentEvent.Completed -> {
                            syncFromEngine()
                            chat += ChatMessage(false, ev.summary)
                        }
                        is AgentEvent.Failed -> {
                            failed = true
                            if (ev.error is AppError.PaidConsentRequired) pendingPaidConsentError = true
                            chat += ChatMessage(false, ev.error.message, true)
                        }
                        else -> Unit
                    }
                }
                if (approvalRequired || failed) return@launch
                renderCurrentTimeline(targetSec = target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = KeyVault.redactSecrets(e.message ?: "Something went wrong.", settings.allConfiguredKeys())
                chat += ChatMessage(false, safeMsg, true)
                status = null
            } finally {
                busy = false
                progress = -1
            }
        }
    }

    fun renderTimelineOnly(previewOnly: Boolean = false) {
        if (clips.isEmpty() || _timelineProject.value.mainVideoTrack.items.isEmpty() || busy) return
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            try {
                renderCurrentTimeline(targetSec = askedLengthSec, previewOnly = previewOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = KeyVault.redactSecrets(e.message ?: "Export failed.", settings.allConfiguredKeys())
                chat += ChatMessage(false, safeMsg, true)
            } finally {
                busy = false
                progress = -1
                status = null
            }
        }
    }

    fun renderQuickPreviewExport() = renderTimelineOnly(previewOnly = true)

    private suspend fun renderCurrentTimeline(targetSec: Double?, previewOnly: Boolean = false) {
        val currentProject = _timelineProject.value
        if (currentProject.mainVideoTrack.items.isEmpty()) return

        if (musicSource == MusicSource.LIBRARY && libSong == null && currentProject.mainAudioTrack.items.isEmpty()) {
            libSong = autoPickSong(currentProject.toEditPlan())
            if (libSong == null) musicSource = MusicSource.MOOD
        }
        val songNow = when (musicSource) { MusicSource.MY_SONG -> song; MusicSource.LIBRARY -> libSong; else -> null }
        val music: MusicMux.Source? = when (musicSource) {
            MusicSource.ORIGINAL -> null
            MusicSource.MOOD -> {
                status = "Composing ${currentProject.mood.label.lowercase()} music…"
                val f = File(ctx.cacheDir, "music_${currentProject.mood.key}.wav")
                withContext(Dispatchers.Default) {
                    MusicSynth.render(currentProject.mood, minOf(usToSec(currentProject.totalDurationUs) + 2, 95.0), f)
                }
                MusicMux.Source.File(Uri.fromFile(f), 0.0)
            }
            MusicSource.MY_SONG, MusicSource.LIBRARY -> songNow?.let { s ->
                val start = s.envelope?.let { Beat.bestStart(it, 0.02, minOf(usToSec(currentProject.totalDurationUs), 30.0), s.tempo) } ?: 0.0
                MusicMux.Source.File(s.uri, start)
            }
        }

        val renderId = UUID.randomUUID().toString()
        suspend fun saveJob(st: RenderJobStatus, p: Int, msg: String, path: String? = null) {
            val state = RenderJobState(renderId, currentProject.id, currentProject.name, st, p, msg, path)
            renderJobStore.save(state)
            withContext(Dispatchers.IO) { runCatching { repo.recordRenderJob(state) } }
        }

        saveJob(RenderJobStatus.RENDERING, 0, "Exporting video…")
        status = if (previewOnly) "Exporting 720p quick preview…" else "Exporting ${exportSettings.resolution.label}…"
        output = null
        progress = 0

        val effectiveSettings = exportSettings.copy(
            forceSafeMode = exportSettings.forceSafeMode || settings.safeRender,
            previewOnly = previewOnly,
            customAspectWidth = currentProject.customAspectWidth,
            customAspectHeight = currentProject.customAspectHeight,
        )
        var usedSafe = false
        val (video, diag) = try {
            Renderer.renderProjectWithDiagnostics(
                context = ctx,
                project = currentProject,
                clips = clips.toList(),
                music = music,
                exportSettings = effectiveSettings,
                onStatus = { st ->
                    status = st
                    if ("safe mode" in st) usedSafe = true
                },
                onProgress = { p ->
                    progress = p
                },
            )
        } catch (e: CancellationException) {
            saveJob(RenderJobStatus.CANCELLED, progress, "Cancelled by user")
            throw e
        } catch (e: Exception) {
            saveJob(RenderJobStatus.FAILED, progress, e.message ?: "Export failed")
            throw e
        }

        exportDiagnostics = diag
        if (usedSafe) settings.safeRender = true
        status = "Running Quality Check…"
        progress = -1
        val report = QualityCheck.checkTimeline(
            context = ctx,
            video = video,
            project = currentProject,
            clips = clips.toList(),
            insights = insights.toMap(),
            expectMusic = music != null || currentProject.audioTracks.any { !it.muted && it.items.isNotEmpty() },
            expectSound = currentProject.mainVideoTrack.items.any { !it.muted && it.volume > 0.02f },
            targetSec = targetSec,
        )
        output = video
        qa = report
        withContext(Dispatchers.IO) {
            runCatching { repo.recordExportDiagnostics(currentProject.id, video.absolutePath, diag.toJson()) }
        }
        saveJob(RenderJobStatus.COMPLETED, 100, "Export ready", video.absolutePath)
        WorkService.notifyCompleted(ctx, "Export ready", currentProject.name)
        triggerAutosave()
        status = null
    }

    fun applySuggestedRepair(repair: SuggestedRepair) {
        when (repair.issue) {
            QaIssue.COLOUR -> setProjectLook(ColorLook.NATURAL)
            QaIssue.CAPTION_OVERLAP -> {
                val fixed = _timelineProject.value.mainCaptionTrack.items
                engine.execute(TimelineCommand.ReplaceCaptions(fixed))
                syncFromEngine()
            }
            QaIssue.CAPTION_READABILITY -> {
                val fixed = QualityCheck.repairCaptionReadability(_timelineProject.value)
                engine.execute(TimelineCommand.ReplaceCaptions(fixed))
                syncFromEngine()
            }
            QaIssue.EXPORT_CORRUPTION -> {
                settings.safeRender = true
                exportSettings = exportSettings.copy(forceSafeMode = true)
            }
            QaIssue.ROTATION -> {
                val file = output ?: return
                val rotFix = qa?.rotationFix ?: return
                viewModelScope.launch {
                    val upright = File(file.parentFile, "upright_${System.currentTimeMillis()}.mp4")
                    runCatching { withContext(Dispatchers.IO) { QualityCheck.fixRotation(file, rotFix, upright) } }
                        .onSuccess {
                            file.delete(); output = upright
                            status = "Rotated output upright."
                        }
                }
                return
            }
            QaIssue.REPEATED_SCENES -> {
                val items = _timelineProject.value.mainVideoTrack.items
                val dupIds = mutableListOf<String>()
                for (i in 1 until items.size) {
                    val a = items[i - 1]; val b = items[i]
                    if (a.sourceClipIndex == b.sourceClipIndex && kotlin.math.abs(a.sourceStartUs - b.sourceStartUs) < 600_000L) {
                        dupIds += b.id
                    }
                }
                if (dupIds.isNotEmpty()) {
                    engine.execute(TimelineCommand.Batch(dupIds.map { TimelineCommand.DeleteVideoClip(it) }, "Remove repeats"))
                    syncFromEngine()
                }
            }
            QaIssue.AUDIO_CLIPPING -> {
                val cmds = _timelineProject.value.mainVideoTrack.items.map {
                    TimelineCommand.SetClipVolume(it.id, (it.volume * 0.8f).coerceAtLeast(0.4f), it.muted)
                }
                if (cmds.isNotEmpty()) {
                    engine.execute(TimelineCommand.Batch(cmds, "Reduce audio gain"))
                    syncFromEngine()
                }
            }
            QaIssue.DULL_OPENING -> {
                val bestBeat = storyBeats.maxByOrNull { it.score }
                val idx = bestBeat?.let { b ->
                    _timelineProject.value.mainVideoTrack.items.indexOfFirst { it.sourceClipIndex == b.clipIndex }
                } ?: -1
                if (idx > 0) {
                    engine.execute(TimelineCommand.ReorderVideoClips(idx, 0))
                    syncFromEngine()
                }
            }
            QaIssue.ABRUPT_ENDING -> {
                val last = _timelineProject.value.mainVideoTrack.items.lastOrNull() ?: return
                engine.execute(TimelineCommand.SetClipTransition(last.id, Transition.SMOOTH))
                syncFromEngine()
            }
            else -> {
                send("Fix QA issue: ${repair.issue.name} (${repair.description})")
                return
            }
        }
        status = "Applied repair: ${repair.label}. Tap Export to re-render."
    }

    fun applyExportPreset(preset: ExportPreset) {
        exportSettings = exportSettings.applyPreset(preset)
        settings.preferredResolution = preset.resolution
        settings.preferredFps = preset.fps
        settings.preferredBitrateMbps = preset.bitrateMbps
        engine.execute(
            TimelineCommand.SetExportConfig(
                resolution = preset.resolution,
                fps = preset.fps,
                videoBitrateBps = preset.bitrateMbps * 1_000_000,
                audioBitrateBps = exportSettings.audioBitrateBps,
                preferredHevc = exportSettings.useHevc,
            )
        )
        syncFromEngine()
    }

    fun updateAudioBitrate(kbps: Int) {
        val clean = kbps.coerceIn(96, 320)
        settings.preferredAudioBitrateKbps = clean
        exportSettings = exportSettings.copy(audioBitrateBps = clean * 1000)
        engine.execute(
            TimelineCommand.SetExportConfig(
                resolution = exportSettings.resolution,
                fps = exportSettings.fps,
                videoBitrateBps = exportSettings.videoBitrateBps,
                audioBitrateBps = clean * 1000,
                preferredHevc = exportSettings.useHevc,
            )
        )
        syncFromEngine()
    }

    fun setUseHevc(enabled: Boolean) {
        settings.preferredCodecHevc = enabled
        exportSettings = exportSettings.copy(useHevc = enabled)
        engine.execute(
            TimelineCommand.SetExportConfig(
                resolution = exportSettings.resolution,
                fps = exportSettings.fps,
                videoBitrateBps = exportSettings.videoBitrateBps,
                audioBitrateBps = exportSettings.audioBitrateBps,
                preferredHevc = enabled,
            )
        )
        syncFromEngine()
    }

    // ------------------------------------------------------------------ Projects CRUD & Recovery

    fun resumeInterruptedSession() {
        val sess = recoverableSession ?: return
        recoverableSession = null
        openSavedProject(sess.id)
    }

    fun dismissRecoverableBanner() {
        recoverableSession = null
        recoverableRenderJob = null
        renderJobStore.clear()
    }

    fun createNewProject(name: String) {
        viewModelScope.launch {
            val proj = repo.createProject(name.ifBlank { "Untitled Edit" })
            startOver()
            engine.reset(proj)
            _timelineProject.value = proj
            showProjectsSheet = false
        }
    }

    fun openSavedProject(projectId: String) {
        viewModelScope.launch {
            when (val r = repo.loadProject(projectId, ctx)) {
                is AppResult.Success -> {
                    val loaded = r.value
                    startOver()
                    engine.reset(loaded.timeline)
                    conversation.restoreFromJson(loaded.conversationJson)
                    loaded.clipCuts.forEach { (k, v) -> clipCuts[k] = v }
                    missingAssetIndices.clear()
                    missingAssetIndices += loaded.missingAssetIndices
                    exportDiagnostics = ExportDiagnostics.fromJson(loaded.entity.lastExportDiagnosticsJson)
                    val reloaded = mutableListOf<Clip>()
                    loaded.assets.forEach { asset ->
                        val uri = asset.uriString.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            ?: return@forEach
                        val loadedOne = ClipAnalyzer.load(ctx, listOf(uri), asset.clipIndex).firstOrNull() ?: return@forEach
                        val restoredCrop = ProjectRepository.parseCropJson(asset.cropJson)
                        val mergedMeta = loadedOne.meta.copy(
                            index = asset.clipIndex,
                            description = asset.insightSummary.ifBlank { loadedOne.meta.description },
                            crop = restoredCrop ?: loadedOne.meta.crop,
                        )
                        reloaded += Clip(loadedOne.uri, mergedMeta, loadedOne.thumbnail, loadedOne.isHdr)
                    }
                    if (reloaded.isNotEmpty()) {
                        clips += reloaded
                        reloaded.forEach { c ->
                            if (c.meta.hasAudio) {
                                viewModelScope.launch {
                                    clipWaveforms[c.meta.index] = VisualCache.getWaveform(ctx, c.uri, "clip_${c.meta.index}")
                                }
                            }
                        }
                    }
                    loaded.entity.lastOutputPath?.let { path ->
                        File(path).takeIf { it.exists() }?.let { output = it }
                    }
                    syncFromEngine(autosave = false)
                    showProjectsSheet = false
                    status = if (loaded.missingAssetIndices.isEmpty()) {
                        "Loaded \"${loaded.timeline.name}\""
                    } else {
                        "Loaded \"${loaded.timeline.name}\" (${loaded.missingAssetIndices.size} missing clip(s) — relink in Projects)."
                    }
                }
                is AppResult.Failure -> status = r.error.message
            }
        }
    }

    fun relinkMissingClip(clipIndex: Int, newUri: Uri) {
        viewModelScope.launch {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = EditorMediaHelper.displayName(ctx, newUri)
            when (val res = repo.relinkAsset(ctx, _timelineProject.value.id, clipIndex, newUri.toString(), displayName)) {
                is AppResult.Success -> {
                    val loaded = res.value
                    engine.reset(loaded.timeline)
                    missingAssetIndices.clear()
                    missingAssetIndices += loaded.missingAssetIndices
                    val freshlyLoaded = ClipAnalyzer.load(ctx, listOf(newUri), clipIndex)
                    if (freshlyLoaded.isNotEmpty()) {
                        val reloadedClip = freshlyLoaded.first()
                        clips.removeAll { it.meta.index == clipIndex }
                        clips += Clip(reloadedClip.uri, reloadedClip.meta.copy(index = clipIndex), reloadedClip.thumbnail, reloadedClip.isHdr)
                        if (reloadedClip.meta.hasAudio) {
                            clipWaveforms[clipIndex] = VisualCache.getWaveform(ctx, newUri, "clip_$clipIndex")
                        }
                    }
                    syncFromEngine(autosave = false)
                    status = "Relinked clip #${clipIndex + 1}."
                }
                is AppResult.Failure -> status = res.error.message
            }
        }
    }

    fun renameProject(projectId: String, name: String) {
        viewModelScope.launch {
            repo.renameProject(projectId, name)
            if (_timelineProject.value.id == projectId) {
                engine.reset(_timelineProject.value.copy(name = name))
                _timelineProject.value = engine.project
            }
        }
    }

    fun duplicateSavedProject(projectId: String) {
        viewModelScope.launch { repo.duplicateProject(projectId) }
    }

    fun softDeleteSavedProject(projectId: String) {
        viewModelScope.launch { repo.softDeleteProject(projectId) }
    }

    fun restoreSavedProject(projectId: String) {
        viewModelScope.launch { repo.restoreProject(projectId) }
    }

    fun purgeSavedProject(projectId: String) {
        viewModelScope.launch { repo.permanentlyDeleteProject(projectId) }
    }

    fun exportProjectJsonString(projectId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.exportProjectJson(projectId)) {
                is AppResult.Success -> onReady(r.value)
                is AppResult.Failure -> status = r.error.message
            }
        }
    }

    fun importProjectJsonString(raw: String) {
        viewModelScope.launch {
            when (val r = repo.importProjectJson(raw, clips.map { it.meta })) {
                is AppResult.Success -> {
                    engine.reset(r.value)
                    syncFromEngine()
                    showProjectsSheet = false
                    status = "Imported \"${r.value.name}\""
                }
                is AppResult.Failure -> status = r.error.message
            }
        }
    }

    val shareCredit: String?
        get() = when (musicSource) { MusicSource.LIBRARY -> libSong?.credit; else -> null }

    fun cancel() {
        job?.cancel()
        busy = false; status = "Cancelled."; progress = -1
    }

    fun saveToGallery(onDone: (Uri?) -> Unit) {
        val file = output ?: return
        viewModelScope.launch {
            val uri = runCatching { Renderer.saveToGallery(ctx, file) }.getOrNull()
            status = if (uri != null) "Saved to Movies/PromptCut." else "Couldn't save the video."
            onDone(uri)
        }
    }
}
