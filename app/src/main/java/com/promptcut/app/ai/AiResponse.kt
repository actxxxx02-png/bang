package com.promptcut.app.ai

import com.promptcut.app.core.AppError

data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AiUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double = 0.0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

data class AiResponse(
    val text: String,
    val model: String,
    val provider: ProviderId,
    val toolCalls: List<AiToolCall> = emptyList(),
    val usage: AiUsage? = null,
)

sealed class AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent()
    data class ToolCallReady(val toolCall: AiToolCall) : AiStreamEvent()
    data class Completed(val response: AiResponse) : AiStreamEvent()
    data class Error(val error: AppError) : AiStreamEvent()
}
