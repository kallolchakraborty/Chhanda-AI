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
 * Enhanced with a cache-aware load balancer with prefix hashing.
 */
@javax.inject.Singleton
class SendMessageUseCase @javax.inject.Inject constructor(
    private val llmEngineLazy: dagger.Lazy<com.chhanda.ai.domain.model.LLMEngine>,
    private val chatDao: com.chhanda.ai.data.repository.ChatDao,
    private val contextManager: com.chhanda.ai.domain.model.ContextManager
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val loadBalancer = LoadBalancer(numReplicas = 1) // Default to 1 replica for Android constraints

    operator fun invoke(
        userText: String, 
        deviceId: String, 
        modelName: String, 
        sessionId: String, 
        attachments: List<android.net.Uri> = emptyList(), 
        preferredLanguage: String = "English",
        externalHistory: List<Pair<String, String>>? = null
    ): kotlinx.coroutines.flow.Flow<com.chhanda.ai.domain.model.TokenUpdate> = kotlinx.coroutines.flow.flow {
        val replica = loadBalancer.getReplica(userText)
        android.util.Log.d("LoadBalancer", "Routed request to replica: $replica")
        
        var saved = false
        var partialAccumulated = ""
        var isContextFound = false
        
        try {
            // STEP 1: Get Optimized Context (Short-term + Long-term)
            val (dbHistory, longTermContext) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)
            val history = externalHistory ?: dbHistory
            isContextFound = longTermContext.isNotBlank()
            


            // STEP 2: Save User Turn
            chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = userText, role = "user", deviceId = deviceId, modelName = modelName, sessionId = sessionId))

            // STEP 3: Session Management & Prompt Construction
            if (history.isEmpty()) {
                llmEngine.resetSession()
            }

            // Defensive: Check for direct prompt injection
            if (com.chhanda.ai.util.SafetyUtil.isPotentialInjection(userText)) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected. Your request cannot be processed."))
                return@flow
            }
            
            // Wrap user input and context in defensive delimiters
            val sanitizedUserText = com.chhanda.ai.util.SafetyUtil.sanitizeInput(userText)
            
            // ORCHESTRATION: Construct the Final RAG-augmented Prompt
            val prompt = if (isContextFound) {
                if (modelName.contains("4B")) {
                    "CONTEXT:\n$longTermContext\n\nQUERY: $sanitizedUserText"
                } else {
                    """
                    DOCUMENT_CONTEXT_START
                    $longTermContext
                    DOCUMENT_CONTEXT_END
                    
                    Please answer the following user query. Use the documentation above as your primary source if it contains the answer. If the information is not in the documentation, use your own general knowledge to answer accurately.
                    
                    $sanitizedUserText
                    """.trimIndent()
                }
            } else {
                sanitizedUserText
            }

            val systemInstruction = if (isContextFound) {
                if (modelName.contains("4B")) {
                    "You are a helpful assistant. Use the provided CONTEXT to answer the QUERY. If the CONTEXT doesn't contain the answer, use your pre-trained knowledge to respond accurately and quickly in $preferredLanguage."
                } else {
                    """
                        You are a helpful and accurate RAG assistant.
    
                        Your job is to answer using the provided documentation context first.
                        - If the information is in the documentation, use it as your primary source.
                        - If the information is not in the documentation, use your own internal knowledge to answer the question accurately.
                        - Do not invent facts or hallucinate if you do not know the answer.
                        - Answer directly and concisely.
    
                        CONSTRAINTS:
                        - RESPONSE LANGUAGE: $preferredLanguage
                    """.trimIndent()
                }
            } else {
                """
                    You are a helpful and accurate assistant.
                    Your job is to answer the user's question accurately using your internal knowledge in $preferredLanguage.
                    - Answer directly and concisely.
                """.trimIndent()
            }

            // STEP 4: Streamed Generation — emit tokens as they arrive
            // Senior Implementation: Filter out <thought> and <think> blocks at the source
            var isThinking = false
            var internalBuffer = ""
            
            llmEngine.generateResponse(prompt, history, systemInstruction, attachments).collect { update ->
                when (update) {
                    is com.chhanda.ai.domain.model.TokenUpdate.Partial -> {
                        internalBuffer += update.text
                        
                        while (true) {
                            if (!isThinking) {
                                val startIdx = internalBuffer.indexOf("<thought>")
                                val startIdxAlt = if (startIdx == -1) internalBuffer.indexOf("<think>") else -1
                                val finalStartIdx = if (startIdx != -1) startIdx else startIdxAlt
                                val markerLen = if (startIdx != -1) 9 else 7

                                if (finalStartIdx != -1) {
                                    val before = internalBuffer.substring(0, finalStartIdx)
                                    if (before.isNotEmpty()) {
                                        emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(before, update.tps))
                                        partialAccumulated += before
                                    }
                                    isThinking = true
                                    internalBuffer = internalBuffer.substring(finalStartIdx + markerLen)
                                } else {
                                    // Send safe portion
                                    if (internalBuffer.length > 10) {
                                        val toSend = internalBuffer.substring(0, internalBuffer.length - 10)
                                        emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(toSend, update.tps))
                                        partialAccumulated += toSend
                                        internalBuffer = internalBuffer.substring(internalBuffer.length - 10)
                                    }
                                    break
                                }
                            } else {
                                val endIdx = internalBuffer.indexOf("</thought>")
                                val endIdxAlt = if (endIdx == -1) internalBuffer.indexOf("</think>") else -1
                                val finalEndIdx = if (endIdx != -1) endIdx else endIdxAlt
                                val markerLen = if (endIdx != -1) 10 else 8

                                if (finalEndIdx != -1) {
                                    isThinking = false
                                    internalBuffer = internalBuffer.substring(finalEndIdx + markerLen)
                                } else {
                                    if (internalBuffer.length > 11) {
                                        internalBuffer = internalBuffer.substring(internalBuffer.length - 11)
                                    }
                                    break
                                }
                            }
                        }
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Final -> {
                        if (internalBuffer.isNotEmpty() && !isThinking) {
                            partialAccumulated += internalBuffer
                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(internalBuffer, update.tps))
                        }
                        var toSave = partialAccumulated.trim()
                        
                        // Robustly strip common thinking prefixes that models sometimes output literally
                        val prefixesToStrip = listOf("Thinking...", "Thinking:", "Thought:", "Thought...", "<thought>", "<think>")
                        var cleaned = toSave
                        var changed = true
                        while (changed) {
                            changed = false
                            for (prefix in prefixesToStrip) {
                                if (cleaned.startsWith(prefix, ignoreCase = true)) {
                                    cleaned = cleaned.substring(prefix.length).trim()
                                    changed = true
                                }
                            }
                        }
                        toSave = cleaned

                        if (toSave.isNotBlank()) {
                            chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = toSave, role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, tps = update.tps, isRagUsed = isContextFound, responseTimeMs = update.responseTimeMs))
                            saved = true
                            contextManager.maintainMemoryHygiene()
                        }
                        emit(update)
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Error -> {
                        emit(update)
                    }
                }
            }
        } catch (e: Throwable) {
            emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Generation failure: ${e.localizedMessage}"))
        } finally {
            loadBalancer.releaseReplica(replica)
            // Safety net: if cancelled mid-stream, save whatever was generated
            if (!saved && partialAccumulated.trim().isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = partialAccumulated.trim(), role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, isRagUsed = isContextFound))
                    contextManager.maintainMemoryHygiene()
                }
            }
        }
    }
}

/**
 * Cache-aware load balancer with prefix hashing.
 * Combines locality (prompt affinity) with fairness (load bound).
 */
private class LoadBalancer(private val numReplicas: Int = 1) {
    private val replicaLoads = mutableMapOf<Int, Int>()
    private val maxLoadBound = 2 // Max concurrent requests per replica
    
    fun getReplica(prompt: String): Int {
        val prefix = prompt.take(50) // Take first 50 chars as prefix for cache locality
        val hash = prefix.hashCode()
        val preferredReplica = Math.abs(hash) % numReplicas
        
        synchronized(this) {
            val currentLoad = replicaLoads[preferredReplica] ?: 0
            if (currentLoad < maxLoadBound) {
                replicaLoads[preferredReplica] = currentLoad + 1
                return preferredReplica
            }
            
            // Spill over to least loaded replica
            val leastLoaded = replicaLoads.minByOrNull { it.value }?.key ?: 0
            val leastLoad = replicaLoads[leastLoaded] ?: 0
            if (leastLoad < maxLoadBound) {
                replicaLoads[leastLoaded] = leastLoad + 1
                return leastLoaded
            }
            
            // If all are full, we still use the preferred one or the least loaded
            // Let's just use the least loaded and increment it (allow queueing)
            replicaLoads[leastLoaded] = leastLoad + 1
            return leastLoaded
        }
    }
    
    fun releaseReplica(replica: Int) {
        synchronized(this) {
            val currentLoad = replicaLoads[replica] ?: 0
            if (currentLoad > 0) {
                replicaLoads[replica] = currentLoad - 1
            }
        }
    }
}
