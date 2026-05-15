package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(
    tableName = "vector_chunks",
    indices = [
        Index(value = ["modelId"]),
        Index(value = ["source"])
    ]
)
data class VectorChunkEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val text: String,
    val source: String,
    val type: String = "TXT",
    val embeddingBlob: ByteArray
) {
    companion object {
        /**
         * SENIOR OPTIMIZATION: Int8 Quantization for embeddings.
         * Reduces disk and RAM footprint by 75% (4 bytes -> 1 byte per dimension).
         * For normalized embeddings (-1 to 1), we scale by 127.
         */
        fun fromFloatArray(array: FloatArray): ByteArray {
            val bytes = ByteArray(array.size)
            for (i in array.indices) {
                // Linear quantization: map [-1.0, 1.0] to [-128, 127]
                val clamped = array[i].coerceIn(-1f, 1f)
                bytes[i] = (clamped * 127f).toInt().toByte()
            }
            return bytes
        }

        fun toFloatArray(bytes: ByteArray): FloatArray {
            val array = FloatArray(bytes.size)
            for (i in bytes.indices) {
                // De-quantization: map [-128, 127] back to [-1.0, 1.0]
                array[i] = bytes[i].toFloat() / 127f
            }
            return array
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VectorChunkEntity

        if (id != other.id) return false
        if (modelId != other.modelId) return false
        if (text != other.text) return false
        if (source != other.source) return false
        if (type != other.type) return false
        if (!embeddingBlob.contentEquals(other.embeddingBlob)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}
