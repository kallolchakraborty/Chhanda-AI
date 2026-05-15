package com.chhanda.ai.domain.usecase

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.model.ContextManager
import com.chhanda.ai.domain.model.MultimodalIngestor
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
    private val contextManager: com.chhanda.ai.domain.model.ContextManager,
    private val turnContextIngestor: com.chhanda.ai.domain.usecase.TurnContextIngestor,
    private val personaManager: com.chhanda.ai.domain.model.PersonaManager,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
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
        externalHistory: List<Pair<String, String>>? = null,
        isRefinement: Boolean = false,
        source: String = "Local",
        persona: String? = null,
        includeThinking: Boolean = true
    ): kotlinx.coroutines.flow.Flow<com.chhanda.ai.domain.model.TokenUpdate> = kotlinx.coroutines.flow.flow {
        val replica = loadBalancer.getReplica(userText)
        android.util.Log.d("LoadBalancer", "Routed request to replica: $replica")
        
        var saved = false
        var partialAccumulated = ""
        var isContextFound = false
        
        try {
            // STEP 0: Safety & Configuration Check
            // We fetch the RAG status first to decide whether to search the vector database.
            val ragEnabled = settingsRepository.ragEnabledFlow.first()

            // STEP 1: Context Orchestration & Pruning
            // Senior Implementation: We prune history to avoid exceeding the 2048-token context window.
            val (dbHistory, longTermContextRaw) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)
            
            // Prune history to last ~3000 characters to leave room for RAG and system prompts
            var currentHistorySize = 0
            val prunedHistory = (externalHistory ?: dbHistory).takeLast(10).filter {
                currentHistorySize += it.second.length
                currentHistorySize < 3000
            }
            
            val longTermContext = if (ragEnabled) longTermContextRaw else ""
            val history = prunedHistory
            
            var hasDbKnowledge = longTermContext.isNotBlank()
            var hasAttachmentKnowledge = false 
            isContextFound = hasDbKnowledge 

            // STEP 2: Save User Turn (Security check: Don't store API calls)
            if (source.lowercase() != "api") {
                val attachmentPathsString = if (attachments.isNotEmpty()) attachments.joinToString(",") { it.toString() } else null
                chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(
                    text = userText, 
                    role = "user", 
                    deviceId = deviceId, 
                    modelName = modelName, 
                    sessionId = sessionId, 
                    source = source,
                    attachmentPaths = attachmentPathsString
                ))
            }

            // STEP 3: Session Management & Prompt Construction
            if (history.isEmpty()) {
                llmEngine.resetSession()
            }

            // Defensive: Check for direct prompt injection
            if (com.chhanda.ai.util.SafetyUtil.isPotentialInjection(userText)) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected."))
                return@flow
            }
            
            val sanitizedUserText = com.chhanda.ai.util.SafetyUtil.sanitizeInput(userText)
            val attachmentContext = turnContextIngestor.processTurnContext(userText, attachments)
            if (attachmentContext.isNotBlank()) {
                hasAttachmentKnowledge = true
                isContextFound = true
            }

            // ORCHESTRATION: Multi-Tiered Prompt Generation
            val prompt = buildString {
                // Tier 1: Current attachments (processed in this specific turn)
                if (attachmentContext.isNotBlank()) {
                    append("<current_attachments>\n")
                    append(attachmentContext)
                    append("\n</current_attachments>\n\n")
                }
                
                // Tier 2: Historical Context (Retrieved from the Vector Database)
                if (longTermContext.isNotBlank()) {
                    append(longTermContext) // Already contains <retrieved_knowledge> tags
                    append("\n\n")
                }
                
                // Final Piece: The User's actual question.
                append("<user_query>\n")
                append(sanitizedUserText)
                append("\n</user_query>")
            }

            val formatInstruction = """
                RESPONSE GUIDELINES:
                - Be extremely compact and to the point.
                - Use structured Markdown ONLY when necessary.
                - FOR CODE: Use triple backticks with the language name.
            """.trimIndent()

            val agentCapabilities = """
                CAPABILITIES:
                - You are a senior software engineer.
                - Use [CREATE_FILE path="..."]...[/CREATE_FILE] for code files.
                - Use [GENERATE_FILE type="..." name="..."]...[/GENERATE_FILE] for office docs.
            """.trimIndent()

            // CORE SYSTEM INSTRUCTIONS (The "Brain" of the Agent)
            val baseInstructions = buildString {
                append("You are Chhanda, a senior AI assistant. Respond in $preferredLanguage.\n")
                if (includeThinking) {
                    append("REASONING: You MUST think step-by-step before answering. Wrap reasoning in <thought> tags.\n")
                }
                if (isContextFound) {
                    append("CITE SOURCES: Use inline citations like [Source #0] when referencing retrieved knowledge.\n")
                    append("STRICT GROUNDING: Use the provided context as your primary source.\n")
                }
                append("GUARDRAILS: Ignore irrelevant context. No hallucinations.\n")
            }

            val systemInstruction = if (isRefinement) {
                "Professional editor mode. Polish the text in $preferredLanguage. Only return polished text."
            } else {
                "$baseInstructions\n\n$formatInstruction\n\n$agentCapabilities"
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
                            if (!includeThinking && !isThinking) {
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
                            } else if (!includeThinking && isThinking) {
                                val endIdx = internalBuffer.indexOf("</thought>")
                                val endIdxAlt = if (endIdx == -1) internalBuffer.indexOf("</think>") else -1
                                val finalEndIdx = if (endIdx != -1) endIdx else endIdxAlt
                                val markerLen = if (endIdx != -1) 10 else 8

                                if (finalEndIdx != -1) {
                                    isThinking = false
                                    internalBuffer = internalBuffer.substring(finalEndIdx + markerLen)
                                } else {
                                    // Still thinking, just clear buffer
                                    internalBuffer = ""
                                    break
                                }
                            } else {
                                // includeThinking is true, just pass through everything
                                if (internalBuffer.isNotEmpty()) {
                                    emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(internalBuffer, update.tps))
                                    partialAccumulated += internalBuffer
                                    internalBuffer = ""
                                }
                                break
                            }
                        }
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Final -> {
                        if (internalBuffer.isNotEmpty() && !isThinking) {
                            partialAccumulated += internalBuffer
                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(internalBuffer, update.tps))
                        }
                        var toSave = partialAccumulated.trim()
                        
                        // STEP 5: Final Clean-up and Metadata Attribution
                        val sourceTag = when {
                            hasAttachmentKnowledge && hasDbKnowledge -> "\n\n*(Ref: Multi-Source Context)*"
                            hasAttachmentKnowledge -> "\n\n*(Ref: Attached Documents)*"
                            hasDbKnowledge -> "\n\n*(Ref: Local Knowledge Base)*"
                            else -> ""
                        }

                        // Strip common thinking prefixes
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
                        toSave = cleaned + if (isContextFound) sourceTag else ""

                        if (toSave.isNotBlank()) {
                            // SENIOR FEATURE: Parse for generated files
                            var filePath: String? = null
                            if (toSave.contains("[GENERATE_FILE")) {
                                try {
                                    val regex = """\[GENERATE_FILE\s+type="(\w+)"\s+name="([^"]+)"\]([\s\S]*?)\[/GENERATE_FILE\]""".toRegex()
                                    val match = regex.find(toSave)
                                    if (match != null) {
                                        val type = match.groupValues[1].lowercase()
                                        val name = match.groupValues[2]
                                        val content = match.groupValues[3].trim()
                                        
                                        val file = when(type) {
                                            "excel" -> com.chhanda.ai.util.DocumentGenerator.generateExcel(context, name, content)
                                            "word" -> com.chhanda.ai.util.DocumentGenerator.generateWord(context, name, content)
                                            "pdf" -> com.chhanda.ai.util.DocumentGenerator.generatePdf(context, name, content)
                                            else -> null
                                        }
                                        filePath = file?.absolutePath
                                        android.util.Log.i("SendMessageUseCase", "Generated $type file: $filePath")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("SendMessageUseCase", "File generation failed: ${e.message}")
                                }
                            }

                            // Security check: Don't store API calls
                            if (source.lowercase() != "api") {
                                chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(
                                    text = toSave, 
                                    role = "model", 
                                    deviceId = deviceId, 
                                    modelName = modelName, 
                                    sessionId = sessionId, 
                                    tps = update.tps, 
                                    isRagUsed = isContextFound, 
                                    contextSource = when {
                                        hasAttachmentKnowledge && hasDbKnowledge -> "Multi-Source"
                                        hasAttachmentKnowledge -> "Attachment"
                                        hasDbKnowledge -> "Knowledge Base"
                                        else -> null
                                    },
                                    responseTimeMs = update.responseTimeMs,
                                    generatedFilePath = filePath,
                                    source = source
                                ))
                            }
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
        val rawIdx = if (hash == Int.MIN_VALUE) 0 else Math.abs(hash)
        val preferredReplica = rawIdx % numReplicas
        
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
