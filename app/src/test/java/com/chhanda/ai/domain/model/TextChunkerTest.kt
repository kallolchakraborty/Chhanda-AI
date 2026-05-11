package com.chhanda.ai.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TextChunkerTest {

    @Test
    fun testChunking() {
        val text = "1234567890"
        // Chunk size 5, overlap 2
        // Chunk 1: "12345" (0..4)
        // Chunk 2: "45678" (3..7)
        // Chunk 3: "7890" (6..9)
        val chunks = TextChunker.chunk(text, chunkSize = 5, overlap = 2)
        println("Chunks: $chunks")
        assertEquals(3, chunks.size)
        assertEquals("12345", chunks[0])
        assertEquals("45678", chunks[1])
        assertEquals("7890", chunks[2])
    }

    @Test
    fun testEmptyText() {
        val chunks = TextChunker.chunk("", chunkSize = 5, overlap = 2)
        assertEquals(0, chunks.size)
    }

    @Test
    fun testSmallText() {
        val chunks = TextChunker.chunk("123", chunkSize = 5, overlap = 2)
        assertEquals(1, chunks.size)
        assertEquals("123", chunks[0])
    }
    
    @Test
    fun testOverlapLargerThanChunkSize() {
        val chunks = TextChunker.chunk("1234567890", chunkSize = 5, overlap = 6)
        assertEquals(1, chunks.size)
        assertEquals("1234567890", chunks[0])
    }
}
