package com.chhanda.ai.data.repository

import com.chhanda.ai.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Senior Optimization: Local Vector Store with optimized similarity math.
 */

class LocalVectorStore @javax.inject.Inject constructor(
    private val vectorChunkDao: VectorChunkDao
) : VectorStore {

    override suspend fun add(text: String, embedding: Embedding, metadata: Map<String, String>, modelId: String) {
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

    override suspend fun search(query: Embedding, topK: Int, modelId: String): List<SearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val entities = vectorChunkDao.getAllForModel(modelId)
        entities.map { entity ->
            val vector = VectorChunkEntity.toFloatArray(entity.embeddingBlob)
            val score = calculateCosineSimilarity(query.vector, vector)
            SearchResult(
                text = entity.text, 
                score = score, 
                metadata = mapOf("source" to entity.source, "type" to entity.type)
            )
        }
        .filter { it.score > 0.65f }
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
        return StorageStats(
            usedBytes = entities.size * 2048L,
            totalCapacityBytes = 20 * 1024 * 1024 * 1024L,
            fileCount = entities.size
        )
    }

    override suspend fun clear() {
        vectorChunkDao.clearAll()
    }
}

private data class DocumentChunk(
    val id: String,
    val text: String,
    val source: String,
    val embedding: Embedding
)
