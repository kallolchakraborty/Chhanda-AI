package com.chhanda.ai.domain.model

object TextChunker {
    /**
     * Splits text into chunks of chunkSize with overlap.
     * Optimized for mobile: avoids large allocations and handles edge cases.
     */
    fun chunk(text: String, chunkSize: Int = 500, overlap: Int = 50): List<String> {
        if (text.isBlank()) return emptyList()
        if (chunkSize <= 0) return listOf(text)
        if (overlap >= chunkSize) return listOf(text) // Avoid infinite loop
        
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            if (end == text.length) break
            start += chunkSize - overlap
        }
        return chunks
    }
}
