package com.chhanda.ai.domain.usecase

import android.net.Uri
import com.chhanda.ai.domain.model.*
import com.chhanda.ai.data.repository.VectorChunkEntity

/**
 * Orchestrates the ingestion of a document into the RAG system.
 * Updated with Production Metrics for Throughput and Indexing Efficiency.
 */
class IngestDocumentUseCase @javax.inject.Inject constructor(
    private val embeddingEngineLazy: dagger.Lazy<EmbeddingEngine>,
    private val vectorStoreLazy: dagger.Lazy<VectorStore>,
    private val ingestorLazy: dagger.Lazy<MultimodalIngestor>,
    private val metricsManager: RAGMetricsManager
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
            DocType.EXCEL -> ingestor.ingestExcel(uri)
            DocType.JSON -> ingestor.ingestJson(uri)
            DocType.CSV -> ingestor.ingestCsv(uri)
        }
        processRawText(rawText, uri.toString(), type.name, modelId, onProgress)
    }

    suspend fun ingestScrapedText(text: String, url: String, label: String, docType: String = "WEB_URL", onProgress: (Float) -> Unit = {}) {
        processRawText(text, url, docType, "shared_rag_db", onProgress)
    }

    private suspend fun processRawText(rawText: String, source: String, type: String, modelId: String, onProgress: (Float) -> Unit) {
        if (rawText.isBlank()) return

        val textChunks = TextChunker.chunk(rawText, chunkSize = 1200, overlap = 250)
        val totalChunks = textChunks.size
        
        val chunkEntities = mutableListOf<VectorChunkEntity>()
        val sourceLabel = source.substringAfterLast("/").substringAfterLast("\\").ifBlank { source.substringAfter("://").take(30) } 

        try {
            textChunks.forEachIndexed { index, text ->
                val contextualText = "### SOURCE: $sourceLabel ($type) ###\n\n$text"
                
                val embedding = try {
                    embeddingEngine.embed(contextualText)
                } catch (e: Exception) {
                    throw Exception("Embedding engine failure: ${e.message}")
                }

                chunkEntities.add(
                    VectorChunkEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        modelId = modelId,
                        text = contextualText, 
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
            
            // Record production metrics
            metricsManager.recordIngest(totalChunks)
            
        } catch (e: Exception) {
            try { vectorStore.clearSource(source) } catch (inner: Exception) {}
            throw e 
        }
    }
}

enum class DocType {
    PDF, IMAGE, AUDIO, TXT, WORD, EXCEL, JSON, CSV
}
