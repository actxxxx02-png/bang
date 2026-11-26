package com.promptcut.app.agent

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.EditPlan
import com.promptcut.app.Frame
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.core.AppError
import com.promptcut.app.timeline.TimelineEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach

/**
 * Stateful facade for PromptCut's autonomous AI editing agent.
 */
class EditingAgent(
    router: ProviderRouter,
    val conversation: ConversationManager,
    private val engine: TimelineEngine,
) {
    private val planner = AgentPlanner(router, conversation)
    private val executor = AgentExecutor(engine)
    private val loop = AgentLoop(planner, executor, engine, conversation)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    fun reset() {
        _state.value = AgentState()
    }

    fun executeInstruction(
        prompt: String,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        clipUris: Map<Int, String>,
        frames: List<Frame>,
        freshEdit: Boolean,
        allowOverwriteManualEdits: Boolean = false,
        manualProvider: ProviderId? = null,
        postProcessPlan: ((EditPlan) -> EditPlan)? = null,
    ): Flow<AgentEvent> {
        _state.value = AgentState(phase = AgentPhase.ANALYZING, statusText = "Starting agent…", iteration = 1)
        return loop.run(
            userPrompt = prompt,
            clips = clips,
            insights = insights,
            clipUris = clipUris,
            frames = frames,
            freshEdit = freshEdit,
            allowOverwriteManualEdits = allowOverwriteManualEdits,
            manualProvider = manualProvider,
            postProcessPlan = postProcessPlan,
        ).onEach { ev ->
            when (ev) {
                is AgentEvent.PhaseChanged -> _state.value = _state.value.copy(
                    phase = ev.phase,
                    statusText = ev.message,
                    pendingApprovalReason = null,
                    errorMessage = null,
                )
                is AgentEvent.ToolExecuted -> _state.value = _state.value.copy(
                    executedSteps = _state.value.executedSteps + ev.step,
                )
                is AgentEvent.ManualEditApprovalRequired -> _state.value = _state.value.copy(
                    phase = AgentPhase.WAITING_APPROVAL,
                    statusText = ev.reason,
                    pendingApprovalReason = ev.reason,
                )
                is AgentEvent.Completed -> _state.value = _state.value.copy(
                    phase = AgentPhase.COMPLETED,
                    statusText = ev.summary,
                    pendingApprovalReason = null,
                )
                is AgentEvent.Failed -> _state.value = _state.value.copy(
                    phase = if (ev.error is AppError.Cancelled) AgentPhase.CANCELLED else AgentPhase.FAILED,
                    statusText = ev.error.message,
                    errorMessage = ev.error.message,
                )
                is AgentEvent.StreamToken, is AgentEvent.ToolPlanned -> Unit
            }
        }
    }
}
