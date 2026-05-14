package com.chhanda.ai.domain.model

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Senior Memory Management: Adaptive Retrieval & Contextual Ranking.
 * Enhanced with Production-Grade Performance Monitoring.
 */
@Singleton
class ContextManager @Inject constructor(
    private val chatDao: ChatDao,
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine,
    private val metricsManager: RAGMetricsManager
) {
    /**
     * Retrieves the most relevant history and database context for a query.
     */
    suspend fun getOptimizedContext(
        query: String, 
        deviceId: String, 
        modelName: String, 
        sessionId: String
    ): Pair<List<Pair<String, String>>, String> {
        val startTime = System.currentTimeMillis()
        
        // STEP 1: Fetch and sanitize history (Last 10 turns)
        val rawHistory: List<MessageEntity> = try {
            chatDao.getRecentMessagesForSession(sessionId, 10).reversed()
        } catch (e: Exception) {
            emptyList()
        }
        
        // STEP 2: Semantic RAG Retrieval
        val longTermMemory = try {
            var augmentedQuery = query
            val lastUserTurn = rawHistory.findLast { it.role == "user" }
            if (lastUserTurn != null && lastUserTurn.text != query) {
                augmentedQuery = "${lastUserTurn.text} $query"
            }

            val queryEmbedding = embeddingEngine.embed(augmentedQuery)
            val results = vectorStore.search(queryEmbedding, topK = if (modelName.contains("4B")) 8 else 12, modelId = "shared_rag_db")
            
            val isExplicitSearch = query.lowercase().contains("attachment") || query.lowercase().contains("file") || 
                                 query.lowercase().contains("web") || query.lowercase().contains("search") || 
                                 query.lowercase().contains("http")
            
            val threshold = if (isExplicitSearch) 0.38f else 0.48f 
            var filtered = results.filter { it.score >= threshold } 

            if (filtered.isEmpty() && augmentedQuery != query) {
                val rawResults = vectorStore.search(embeddingEngine.embed(query), topK = 10, modelId = "shared_rag_db")
                filtered = rawResults.filter { it.score >= 0.42f }
            }

            if (filtered.isEmpty()) ""
            else {
                "DATABASE KNOWLEDGE RETRIEVED (High Confidence):\n" +
                filtered.distinctBy { it.text.take(100) }
                    .groupBy { it.metadata["source"] ?: "General Knowledge" }
                    .entries.take(5)
                    .joinToString("\n\n") { entry ->
                        val source = entry.key
                        val chunks = entry.value
                        val sourceName = source.substringAfterLast("/").substringAfterLast("\\")
                        "[SOURCE: $sourceName]\n" + chunks.take(3).joinToString("\n---\n") { it.text }
                    }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContextManager", "Long-term memory retrieval failed: ${e.message}")
            ""
        }

        // Record metrics for production monitoring
        metricsManager.recordQuery(System.currentTimeMillis() - startTime)

        val historyResult = rawHistory.map { Pair(it.role, it.text) }
        return Pair(historyResult, longTermMemory)
    }

    suspend fun clearAllHistory() {
        try {
            chatDao.clearHistory()
            vectorStore.clear()
        } catch (e: Exception) { }
    }

    suspend fun maintainMemoryHygiene() { }
}
