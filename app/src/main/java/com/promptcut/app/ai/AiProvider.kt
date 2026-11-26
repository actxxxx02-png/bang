package com.promptcut.app.ai

import com.promptcut.app.core.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Unified AI provider contract implemented by Gemini, Groq, and Claude adapters.
 */
interface AiProvider {
    val id: ProviderId
    val capabilities: AiCapabilities

    suspend fun listModels(apiKey: String): AppResult<List<String>>
    suspend fun testConnection(apiKey: String, preferredModel: String? = null): AppResult<String>
    suspend fun complete(apiKey: String, preferredModel: String? = null, request: AiRequest): AppResult<AiResponse>
    fun stream(apiKey: String, preferredModel: String? = null, request: AiRequest): Flow<AiStreamEvent>
}
