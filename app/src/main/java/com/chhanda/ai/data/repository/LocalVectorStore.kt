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
    override suspend fun search(query: Embedding, topK: Int, modelId: String): List<SearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val entities = try { vectorChunkDao.getAllForModel(modelId) } catch (e: Exception) { 
            android.util.Log.e("LocalVectorStore", "Search failed: ${e.message}")
            emptyList() 
        }
        
        if (entities.isEmpty()) return@withContext emptyList()

        val queryVector = query.vector
        // PRE-COMPUTE: Normalization of query vector outside the loop to avoid O(N) redundant sqrt calls
        val queryNorm = calculateNorm(queryVector)
        if (queryNorm < 1e-8) return@withContext emptyList()

        /** 
         * Senior Optimization: PriorityQueue (Min-Heap) used to track top results.
         * Size is capped at topK + 1 to ensure complexity of O(N log K) where N is number of chunks.
         * This avoids sorting the entire database.
         */
        val topResults = java.util.PriorityQueue<SearchResult>(topK + 1) { a, b -> a.score.compareTo(b.score) }

        for (entity in entities) {
            val vectorBytes = entity.embeddingBlob
            if (vectorBytes.size != queryVector.size) continue
            
            // STEP 2: Fast Quantized Dot Product Calculation
            // Senior Optimization: We work directly with bytes to avoid de-quantization overhead.
            // Result is scaled back to float at the end of the loop.
            var dotProductInt = 0
            var vNormSqInt = 0
            for (i in queryVector.indices) {
                val qi = (queryVector[i] * 127f).toInt()
                val vi = vectorBytes[i].toInt()
                dotProductInt += qi * vi
                vNormSqInt += vi * vi
            }
            
            // STEP 3: Scale back and compute Cosine Similarity
            // Normalize: (A·B) / (||A|| * ||B||)
            // Since we scaled by 127, the product was scaled by 127*127.
            // However, the division (dotProductInt / sqrt(vNormSqInt)) cancels out one 127.
            // We then divide by queryNorm (which is in float space).
            val vNorm = kotlin.math.sqrt(vNormSqInt.toDouble()).toFloat()
            val score = if (vNorm > 1e-8) (dotProductInt.toFloat() / (queryNorm * 127f * vNorm)) else 0.0f
            
            // HEURISTIC: Reject noise.
            if (score < 0.20f) continue 

            val result = SearchResult(
                text = entity.text, 
                score = score, 
                metadata = mapOf("source" to entity.source, "type" to entity.type)
            )
            
            // Maintain heap property
            topResults.add(result)
            if (topResults.size > topK) {
                topResults.poll() // Remove lowest score to keep only top-K
            }
        }
        
        topResults.toList().sortedByDescending { it.score }
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
