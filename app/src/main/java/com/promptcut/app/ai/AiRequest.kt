package com.promptcut.app.ai

import com.promptcut.app.Frame

enum class AiRole { SYSTEM, USER, ASSISTANT, TOOL }

data class AiToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

data class AiMessage(
    val role: AiRole,
    val content: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class AiRequest(
    val systemPrompt: String,
    val messages: List<AiMessage>,
    val frames: List<Frame> = emptyList(),
    val tools: List<AiToolSpec> = emptyList(),
    val jsonMode: Boolean = false,
    val temperature: Double = 0.3,
    val maxTokens: Int = 4096,
    val timeoutMs: Long = 90_000L,
) {
    constructor(
        systemPrompt: String,
        userPrompt: String,
        frames: List<Frame> = emptyList(),
        tools: List<AiToolSpec> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Double = 0.3,
        maxTokens: Int = 4096,
        timeoutMs: Long = 90_000L,
    ) : this(
        systemPrompt = systemPrompt,
        messages = listOf(AiMessage(AiRole.USER, userPrompt)),
        frames = frames,
        tools = tools,
        jsonMode = jsonMode,
        temperature = temperature,
        maxTokens = maxTokens,
        timeoutMs = timeoutMs,
    )
}
