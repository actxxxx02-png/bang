package com.promptcut.app.agent

import com.promptcut.app.core.AppError

sealed class AgentEvent {
    data class PhaseChanged(val phase: AgentPhase, val message: String) : AgentEvent()
    data class StreamToken(val token: String) : AgentEvent()
    data class ToolPlanned(val toolName: String, val summary: String) : AgentEvent()
    data class ToolExecuted(val step: AgentStep) : AgentEvent()
    data class ManualEditApprovalRequired(
        val reason: String,
        val modifiedItemCount: Int,
    ) : AgentEvent()
    data class Completed(val summary: String, val steps: List<AgentStep>) : AgentEvent()
    data class Failed(val error: AppError) : AgentEvent()
}
