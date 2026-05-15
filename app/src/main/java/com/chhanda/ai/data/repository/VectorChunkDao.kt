package com.chhanda.ai.data.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VectorChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: VectorChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<VectorChunkEntity>)

    @Query("SELECT * FROM vector_chunks WHERE modelId = :modelId")
    suspend fun getAllForModel(modelId: String): List<VectorChunkEntity>

    @Query("DELETE FROM vector_chunks WHERE modelId = :modelId")
    suspend fun clearAllForModel(modelId: String)

    @Query("SELECT * FROM vector_chunks")
    suspend fun getAll(): List<VectorChunkEntity>

    @Query("SELECT COUNT(*) FROM vector_chunks")
    suspend fun getCount(): Int

    @Query("SELECT SUM(LENGTH(embeddingBlob)) FROM vector_chunks")
    suspend fun getTotalEmbeddingSize(): Long?

    @Query("DELETE FROM vector_chunks WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM vector_chunks")
    suspend fun clearAll()

    @Query("SELECT DISTINCT source FROM vector_chunks LIMIT :limit")
    suspend fun getDistinctSources(limit: Int = 5): List<String>
}
