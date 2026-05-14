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
        // Short-Term Memory: Last 10 messages for deeper conversation context
        val recentMessages = try {
            chatDao.getRecentMessagesForSession(sessionId, 10).reversed()
        } catch (e: Exception) {
            emptyList<MessageEntity>()
        }
        val history = recentMessages.map { it.role to it.text }

        // Long-Term Memory: Vector search for semantic relevance across all history
        val longTermMemory = try {
            // ADVANCED SELECTIVITY: Only skip for very short interactions
            val smallTalkKeywords = listOf("hi", "hello", "hey", "thanks", "thank you", "bye", "ok", "okay")
            val isSmallTalk = smallTalkKeywords.any { query.lowercase().trim() == it } || (query.trim().length < 3)
            
            if (isSmallTalk && history.size < 2) {
                android.util.Log.d("ContextManager", "Small talk detected for '$query'. Skipping RAG.")
                return history to ""
            }

            // QUERY AUGMENTATION: For short/vague follow-ups, include previous user context in the embedding search
            val augmentedQuery = if (query.split(" ").size < 6 && history.isNotEmpty()) {
                val lastUserQuery = history.findLast { it.first == "user" }?.second ?: ""
                if (lastUserQuery.isNotEmpty() && lastUserQuery != query) {
                    "$lastUserQuery $query"
                } else query
            } else query

            android.util.Log.d("ContextManager", "RAG Query: '$augmentedQuery' (Original: '$query')")
            val queryEmbedding = embeddingEngine.embed(augmentedQuery)
            val results = vectorStore.search(queryEmbedding, topK = if (modelName.contains("4B")) 8 else 12, modelId = "shared_rag_db")
            
            // Adaptive threshold: lower for attachments/specific files, higher for general KB to prevent hallucinations
            val isExplicitSearch = query.lowercase().contains("attachment") || query.lowercase().contains("file") || query.lowercase().contains("image")
            val threshold = if (isExplicitSearch) 0.55f else 0.68f 
            
            val filtered = results.filter { it.score >= threshold } 

            android.util.Log.d("ContextManager", "RAG Search found ${results.size} total. Threshold: $threshold. Filtered to ${filtered.size} snippets.")

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
            ""
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
