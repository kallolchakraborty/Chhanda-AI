package com.chhanda.ai.data.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VectorChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: VectorChunkEntity)

    @Query("SELECT * FROM vector_chunks WHERE modelId = :modelId")
    suspend fun getAllForModel(modelId: String): List<VectorChunkEntity>

    @Query("DELETE FROM vector_chunks WHERE modelId = :modelId")
    suspend fun clearAllForModel(modelId: String)

    @Query("SELECT * FROM vector_chunks")
    suspend fun getAll(): List<VectorChunkEntity>

    @Query("DELETE FROM vector_chunks")
    suspend fun clearAll()
}
