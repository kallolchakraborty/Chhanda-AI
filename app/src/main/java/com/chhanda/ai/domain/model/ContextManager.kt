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
            val results = vectorStore.search(queryEmbedding, topK = 6, modelId = "shared_rag_db")
            
            val filtered = results
                .filter { it.score >= 0.10f } 

            android.util.Log.d("ContextManager", "RAG Search for '$query' found ${results.size} total. Scores: ${results.joinToString { String.format("%.3f", it.score) }}")
            android.util.Log.d("ContextManager", "Filtered to ${filtered.size} snippets above 0.10 threshold")

            if (filtered.isEmpty()) ""
            else {
                "RELEVANT DOCUMENTATION SNIPPETS:\n" +
                filtered.groupBy { it.metadata["source"] ?: "General Knowledge" }
                    .entries.joinToString("\n\n") { (source, chunks) ->
                        val sourceName = source.substringAfterLast("/").substringAfterLast("\\")
                        "[SOURCE: $sourceName]\n" + chunks.joinToString("\n---\n") { it.text }
                    }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContextManager", "Long-term memory retrieval failed: ${e.message}")
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
