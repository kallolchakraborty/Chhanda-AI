package com.chhanda.ai.domain.usecase

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.model.ContextManager
import kotlinx.coroutines.flow.*

/**
 * Senior Architect Level Orchestrator for RAG-based AI communication.
 * Refactored to use dedicated ContextManager for memory optimization.
 */
@javax.inject.Singleton
class SendMessageUseCase @javax.inject.Inject constructor(
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val chatDao: ChatDao,
    private val contextManager: ContextManager
) {
    private val llmEngine get() = llmEngineLazy.get()
    /**
     * Executes the RAG-augmented generation pipeline.
     */
    operator fun invoke(userText: String, deviceId: String, modelName: String, sessionId: String, attachments: List<android.net.Uri> = emptyList(), preferredLanguage: String = "English"): Flow<TokenUpdate> = flow {
        // STEP 1: Get Optimized Context (Short-term + Long-term)
        val (history, longTermContext) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)

        // STEP 2: Save User Turn
        chatDao.insertMessage(MessageEntity(text = userText, role = "user", deviceId = deviceId, modelName = modelName, sessionId = sessionId))

        // STEP 3: Session Management & Prompt Construction
        if (history.isEmpty()) {
            llmEngine.resetSession()
        }
        val isSessionActive = llmEngine.isSessionActive()
        
        // Use userText directly, since litertlm Conversation handles formatting natively
        val prompt = userText

        val systemInstruction = buildString {
            append("You are a helpful AI assistant. Answer directly without thinking tags.\n")
            if (preferredLanguage != "English") {
                append("IMPORTANT: Always respond in $preferredLanguage.\n")
            }
            if (longTermContext.isNotEmpty()) {
                append("\nContext:\n$longTermContext\n")
            }
        }

        // STEP 4: Streamed Generation — emit tokens as they arrive
        var partialAccumulated = ""  // tracks streaming partials for UI
        var saved = false
        try {
            llmEngine.generateResponse(prompt, history, systemInstruction, attachments).collect { update ->
                emit(update)
                when (update) {
                    is TokenUpdate.Partial -> partialAccumulated += update.text
                    is TokenUpdate.Final -> {
                        // Always use the Final text — it is already cleaned by the engine.
                        // Fall back to partialAccumulated if Final text is empty (shouldn't happen).
                        val toSave = update.fullText.ifBlank { partialAccumulated }.trim()
                        if (toSave.isNotBlank()) {
                            chatDao.insertMessage(MessageEntity(text = toSave, role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, tps = update.tps))
                            saved = true
                            contextManager.maintainMemoryHygiene()
                        }
                    }
                    is TokenUpdate.Error -> { /* Error is emitted; nothing to save */ }
                }
            }
        } catch (e: Throwable) {
            emit(TokenUpdate.Error("Generation failure: ${e.localizedMessage}"))
        } finally {
            // Safety net: if cancelled mid-stream, save whatever was generated
            if (!saved && partialAccumulated.trim().isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    chatDao.insertMessage(MessageEntity(text = partialAccumulated.trim(), role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId))
                    contextManager.maintainMemoryHygiene()
                }
            }
        }
    }
}
