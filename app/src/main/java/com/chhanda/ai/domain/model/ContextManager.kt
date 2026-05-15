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
            // ADAPTIVE AUGMENTATION: Only combine with previous turn if query is a follow-up
            // (short or contains common pronouns).
            var augmentedQuery = query
            val pronouns = listOf("it", "this", "that", "those", "these", "they", "them", "he", "she", "his", "her")
            val isFollowUp = query.split(" ").size < 5 || pronouns.any { query.lowercase().contains(it) }
            
            val lastUserTurn = rawHistory.findLast { it.role == "user" }
            if (isFollowUp && lastUserTurn != null && lastUserTurn.text != query) {
                augmentedQuery = "${lastUserTurn.text} $query"
            }

            // Generate vector embedding for the query
            val queryEmbedding = embeddingEngine.embed(augmentedQuery)
            
            // Search the vector store.
            val results = vectorStore.search(queryEmbedding, topK = if (modelName.contains("4B")) 8 else 12, modelId = "shared_rag_db")
            
            val isExplicitSearch = query.lowercase().contains("attachment") || query.lowercase().contains("file") || 
                                 query.lowercase().contains("web") || query.lowercase().contains("search")
            
            // Senior Optimization: Lowering thresholds for the lightweight on-device embedding engine.
            // 0.45 was too aggressive for hash-based projections. 
            // 0.30 provides a better balance for on-device RAG.
            val threshold = if (isExplicitSearch) 0.25f else 0.30f 
            var filtered = results.filter { it.score >= threshold } 

            if (filtered.isEmpty() && isFollowUp) {
                val rawResults = vectorStore.search(embeddingEngine.embed(query), topK = 10, modelId = "shared_rag_db")
                filtered = rawResults.filter { it.score >= 0.25f }
            }

            // Format results using XML-style tags for better LLM boundary detection.
            if (filtered.isEmpty()) ""
            else {
                buildString {
                    append("<retrieved_knowledge>\n")
                    filtered.distinctBy { it.text.take(100) }
                        .groupBy { it.metadata["source"] ?: "General" }
                        .entries.take(5)
                        .forEachIndexed { index, entry ->
                            val source = entry.key.substringAfterLast("/").substringAfterLast("\\")
                            append("[Source #$index: $source]\n")
                            append(entry.value.take(2).joinToString("\n") { it.text })
                            append("\n\n")
                        }
                    append("</retrieved_knowledge>")
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
