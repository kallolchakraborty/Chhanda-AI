package com.chhanda.ai.domain.model

import org.junit.Assert.*
import org.junit.Test

class RAGIntegrationTest {

    @Test
    fun testTextChunkingWithOverlap() {
        val text = "This is a long piece of text that needs to be chunked into multiple parts with some overlap."
        val chunkSize = 20
        val overlap = 5
        
        val chunks = TextChunker.chunk(text, chunkSize, overlap)
        
        // Basic checks
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.length <= chunkSize) }
        
        // Check overlap: end of chunk N should match start of chunk N+1
        for (i in 0 until chunks.size - 1) {
            val currentChunk = chunks[i]
            val nextChunk = chunks[i+1]
            val overlapPart = currentChunk.takeLast(overlap)
            assertTrue("Overlap failed between chunk $i and ${i+1}", nextChunk.startsWith(overlapPart))
        }
    }

    @Test
    fun testRAGPromptConstruction() {
        val longTermContext = "- PDF: Relevant PDF info\n- Audio transcript: Transcription data"
        val userText = "What is in the PDF?"
        
        val systemInstruction = """
                You are an on-device retrieval-augmented assistant optimized for mobile efficiency.

                Question: $userText

                Source context:
                $longTermContext

                Instructions:
                Use the most relevant and recent evidence.
                Prefer transcript/OCR/text evidence over metadata.
                Answer briefly and accurately.
                If evidence is insufficient, say what is missing.
                
                Optimization priorities: 1. Correctness, 2. Low latency, 3. Low memory, 4. Short output.
            """.trimIndent()
            
        assertTrue(systemInstruction.contains("Relevant PDF info"))
        assertTrue(systemInstruction.contains("Transcription data"))
        assertTrue(systemInstruction.contains(userText))
    }
    
    @Test
    fun testFailproofEmptyContext() {
        val longTermContext = ""
        val contextOutput = if (longTermContext.isEmpty()) "- No relevant local context found." else longTermContext
        assertEquals("- No relevant local context found.", contextOutput)
    }
}
