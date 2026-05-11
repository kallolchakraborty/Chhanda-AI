package com.chhanda.ai.data.inference

import android.content.Context
import com.chhanda.ai.domain.model.Embedding
import com.chhanda.ai.domain.model.EmbeddingEngine
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRTEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : EmbeddingEngine {

    private var embedder: TextEmbedder? = null
    private var lastModelPath: String? = null
    private val mutex = Mutex()

    private suspend fun getEmbedder(): TextEmbedder? = mutex.withLock {
        val modelFile = File(context.filesModelsDir(), "embedding-gemma-300m.tflite")
        if (!modelFile.exists()) return null

        if (embedder != null && lastModelPath == modelFile.absolutePath) {
            return embedder
        }

        val options = TextEmbedderOptions.builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(Delegate.GPU)
                    .build()
            )
            .build()

        embedder = TextEmbedder.createFromOptions(context, options)
        lastModelPath = modelFile.absolutePath
        return embedder
    }

    override suspend fun embed(text: String): Embedding = withContext(Dispatchers.Default) {
        val currentEmbedder = getEmbedder()
        if (currentEmbedder == null) {
            // Fallback to simple hash-based vector if model not downloaded
            return@withContext Embedding(generateFallbackEmbedding(text))
        }

        val result = currentEmbedder.embed(text)
        val vector = result.embeddingResult().embeddings()[0].floatEmbedding()
        Embedding(vector)
    }

    private fun generateFallbackEmbedding(text: String): FloatArray {
        val vector = FloatArray(384) { 0f }
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        for (word in words) {
            val hash = Math.abs(word.hashCode()) % 384
            vector[hash] += 1f
        }
        // Normalize
        val norm = Math.sqrt(vector.fold(0.0) { acc, f -> acc + f * f }).toFloat()
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    private fun Context.filesModelsDir(): File {
        val dir = File(filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
