package com.promptcut.app.agent

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.EditPlan
import com.promptcut.app.Frame
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.round1
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

data class AgentRunResult(
    val explanation: String,
    val steps: List<AgentStep>,
    val outcome: ExecutionOutcome?,
    val plan: EditPlan?,
    val requiresApproval: Boolean = false,
    val approvalReason: String? = null,
)

/**
 * Bounded Plan → Validate → Protect Manual Edits → Execute → Verify loop with timeouts & cancellation.
 */
class AgentLoop(
    private val planner: AgentPlanner,
    private val executor: AgentExecutor,
    private val engine: TimelineEngine,
    private val conversation: ConversationManager,
    private val timeoutMs: Long = 110_000L,
) {
    fun run(
        userPrompt: String,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        clipUris: Map<Int, String>,
        frames: List<Frame>,
        freshEdit: Boolean,
        allowOverwriteManualEdits: Boolean = false,
        manualProvider: ProviderId? = null,
        postProcessPlan: ((EditPlan) -> EditPlan)? = null,
    ): Flow<AgentEvent> = flow {
        try {
            emit(AgentEvent.PhaseChanged(AgentPhase.ANALYZING, "Reviewing clips and timeline…"))
            if (clips.isEmpty()) {
                emit(AgentEvent.Failed(AppError.Validation("Add at least one video clip first.")))
                return@flow
            }

            emit(AgentEvent.PhaseChanged(AgentPhase.PLANNING, "Planning edit steps…"))
            val planResult = withTimeout(timeoutMs) {
                planner.plan(
                    userPrompt = userPrompt,
                    project = engine.project,
                    clips = clips,
                    insights = insights,
                    frames = frames,
                    freshEdit = freshEdit,
                    manualProvider = manualProvider,
                )
            }
            val planned = when (planResult) {
                is AppResult.Success -> planResult.value
                is AppResult.Failure -> {
                    emit(AgentEvent.Failed(planResult.error))
                    return@flow
                }
            }

            planned.toolCalls.forEach { tc ->
                emit(AgentEvent.ToolPlanned(tc.toolName, "Queued ${tc.toolName}"))
            }

            emit(AgentEvent.PhaseChanged(AgentPhase.VALIDATING, "Validating editing tools…"))
            val processedEditPlan = planned.editPlan?.let { ep -> postProcessPlan?.invoke(ep) ?: ep }
            val validation = AgentValidator.validate(
                invocations = planned.toolCalls,
                project = engine.project,
                clips = clips,
                allowOverwriteManualEdits = allowOverwriteManualEdits,
                replacesWholeTimeline = processedEditPlan != null && engine.project.mainVideoTrack.items.isNotEmpty(),
            )
            val valReport = when (validation) {
                is AppResult.Success -> validation.value
                is AppResult.Failure -> {
                    emit(AgentEvent.Failed(validation.error))
                    return@flow
                }
            }

            if (valReport.requiresManualEditApproval) {
                emit(
                    AgentEvent.ManualEditApprovalRequired(
                        reason = valReport.approvalReason ?: "Manual edits detected on timeline.",
                        modifiedItemCount = valReport.affectedManualItemIds.size,
                    )
                )
                return@flow
            }

            emit(AgentEvent.PhaseChanged(AgentPhase.EXECUTING, "Applying tools to timeline…"))
            val exec = executor.execute(
                invocations = valReport.validatedTools,
                clips = clips,
                insights = insights,
                clipUris = clipUris,
                replacementPlan = processedEditPlan,
            )
            val outcome = when (exec) {
                is AppResult.Success -> exec.value
                is AppResult.Failure -> {
                    emit(AgentEvent.Failed(exec.error))
                    return@flow
                }
            }

            outcome.steps.forEach { step ->
                emit(AgentEvent.ToolExecuted(step))
                conversation.recordOperation("${step.toolName}: ${step.summary}")
            }

            emit(AgentEvent.PhaseChanged(AgentPhase.VERIFYING, "Verifying timeline integrity…"))
            val totalSec = round1(usToSec(outcome.updatedProject.totalDurationUs))
            val summary = "${planned.explanation} (${totalSec}s, ${outcome.updatedProject.mainVideoTrack.items.size} clips)"
            conversation.addUserMessage(userPrompt)
            conversation.addAssistantMessage(summary)
            conversation.recordDecision(planned.explanation)

            emit(AgentEvent.Completed(summary, outcome.steps))
        } catch (te: TimeoutCancellationException) {
            emit(AgentEvent.Failed(AppError.Timeout("The AI agent timed out. Try again or shorten the request.", cause = te)))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            emit(AgentEvent.Failed(AppError.Unknown(e.message ?: "Agent stopped unexpectedly.", cause = e)))
        }
    }
}
