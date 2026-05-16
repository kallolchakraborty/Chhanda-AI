package com.chhanda.ai.data.repository

import com.chhanda.ai.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Senior Optimization: Local Vector Store with optimized similarity math.
 */

class LocalVectorStore @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val vectorChunkDao: VectorChunkDao,
    private val settingsRepository: SettingsRepository
) : VectorStore {

    override suspend fun add(text: String, embedding: Embedding, metadata: Map<String, String>, modelId: String) {
        if (text.isBlank()) return
        val entity = VectorChunkEntity(
            id = java.util.UUID.randomUUID().toString(),
            modelId = modelId,
            text = text,
            source = metadata["source"] ?: "Local Device",
            type = metadata["type"] ?: "TXT",
            embeddingBlob = VectorChunkEntity.fromFloatArray(embedding.vector)
        )
        vectorChunkDao.insert(entity)
    }

    override suspend fun addAll(entities: List<VectorChunkEntity>) {
        if (entities.isEmpty()) return
        vectorChunkDao.insertAll(entities)
    }

    /**
     * Searches the vector store for the top-K most similar chunks to the query embedding.
     * Uses a Min-Heap (PriorityQueue) to ensure O(N log K) complexity.
     */
    override suspend fun search(query: Embedding, topK: Int, modelId: String, queryText: String?): List<SearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        
        // TIER 1: Keyword-based candidate retrieval (BM25)
        // If queryText is provided, we fetch high-probability candidates using FTS5 first.
        val candidates = if (!queryText.isNullOrBlank()) {
            try {
                // Sanitize query for FTS (remove special characters that break MATCH)
                val cleanQuery = queryText.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
                if (cleanQuery.isNotEmpty()) {
                    vectorChunkDao.searchKeywords("$cleanQuery*")
                } else {
                    vectorChunkDao.getAllForModel(modelId)
                }
            } catch (e: Exception) {
                android.util.Log.w("LocalVectorStore", "FTS search failed, falling back: ${e.message}")
                vectorChunkDao.getAllForModel(modelId)
            }
        } else {
            vectorChunkDao.getAllForModel(modelId)
        }

        if (candidates.isEmpty()) return@withContext emptyList()

        val queryVector = query.vector
        val queryNorm = calculateNorm(queryVector)
        if (queryNorm < 1e-8) return@withContext emptyList()

        val topResults = java.util.PriorityQueue<SearchResult>(topK + 1) { a, b -> a.score.compareTo(b.score) }

        // TIER 2: Quantized Cosine Similarity on candidates only
        for (entity in candidates) {
            val vectorBytes = entity.embeddingBlob
            if (vectorBytes.size != queryVector.size) continue
            
            var dotProductInt = 0
            var vNormSqInt = 0
            for (i in queryVector.indices) {
                val qi = (queryVector[i] * 127f).toInt()
                val vi = vectorBytes[i].toInt()
                dotProductInt += qi * vi
                vNormSqInt += vi * vi
            }
            
            val vNorm = kotlin.math.sqrt(vNormSqInt.toDouble()).toFloat()
            val score = if (vNorm > 1e-8) (dotProductInt.toFloat() / (queryNorm * 127f * vNorm)) else 0.0f
            
            // HEURISTIC: Reject low-quality matches
            if (score < 0.25f) continue 

            val result = SearchResult(
                text = entity.text, 
                score = score, 
                metadata = mapOf("source" to entity.source, "type" to entity.type)
            )
            
            topResults.add(result)
            if (topResults.size > topK) {
                topResults.poll()
            }
        }
        
        // CONTEXT PRUNING: Capping result set and sorting
        val finalResults = topResults.toList().sortedByDescending { it.score }
        
        // Final sanity check: if we have too much text, prune to avoid LLM context overflow
        var totalChars = 0
        val pruned = finalResults.filter {
            totalChars += it.text.length
            totalChars < 8000 // Cap context at ~2000 tokens
        }
        
        pruned
    }

    private fun calculateNorm(v: FloatArray): Float {
        var norm = 0.0f
        for (x in v) norm += x * x
        return kotlin.math.sqrt(norm.toDouble()).toFloat()
    }

    override suspend fun getStorageUsage(): StorageStats {
        val entities = vectorChunkDao.getAll()
        
        val statFs = android.os.StatFs(context.filesDir.path)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val dynamicLimit = (availableBytes * 0.15).toLong()
        val finalCapacity = maxOf(1024L * 1024 * 1024, dynamicLimit)
        
        return StorageStats(
            usedBytes = entities.size * 2048L,
            totalCapacityBytes = finalCapacity,
            fileCount = entities.size
        )
    }

    override suspend fun clear() {
        vectorChunkDao.clearAll()
    }

    override suspend fun clearSource(source: String) {
        vectorChunkDao.deleteBySource(source)
    }
}

private data class DocumentChunk(
    val id: String,
    val text: String,
    val source: String,
    val embedding: Embedding
)
