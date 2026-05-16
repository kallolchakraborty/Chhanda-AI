package com.chhanda.ai.domain.model

data class Embedding(val vector: FloatArray)

data class SearchResult(
    val text: String,
    val score: Float,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Interface for on-device text embedding.
 */
interface EmbeddingEngine {
    suspend fun embed(text: String): Embedding
}

/**
 * Interface for local vector storage and retrieval.
 */
interface VectorStore {
    suspend fun add(text: String, embedding: Embedding, metadata: Map<String, String> = emptyMap(), modelId: String = "default")
    suspend fun addAll(entities: List<com.chhanda.ai.data.repository.VectorChunkEntity>)
    suspend fun search(query: Embedding, topK: Int, modelId: String = "default", queryText: String? = null): List<SearchResult>
    suspend fun getStorageUsage(): StorageStats
    suspend fun clear()
    suspend fun clearSource(source: String)
}

data class StorageStats(
    val usedBytes: Long,
    val totalCapacityBytes: Long,
    val fileCount: Int
)
