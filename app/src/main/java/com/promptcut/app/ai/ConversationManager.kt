package com.promptcut.app.ai

import com.promptcut.app.Frame
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent conversation memory for the AI editing assistant & agent.
 * Remembers:
 * - Previous messages
 * - Project instructions & constraints
 * - Key editing decisions
 * - Currently selected clips / timeline segment
 * - Recent editing operations
 * - Rolling summary when conversations grow long
 */
class ConversationManager(
    private val budgetManager: TokenBudgetManager = TokenBudgetManager(),
) {
    private val _messages = mutableListOf<AiMessage>()
    val messages: List<AiMessage> get() = _messages.toList()

    var projectInstructions: String = ""
    private val _decisions = mutableListOf<String>()
    val editingDecisions: List<String> get() = _decisions.toList()

    var selectedClipIndices: Set<Int> = emptySet()
    var selectedSegmentId: String? = null
    private val _recentOperations = mutableListOf<String>()
    val recentOperations: List<String> get() = _recentOperations.toList()

    var rollingSummary: String = ""
        private set

    fun addUserMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _messages += AiMessage(AiRole.USER, clean)
        compactRollingMemoryIfNeeded()
    }

    fun addAssistantMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _messages += AiMessage(AiRole.ASSISTANT, clean)
        compactRollingMemoryIfNeeded()
    }

    fun recordDecision(decision: String) {
        val clean = decision.trim()
        if (clean.isEmpty()) return
        if (_decisions.lastOrNull() != clean) _decisions += clean
        if (_decisions.size > 15) _decisions.removeAt(0)
    }

    fun recordOperation(operation: String) {
        val clean = operation.trim()
        if (clean.isEmpty()) return
        _recentOperations += clean
        if (_recentOperations.size > 20) _recentOperations.removeAt(0)
    }

    fun buildContextBlock(): String = buildString {
        if (projectInstructions.isNotBlank()) {
            appendLine("PROJECT INSTRUCTIONS: $projectInstructions")
        }
        if (rollingSummary.isNotBlank()) {
            appendLine("CONVERSATION SUMMARY:")
            appendLine(rollingSummary)
        }
        if (selectedClipIndices.isNotEmpty() || selectedSegmentId != null) {
            append("CURRENT SELECTION: ")
            if (selectedClipIndices.isNotEmpty()) append("clips=${selectedClipIndices.sorted().joinToString()} ")
            if (selectedSegmentId != null) append("segment=$selectedSegmentId")
            appendLine()
        }
        if (_decisions.isNotEmpty()) {
            appendLine("EDITING DECISIONS SO FAR:")
            _decisions.takeLast(8).forEach { appendLine("- $it") }
        }
        if (_recentOperations.isNotEmpty()) {
            appendLine("RECENT OPERATIONS:")
            _recentOperations.takeLast(8).forEach { appendLine("- $it") }
        }
    }.trim()

    fun buildBudgetedMessages(
        systemPrompt: String,
        latestUserPrompt: String,
        frames: List<Frame>,
        provider: ProviderId,
    ): Pair<List<AiMessage>, TokenBudgetManager.BudgetReport> {
        val ctx = buildContextBlock()
        val combinedUser = if (ctx.isNotBlank()) "$ctx\n\n$latestUserPrompt" else latestUserPrompt
        val candidate = (_messages.takeLast(10) + AiMessage(AiRole.USER, combinedUser))
        return budgetManager.fitMessages(systemPrompt, candidate, frames, provider)
    }

    fun clear() {
        _messages.clear()
        projectInstructions = ""
        _decisions.clear()
        selectedClipIndices = emptySet()
        selectedSegmentId = null
        _recentOperations.clear()
        rollingSummary = ""
    }

    private fun compactRollingMemoryIfNeeded() {
        if (_messages.size <= MAX_LIVE_MESSAGES) return
        val toSummarize = _messages.dropLast(KEEP_AFTER_SUMMARY)
        val kept = _messages.takeLast(KEEP_AFTER_SUMMARY)
        val addition = toSummarize.joinToString("\n") { m ->
            val who = if (m.role == AiRole.USER) "User" else "AI"
            "$who: ${m.content.replace("\n", " ").take(140)}"
        }
        rollingSummary = listOf(rollingSummary, addition)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeLast(1800)
        _messages.clear()
        _messages.addAll(kept)
    }

    fun toJson(): String = JSONObject().apply {
        put("instructions", projectInstructions)
        put("summary", rollingSummary)
        put("selectedSegmentId", selectedSegmentId ?: "")
        put("selectedClips", JSONArray(selectedClipIndices.toList()))
        put("decisions", JSONArray(_decisions))
        put("operations", JSONArray(_recentOperations))
        put("messages", JSONArray().apply {
            _messages.forEach { m ->
                put(JSONObject().put("role", m.role.name).put("content", m.content))
            }
        })
    }.toString()

    fun restoreFromJson(json: String) {
        if (json.isBlank()) return
        runCatching {
            val o = JSONObject(json)
            _messages.clear()
            _decisions.clear()
            _recentOperations.clear()
            projectInstructions = o.optString("instructions", "")
            rollingSummary = o.optString("summary", "")
            selectedSegmentId = o.optString("selectedSegmentId", "").ifBlank { null }
            val sc = o.optJSONArray("selectedClips") ?: JSONArray()
            selectedClipIndices = (0 until sc.length()).map { sc.getInt(it) }.toSet()
            val dec = o.optJSONArray("decisions") ?: JSONArray()
            for (i in 0 until dec.length()) _decisions += dec.getString(i)
            val ops = o.optJSONArray("operations") ?: JSONArray()
            for (i in 0 until ops.length()) _recentOperations += ops.getString(i)
            val msgs = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val role = runCatching { AiRole.valueOf(m.getString("role")) }.getOrDefault(AiRole.USER)
                _messages += AiMessage(role, m.getString("content"))
            }
        }
    }

    companion object {
        private const val MAX_LIVE_MESSAGES = 16
        private const val KEEP_AFTER_SUMMARY = 8
    }
}
