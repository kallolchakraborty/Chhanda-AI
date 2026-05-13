package com.chhanda.ai.domain.model

import android.net.Uri

/**
 * Interface for processing various file types into text chunks for RAG.
 */
interface MultimodalIngestor {
    suspend fun ingestPdf(uri: Uri): List<String>
    suspend fun ingestImage(uri: Uri): String // OCR
    suspend fun ingestAudio(uri: Uri): String // ASR
    suspend fun ingestTxt(uri: Uri): String
    suspend fun ingestWord(uri: Uri): String
    suspend fun ingestExcel(uri: Uri): String
}

/**
 * Represents a document chunk stored in the vector database.
 */
data class DocumentChunk(
    val id: String,
    val text: String,
    val source: String,
    val embedding: Embedding
)
