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
        val textChunks = TextChunker.chunk(rawText, chunkSize = 800, overlap = 200)
        val totalChunks = textChunks.size
        val chunkEntities = mutableListOf<VectorChunkEntity>()

        try {
            textChunks.forEachIndexed { index, text ->
                val embedding = embeddingEngine.embed(text)
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
                
                if (chunkEntities.size >= 10 || index == totalChunks - 1) {
                    vectorStore.addAll(chunkEntities)
                    chunkEntities.clear()
                    onProgress((index + 1).toFloat() / totalChunks)
                }
            }
        } catch (e: Exception) {
            // Failure rollback: remove partial chunks for this source
            vectorStore.clearSource(source)
            throw e // Re-throw to be handled by caller (ViewModel or Worker)
        }
    }
}

enum class DocType {
    PDF, IMAGE, AUDIO, TXT, WORD
}
