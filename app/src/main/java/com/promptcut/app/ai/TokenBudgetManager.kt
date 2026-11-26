package com.promptcut.app.ai

import com.promptcut.app.Frame

/**
 * Estimates prompt + frame token counts and enforces context budgets per provider
 * so conversations and clip descriptions stay fast and within quota limits.
 */
class TokenBudgetManager {

    data class BudgetReport(
        val estimatedTokens: Int,
        val maxBudgetTokens: Int,
        val compacted: Boolean,
        val droppedMessages: Int,
    )

    fun estimateTokens(text: String): Int = ((text.length + 3) / 4).coerceAtLeast(1)

    fun estimateFramesTokens(frames: List<Frame>): Int = frames.size * TOKENS_PER_FRAME

    fun estimateRequestTokens(
        systemPrompt: String,
        messages: List<AiMessage>,
        frames: List<Frame>,
    ): Int {
        val msgTok = messages.sumOf { estimateTokens(it.content) + 8 }
        return estimateTokens(systemPrompt) + msgTok + estimateFramesTokens(frames)
    }

    fun budgetFor(provider: ProviderId): Int = when (provider) {
        ProviderId.GROQ -> 18_000      // Free-tier TPM friendly
        ProviderId.GEMINI -> 48_000    // Generous free context while avoiding bloated payloads
        ProviderId.CLAUDE -> 36_000    // Keep paid token usage predictable
    }

    /**
     * Fits [messages] inside [maxTokens] by keeping the newest [keepRecentCount] messages
     * and collapsing older messages into a concise text block if needed.
     */
    fun fitMessages(
        systemPrompt: String,
        messages: List<AiMessage>,
        frames: List<Frame>,
        provider: ProviderId,
        keepRecentCount: Int = 6,
    ): Pair<List<AiMessage>, BudgetReport> {
        val maxTokens = budgetFor(provider)
        val initial = estimateRequestTokens(systemPrompt, messages, frames)
        if (initial <= maxTokens || messages.size <= keepRecentCount) {
            return messages to BudgetReport(initial, maxTokens, compacted = false, droppedMessages = 0)
        }

        val recent = messages.takeLast(keepRecentCount).toMutableList()
        val older = messages.dropLast(keepRecentCount)
        val summaryLines = older.mapNotNull { m ->
            val role = if (m.role == AiRole.USER) "User" else if (m.role == AiRole.ASSISTANT) "Editor" else return@mapNotNull null
            val snippet = m.content.replace("\n", " ").trim().take(120)
            if (snippet.isBlank()) null else "- $role: $snippet"
        }.takeLast(8)

        val compactedList = buildList {
            if (summaryLines.isNotEmpty()) {
                add(
                    AiMessage(
                        AiRole.USER,
                        "Earlier conversation summary:\n${summaryLines.joinToString("\n")}",
                    )
                )
            }
            addAll(recent)
        }
        val finalTok = estimateRequestTokens(systemPrompt, compactedList, frames)
        return compactedList to BudgetReport(
            estimatedTokens = finalTok,
            maxBudgetTokens = maxTokens,
            compacted = true,
            droppedMessages = older.size,
        )
    }

    companion object {
        private const val TOKENS_PER_FRAME = 258
    }
}
