package com.chhanda.ai.domain.model

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.data.repository.UploadedFileDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextManagerTest {

    private val chatDao = mockk<ChatDao>()
    private val vectorStore = mockk<VectorStore>()
    private val embeddingEngine = mockk<EmbeddingEngine>()
    private val metricsManager = mockk<RAGMetricsManager>(relaxed = true)
    private val uploadedFileDao = mockk<UploadedFileDao>()

    private lateinit var contextManager: ContextManager

    @Before
    fun setup() {
        contextManager = ContextManager(
            chatDao,
            vectorStore,
            embeddingEngine,
            metricsManager,
            uploadedFileDao
        )
    }

    @Test
    fun `getOptimizedContext returns correct history and augmented knowledge`() = runBlocking {
        // Arrange
        val sessionId = "test_session"
        val query = "What is the capital of France?"
        val history = listOf(
            MessageEntity(id = 1, sessionId = sessionId, role = "user", text = "Hello", timestamp = 1000L),
            MessageEntity(id = 2, sessionId = sessionId, role = "assistant", text = "Hi there!", timestamp = 1100L)
        )
        
        coEvery { chatDao.getRecentMessagesForSession(sessionId, 10) } returns history
        coEvery { embeddingEngine.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        coEvery { vectorStore.search(any(), any(), any(), any()) } returns listOf(
            VectorResult(text = "Paris is the capital.", score = 0.9f, metadata = mapOf("source" -> "world_capitals.pdf"))
        )
        coEvery { uploadedFileDao.getDisabledFileNames() } returns emptyList()

        // Act
        val result = contextManager.getOptimizedContext(query, "local", "gemma-4", sessionId)

        // Assert
        assertEquals(2, result.first.size)
        assertEquals("user", result.first[0].first)
        assertEquals("Hello", result.first[0].second)
        assertTrue(result.second.contains("Paris is the capital"))
        assertTrue(result.second.contains("<retrieved_knowledge>"))
    }

    @Test
    fun `getOptimizedContext handles follow-up query augmentation`() = runBlocking {
        // Arrange
        val sessionId = "test_session"
        val query = "What about it?" // Pronoun "it" triggers follow-up augmentation
        val history = listOf(
            MessageEntity(id = 1, sessionId = sessionId, role = "user", text = "Tell me about the Eiffel Tower", timestamp = 1000L),
            MessageEntity(id = 2, sessionId = sessionId, role = "assistant", text = "It's a tall tower in Paris.", timestamp = 1100L)
        )
        
        coEvery { chatDao.getRecentMessagesForSession(sessionId, 10) } returns history
        coEvery { embeddingEngine.embed("Tell me about the Eiffel Tower What about it?") } returns floatArrayOf(0.1f)
        coEvery { vectorStore.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { uploadedFileDao.getDisabledFileNames() } returns emptyList()

        // Act
        contextManager.getOptimizedContext(query, "local", "gemma-4", sessionId)

        // Verify that embeddingEngine was called with the augmented query
        io.mockk.coVerify { embeddingEngine.embed("Tell me about the Eiffel Tower What about it?") }
    }
    
    @Test
    fun `getOptimizedContext respects disabled sources`() = runBlocking {
        // Arrange
        val sessionId = "test_session"
        val query = "Quantum physics"
        
        coEvery { chatDao.getRecentMessagesForSession(sessionId, 10) } returns emptyList()
        coEvery { embeddingEngine.embed(any()) } returns floatArrayOf(0.1f)
        coEvery { vectorStore.search(any(), any(), any(), any()) } returns listOf(
            VectorResult(text = "Secret details.", score = 0.9f, metadata = mapOf("source" -> "confidential.pdf"))
        )
        coEvery { uploadedFileDao.getDisabledFileNames() } returns listOf("confidential.pdf")

        // Act
        val result = contextManager.getOptimizedContext(query, "local", "gemma-4", sessionId)

        // Assert
        assertTrue(result.second.isEmpty())
    }
}
