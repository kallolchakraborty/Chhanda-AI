package com.chhanda.ai.domain.usecase

import android.net.Uri
import com.chhanda.ai.domain.model.*
import com.chhanda.ai.data.repository.VectorChunkEntity

/**
 * Orchestrates the ingestion of a document into the RAG system.
 */
class IngestDocumentUseCase @javax.inject.Inject constructor(
    private val embeddingEngineLazy: dagger.Lazy<EmbeddingEngine>,
    private val vectorStoreLazy: dagger.Lazy<VectorStore>,
    private val ingestorLazy: dagger.Lazy<MultimodalIngestor>
) {
    private val embeddingEngine get() = embeddingEngineLazy.get()
    private val vectorStore get() = vectorStoreLazy.get()
    private val ingestor get() = ingestorLazy.get()

    suspend operator fun invoke(uri: android.net.Uri, type: DocType, modelId: String = "shared_rag_db", onProgress: (Float) -> Unit = {}) {
        val rawText = when (type) {
            DocType.PDF -> ingestor.ingestPdf(uri).joinToString("\n")
            DocType.IMAGE -> ingestor.ingestImage(uri)
            DocType.AUDIO -> ingestor.ingestAudio(uri)
            DocType.TXT -> ingestor.ingestTxt(uri)
            DocType.WORD -> ingestor.ingestWord(uri)
        }
        processRawText(rawText, uri.toString(), type.name, modelId, onProgress)
    }

    suspend fun ingestScrapedText(text: String, url: String, label: String, onProgress: (Float) -> Unit = {}) {
        processRawText(text, url, "WEB_URL", "shared_rag_db", onProgress)
    }

    private suspend fun processRawText(rawText: String, source: String, type: String, modelId: String, onProgress: (Float) -> Unit) {
        if (rawText.isBlank()) {
            android.util.Log.w("IngestUseCase", "Attempted to ingest empty text from $source")
            throw Exception("No text extracted from source.")
        }

        android.util.Log.d("IngestUseCase", "Starting ingestion for $source (${rawText.length} chars)")
        val textChunks = TextChunker.chunk(rawText, chunkSize = 800, overlap = 200)
        val totalChunks = textChunks.size
        android.util.Log.d("IngestUseCase", "Split into $totalChunks chunks")
        
        val chunkEntities = mutableListOf<VectorChunkEntity>()

        try {
            textChunks.forEachIndexed { index, text ->
                val embedding = try {
                    embeddingEngine.embed(text)
                } catch (e: Exception) {
                    android.util.Log.e("IngestUseCase", "Embedding failed for chunk $index: ${e.message}")
                    throw Exception("Embedding engine failure: ${e.message}")
                }

                chunkEntities.add(
                    VectorChunkEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        modelId = modelId,
                        text = text,
                        source = source,
                        type = type,
                        embeddingBlob = VectorChunkEntity.fromFloatArray(embedding.vector)
                    )
                )
                
                // Batch insert for performance
                if (chunkEntities.size >= 10 || index == totalChunks - 1) {
                    try {
                        vectorStore.addAll(chunkEntities)
                        android.util.Log.v("IngestUseCase", "Inserted batch up to chunk $index")
                    } catch (e: Exception) {
                        android.util.Log.e("IngestUseCase", "Database insertion failed: ${e.message}")
                        throw Exception("Vector store insertion failure: ${e.message}")
                    }
                    chunkEntities.clear()
                    onProgress((index + 1).toFloat() / totalChunks)
                }
            }
            android.util.Log.i("IngestUseCase", "Successfully ingested $source")
        } catch (e: Exception) {
            android.util.Log.e("IngestUseCase", "Ingestion failed for $source: ${e.message}")
            // Failure rollback: remove partial chunks for this source
            vectorStore.clearSource(source)
            throw e 
        }
    }
}

enum class DocType {
    PDF, IMAGE, AUDIO, TXT, WORD
}
