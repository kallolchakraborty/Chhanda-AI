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
    suspend fun search(query: Embedding, topK: Int, modelId: String = "default"): List<SearchResult>
    suspend fun getStorageUsage(): StorageStats
    suspend fun clear()
}

data class StorageStats(
    val usedBytes: Long,
    val totalCapacityBytes: Long,
    val fileCount: Int
)
