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

            // STEP 1: Context Orchestration
            // The ContextManager fetches:
            // 1. dbHistory: Recent chat turns for short-term memory.
            // 2. longTermContextRaw: Relevant snippets from the vector database.
            val (dbHistory, longTermContextRaw) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)
            
            // Only include long-term context if RAG is globally enabled in settings.
            val longTermContext = if (ragEnabled) longTermContextRaw else ""
            val history = externalHistory ?: dbHistory
            
            // Track metadata about what kind of knowledge we have for this turn.
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
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected. Your request cannot be processed."))
                return@flow
            }
            
            // Wrap user input and context in defensive delimiters
            val sanitizedUserText = com.chhanda.ai.util.SafetyUtil.sanitizeInput(userText)
            
            // STEP 3: Process Attachments & Detect URLs (Refactored)
            val attachmentContext = turnContextIngestor.processTurnContext(userText, attachments)
            if (attachmentContext.isNotBlank()) {
                hasAttachmentKnowledge = true
                isContextFound = true
            }

            // ORCHESTRATION: Multi-Tiered Prompt Generation (Refactored)
            val prompt = buildString {
                append(personaManager.getSystemPrompt(persona, source))

                // Tier 1: Immediate Context (Files/URLs processed in this specific turn)
                if (attachmentContext.isNotBlank()) {
                    append("### [URGENT] TIER 1: CURRENT_ATTACHMENTS\n")
                    append(attachmentContext)
                    append("\n--- END OF CURRENT ATTACHMENTS ---\n\n")
                }
                
                // Tier 2: Historical Context (Retrieved from the Vector Database)
                if (longTermContext.isNotBlank()) {
                    append("### TIER 2: DATABASE_KNOWLEDGE_CONTEXT\n")
                    append(longTermContext)
                    append("\n\n")
                }
                
                // Final Piece: The User's actual question.
                append("### USER_QUERY\n")
                append(sanitizedUserText)
                
                // Guardrails: We provide strict instructions to minimize hallucinations.
                append("\n\n### CRITICAL INSTRUCTIONS\n")
                
                if (includeThinking) {
                    append("0. REASONING MODE: You MUST think step-by-step before answering. Wrap your internal reasoning inside <thought> tags. Focus on logic, edge cases, and user intent.\n")
                }
                
                append("1. STRICT RELEVANCE: Only use TIER 1 or TIER 2 context if it DIRECTLY and SPECIFICALLY answers the query. If the context is about a different topic, ignore it completely.\n")
                append("2. ATTACHMENT PRIORITY: If TIER 1 contains the answer, use it and STOP searching.\n")
                append("3. NO FORCED ANSWERS: If information is missing from context, admit it instead of making things up.\n")
                append("4. SMALL TALK: If the query is greetings or small talk, IGNORE all context and respond naturally.\n")
                append("5. LANGUAGE: Respond in $preferredLanguage.\n")
            }

            val formatInstruction = """
                
                RESPONSE GUIDELINES:
                - Be extremely compact and to the point. Avoid conversational filler or meta-commentary.
                - Use structured Markdown ONLY when necessary for clarity (e.g., code blocks, short lists).
                - FOR CODE: Use triple backticks with the language name. Ensure it is clean and production-ready.
                - NO UNNECESSARY HEADINGS: Do not use complex hierarchical structures unless the topic is highly complex. Prefer direct paragraphs or bullet points.
                - Use bold text (**word**) sparingly for critical emphasis only.
            """.trimIndent()

            val agentCapabilities = """
                CODING_AGENT_CAPABILITIES:
                - You are a senior software engineer.
                - When asked to create or write code, use:
                  [CREATE_FILE path="path/to/file.ext"]
                  CODE_CONTENT
                  [/CREATE_FILE]
                - You can understand documents uploaded via API or UI. Analyze them to provide context-aware code.

                DOCUMENT_GENERATION:
                - You can generate Excel, Word, and PDF files.
                - To generate a file, use the following tag:
                  [GENERATE_FILE type="excel|word|pdf" name="filename.ext"]
                  Content here (For excel, use markdown table format)
                  [/GENERATE_FILE]
                - Only generate one file per response.
            """.trimIndent()

            val systemInstruction = if (isRefinement) {
                """
                    You are a professional editor and writing assistant.
                    Your goal is to take the provided text (which may be rough transcript or spoken thoughts) and turn it into polished, professional, and well-structured text in $preferredLanguage.
                    - Fix grammar, improve vocabulary, and ensure smooth flow.
                    - Maintain the original meaning and tone.
                    - Use structured Markdown for lists or headings if appropriate.
                    - Provide only the polished text without meta-commentary.
                """.trimIndent()
            } else if (isContextFound) {
                val contextSource = when {
                    hasAttachmentKnowledge && hasDbKnowledge -> "ATTACHED DOCUMENTS and DATABASE"
                    hasAttachmentKnowledge -> "ATTACHED DOCUMENTS"
                    else -> "DATABASE"
                }
                "Use the provided $contextSource context as your primary source. If it doesn't answer the query, use your own knowledge. Do not hallucinate. $formatInstruction\n\n$agentCapabilities"
            } else {
                "Answer accurately using your internal knowledge in $preferredLanguage. $formatInstruction\n\n$agentCapabilities"
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
