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
        fun fromFloatArray(array: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in array) {
                buffer.putFloat(f)
            }
            return buffer.array()
        }

        fun toFloatArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val array = FloatArray(bytes.size / 4)
            for (i in array.indices) {
                array[i] = buffer.float
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
