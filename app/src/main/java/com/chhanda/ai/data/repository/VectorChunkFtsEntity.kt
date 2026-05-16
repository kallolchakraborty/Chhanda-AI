package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * FTS5 Virtual Table for Vector Chunks.
 * Enables lightning-fast keyword search (BM25) for the RAG pipeline.
 */
@Fts4(contentEntity = VectorChunkEntity::class)
@Entity(tableName = "vector_chunks_fts")
data class VectorChunkFtsEntity(
    val text: String
)
