package com.chhanda.ai.domain.model

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity

/**
 * ContextManager: Orchestrator for Memory and Context Optimization.
 * Manages Short-Term (Sliding Window) and Long-Term (RAG/Persistent) memory.
 */
@javax.inject.Singleton
class ContextManager @javax.inject.Inject constructor(
    private val chatDao: ChatDao,
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * Optimizes context for the LLM by combining recent history with relevant long-term memory.
     */
    suspend fun getOptimizedContext(query: String, deviceId: String, modelName: String, sessionId: String): Pair<List<Pair<String, String>>, String> {
        // Short-Term Memory: Last 4 messages for fast immediate conversation context
        val recentMessages = try {
            chatDao.getRecentMessagesForSession(sessionId, 4).reversed()
        } catch (e: Exception) {
            emptyList<MessageEntity>()
        }
        val history = recentMessages.map { it.role to it.text }

        // Long-Term Memory: Vector search for semantic relevance across all history
        val longTermMemory = try {
            val queryEmbedding = embeddingEngine.embed(query)
            // Use topK=3 but filter for high-confidence/low-signal chunks
            val results = vectorStore.search(queryEmbedding, topK = 3, modelId = modelName)
            
            results
                .filter { it.score > 0.65f } // Discard low-signal chunks
                .filter { result ->
                    // Avoid repeating context already in recent history
                    recentMessages.none { it.text.contains(result.text.take(20)) }
                }
                .joinToString("\n---\n") { result ->
                    val source = result.metadata["source"] ?: "Local Source"
                    val type = result.metadata["type"] ?: "Document"
                    "[Source: $source ($type)]\n${result.text}"
                }
                .take(1000) // Adaptive limit for mobile
        } catch (e: Exception) {
            "" // Graceful fallback
        }

        return history to longTermMemory
    }

    /**
     * Clears all memory for the current device with full persistence cleanup.
     */
    suspend fun clearAllHistory() {
        try {
            chatDao.clearHistory()
            vectorStore.clear()
        } catch (e: Exception) {
            // Best-effort cleanup — do not rethrow
        }
    }

    /**
     * Maintenance: Prunes excessively long conversations to keep inference fast.
     */
    suspend fun maintainMemoryHygiene() {
        try {
            chatDao.pruneMessages(100) // Keep last 100 turns in active DB
        } catch (e: Exception) {
            // Non-critical maintenance — ignore failures
        }
    }
}
