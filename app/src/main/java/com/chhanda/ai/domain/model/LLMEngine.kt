package com.chhanda.ai.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * Core interface for the Large Language Model inference engine.
 * Decouples the UI and Orchestration layers from the specific runtime (LiteRT/TFLite).
 */
interface LLMEngine {
    
    /**
     * Initializes the engine with a specific model file.
     */
    suspend fun initModel(path: String)

    /**
     * Generates a streaming response for the given prompt.
     * @param prompt The formatted prompt string.
     * @param attachments Optional list of file URIs for multimodal context.
     * @return A Flow of partial token strings.
     */
    fun generateResponse(
        prompt: String, 
        history: List<Pair<String, String>> = emptyList(),
        systemInstruction: String? = null,
        attachments: List<android.net.Uri> = emptyList()
    ): Flow<TokenUpdate>

    /**
     * Resets the current chat session (clears history).
     */
    suspend fun resetSession()
    
    /**
     * Returns true if a persistent native session is already active.
     */
    fun isSessionActive(): Boolean

    /**
     * Cancels the current inference task and releases associated resources.
     */
    fun stopInference()

    /**
     * Real-time telemetry for the inference engine (e.g., tokens per second).
     */
    val performanceMetrics: Flow<Double>

    /**
     * Releases the model from memory.
     */
    suspend fun close()

    /**
     * Checks if the model is currently loaded in memory.
     */
    fun isModelLoaded(): Boolean

    /**
     * Gets the name of the currently loaded model.
     */
    fun getCurrentModelName(): String

    /**
     * Checks if the model is currently being loaded.
     */
    fun isModelLoading(): Boolean

    /**
     * Real-time loading progress (0.0 to 1.0).
     */
    val loadingProgress: Flow<Float>
}

sealed class TokenUpdate {
    data class Partial(val text: String, val tps: Double = 0.0) : TokenUpdate()
    data class Final(val fullText: String, val tps: Double = 0.0, val responseTimeMs: Long = 0) : TokenUpdate()
    data class Status(val message: String) : TokenUpdate()
    data class Error(val message: String) : TokenUpdate()
}
