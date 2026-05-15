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
        val entities = try { vectorChunkDao.getAllForModel(modelId) } catch (e: Exception) { 
            android.util.Log.e("LocalVectorStore", "Search failed: ${e.message}")
            emptyList() 
        }
        
        if (entities.isEmpty()) return@withContext emptyList()

        val queryVector = query.vector
        val queryNorm = calculateNorm(queryVector)
        if (queryNorm < 1e-8) return@withContext emptyList()

        // Use a Min-Heap to keep only top-K results efficiently
        val topResults = java.util.PriorityQueue<SearchResult> { a, b -> a.score.compareTo(b.score) }

        for (entity in entities) {
            val vector = try { VectorChunkEntity.toFloatArray(entity.embeddingBlob) } catch (e: Exception) { continue }
            if (vector.size != queryVector.size) continue
            
            val score = calculateFastCosine(queryVector, queryNorm, vector)
            if (score < 0.15f) continue // Ignore very low relevance

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
        
        topResults.toList().sortedByDescending { it.score }
    }

    private fun calculateNorm(v: FloatArray): Float {
        var norm = 0.0f
        for (x in v) norm += x * x
        return kotlin.math.sqrt(norm.toDouble()).toFloat()
    }

    private fun calculateFastCosine(q: FloatArray, qNorm: Float, v: FloatArray): Float {
        var dotProduct = 0.0f
        var vNormSq = 0.0f
        for (i in q.indices) {
            val qi = q[i]
            val vi = v[i]
            dotProduct += qi * vi
            vNormSq += vi * vi
        }
        val vNorm = kotlin.math.sqrt(vNormSq.toDouble()).toFloat()
        val denominator = qNorm * vNorm
        return if (denominator < 1e-8) 0.0f else (dotProduct / denominator)
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
