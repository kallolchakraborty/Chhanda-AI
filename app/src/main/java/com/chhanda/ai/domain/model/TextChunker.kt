package com.chhanda.ai.domain.model

object TextChunker {
    /**
     * Splits text into chunks by attempting to break at sentence boundaries.
     * Follows the 'Semantic Chunking' pattern from the System Design RAG tutorial.
     */
    fun chunk(text: String, chunkSize: Int = 800, overlap: Int = 100): List<String> {
        if (text.isBlank()) return emptyList()
        
        // Split into sentences using common punctuation
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        
        for (sentence in sentences) {
            if (currentChunk.length + sentence.length <= chunkSize) {
                currentChunk.append(sentence).append(" ")
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                }
                // Handle cases where a single sentence is longer than chunkSize
                if (sentence.length > chunkSize) {
                    var subStart = 0
                    while (subStart < sentence.length) {
                        val subEnd = minOf(subStart + chunkSize, sentence.length)
                        chunks.add(sentence.substring(subStart, subEnd))
                        subStart += chunkSize - overlap
                    }
                    currentChunk = StringBuilder()
                } else {
                    // Start new chunk with the sentence
                    currentChunk = StringBuilder(sentence).append(" ")
                }
            }
        }
        
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }
        
        return chunks
    }
}
