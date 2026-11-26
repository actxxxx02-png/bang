package com.promptcut.app.agent

enum class AgentPhase(val label: String) {
    IDLE("Ready"),
    ANALYZING("Inspecting footage"),
    PLANNING("Planning edit"),
    VALIDATING("Validating tools"),
    WAITING_APPROVAL("Waiting for approval"),
    EXECUTING("Applying timeline tools"),
    VERIFYING("Verifying quality"),
    COMPLETED("Done"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
}

data class AgentStep(
    val toolName: String,
    val summary: String,
    val succeeded: Boolean,
)

data class AgentState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val statusText: String = "",
    val iteration: Int = 0,
    val maxIterations: Int = 4,
    val executedSteps: List<AgentStep> = emptyList(),
    val pendingApprovalReason: String? = null,
    val errorMessage: String? = null,
) {
    val isRunning: Boolean
        get() = phase in setOf(
            AgentPhase.ANALYZING,
            AgentPhase.PLANNING,
            AgentPhase.VALIDATING,
            AgentPhase.EXECUTING,
            AgentPhase.VERIFYING,
        )
}
