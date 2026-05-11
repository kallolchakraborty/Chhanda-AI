package com.chhanda.ai.domain.usecase

import android.net.Uri
import com.chhanda.ai.domain.model.*

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

    suspend operator fun invoke(uri: Uri, type: DocType, modelId: String = "default") {
        val rawText = when (type) {
            DocType.PDF -> ingestor.ingestPdf(uri).joinToString("\n")
            DocType.IMAGE -> ingestor.ingestImage(uri)
            DocType.AUDIO -> ingestor.ingestAudio(uri)
            DocType.TXT -> ingestor.ingestTxt(uri)
            DocType.WORD -> ingestor.ingestWord(uri)
        }

        val textChunks = TextChunker.chunk(rawText)

        textChunks.forEach { text ->
            val embedding = embeddingEngine.embed(text)
            vectorStore.add(
                text = text,
                embedding = embedding,
                metadata = mapOf("source" to uri.toString(), "type" to type.name),
                modelId = modelId
            )
        }
    }
}

enum class DocType {
    PDF, IMAGE, AUDIO, TXT, WORD
}
