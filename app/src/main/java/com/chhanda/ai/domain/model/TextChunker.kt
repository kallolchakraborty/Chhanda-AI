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
        val currentSentences = mutableListOf<String>()
        var currentLength = 0
        
        for (sentence in sentences) {
            // Handle massive sentences
            if (sentence.length > chunkSize) {
                // Flush current chunk first
                if (currentSentences.isNotEmpty()) {
                    chunks.add(currentSentences.joinToString(" "))
                    currentSentences.clear()
                    currentLength = 0
                }
                
                var subStart = 0
                while (subStart < sentence.length) {
                    val subEnd = minOf(subStart + chunkSize, sentence.length)
                    chunks.add(sentence.substring(subStart, subEnd))
                    subStart += chunkSize - overlap
                }
                continue
            }

            if (currentLength + sentence.length <= chunkSize) {
                currentSentences.add(sentence)
                currentLength += sentence.length + 1 // +1 for space
            } else {
                // Save current chunk
                chunks.add(currentSentences.joinToString(" "))
                
                // Backtrack to create overlap
                val overlapSentences = mutableListOf<String>()
                var overlapLength = 0
                for (i in currentSentences.indices.reversed()) {
                    val s = currentSentences[i]
                    if (overlapLength + s.length <= overlap) {
                        overlapSentences.add(0, s)
                        overlapLength += s.length + 1
                    } else break
                }
                
                currentSentences.clear()
                currentSentences.addAll(overlapSentences)
                currentSentences.add(sentence)
                currentLength = currentSentences.sumOf { it.length + 1 }
            }
        }
        
        if (currentSentences.isNotEmpty()) {
            chunks.add(currentSentences.joinToString(" "))
        }
        
        return chunks
    }
}
