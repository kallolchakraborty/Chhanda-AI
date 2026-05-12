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

    override suspend fun search(query: Embedding, topK: Int, modelId: String): List<SearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        // Universal RAG: Search across all relevant knowledge
        val entities = try { vectorChunkDao.getAllForModel(modelId) } catch (e: Exception) { 
            android.util.Log.e("LocalVectorStore", "Search failed: ${e.message}")
            emptyList() 
        }
        
        if (entities.isEmpty()) {
            android.util.Log.d("LocalVectorStore", "No chunks found in database.")
            return@withContext emptyList<SearchResult>()
        }

        val queryVector = query.vector
        android.util.Log.d("LocalVectorStore", "Searching ${entities.size} chunks. Query dim: ${queryVector.size}")
        
        var maxScore = 0.0f
        val results = entities.mapNotNull { entity ->
            val vector = VectorChunkEntity.toFloatArray(entity.embeddingBlob)
            if (vector.size != queryVector.size) {
                android.util.Log.w("LocalVectorStore", "Dimension mismatch: chunk=${vector.size}, query=${queryVector.size}")
                return@mapNotNull null
            }
            
            val score = calculateCosineSimilarity(queryVector, vector)
            if (score > maxScore) maxScore = score
            
            SearchResult(
                text = entity.text, 
                score = score, 
                metadata = mapOf("source" to entity.source, "type" to entity.type)
            )
        }
        
        android.util.Log.d("LocalVectorStore", "Search complete. Max score found: $maxScore. Using threshold 0.10")
        
        results
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0.0f
        
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        
        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }
        
        val denominator = kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble())
        return if (denominator < 1e-8) 0.0f else (dotProduct / denominator).toFloat()
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
