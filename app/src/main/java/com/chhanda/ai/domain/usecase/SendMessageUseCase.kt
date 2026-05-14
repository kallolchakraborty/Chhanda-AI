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
    private val ingestor: com.chhanda.ai.domain.model.MultimodalIngestor,
    private val persistentIngestor: com.chhanda.ai.domain.usecase.IngestDocumentUseCase,
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
        source: String = "Local"
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
            
            // Context Awareness State
            var hasDbKnowledge = longTermContext.isNotBlank()
            var hasAttachmentKnowledge = false // Will be updated after processing attachments
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
            
            // STEP 3: Process Attachments (Direct Context)
            val attachmentContext = if (attachments.isNotEmpty()) {
                val texts = attachments.map { uri ->
                    try {
                        val uriString = uri.toString()
                        val fileName = uri.lastPathSegment ?: "file"
                        val (rawText, type) = when {
                            uriString.contains("image") || uriString.endsWith(".jpg") || uriString.endsWith(".png") || uriString.endsWith(".jpeg") -> 
                                ingestor.ingestImage(uri) to com.chhanda.ai.domain.usecase.DocType.IMAGE
                            uriString.endsWith(".pdf") -> 
                                ingestor.ingestPdf(uri).joinToString("\n") to com.chhanda.ai.domain.usecase.DocType.PDF
                            uriString.contains("audio") || uriString.endsWith(".wav") || uriString.endsWith(".mp3") -> 
                                ingestor.ingestAudio(uri) to com.chhanda.ai.domain.usecase.DocType.AUDIO
                            uriString.endsWith(".docx") || uriString.endsWith(".doc") -> 
                                ingestor.ingestWord(uri) to com.chhanda.ai.domain.usecase.DocType.WORD
                            uriString.endsWith(".xlsx") || uriString.endsWith(".xls") -> 
                                ingestor.ingestExcel(uri) to com.chhanda.ai.domain.usecase.DocType.EXCEL
                            else -> ingestor.ingestTxt(uri) to com.chhanda.ai.domain.usecase.DocType.TXT
                        }
                        
                        // PERSISTENCE: Store in the RAG database so it's available for future queries
                        val metaText = "[Source: $fileName] [Type: ${type.name}]\n\n$rawText"
                        try {
                            persistentIngestor.ingestScrapedText(rawText, uriString, type.name)
                        } catch (e: Exception) {
                            android.util.Log.e("SendMessageUseCase", "Failed to persist attachment to DB: ${e.message}")
                        }
                        
                        "--- ATTACHMENT: $fileName (${type.name}) ---\n$rawText\n"
                    } catch (e: Exception) {
                        "Error processing ${uri.lastPathSegment}: ${e.localizedMessage}"
                    }
                }
                hasAttachmentKnowledge = true
                isContextFound = true
                texts.joinToString("\n")
            } else ""

            // ORCHESTRATION: Construct the Final Multi-Tiered Prompt
            val prompt = buildString {
                append("### SYSTEM ROLE: CHHANDA AI GATEWAY ORCHESTRATOR\n")
                append("You are Chhanda AI, an expert assistant. You have access to a tiered knowledge system.\n")
                append("PRIORITY 1 (ATTACHMENTS): Use TIER 1 first. It contains the immediate files the user provided.\n")
                append("PRIORITY 2 (KNOWLEDGE BASE): Use TIER 2 if the answer isn't in TIER 1.\n")
                append("PRIORITY 3 (INTERNAL): Only use your pre-trained knowledge if the above tiers are insufficient.\n\n")

                if (attachmentContext.isNotBlank()) {
                    append("### [URGENT] TIER 1: CURRENT_ATTACHMENTS\n")
                    append(attachmentContext)
                    append("\n--- END OF CURRENT ATTACHMENTS ---\n\n")
                }
                
                if (longTermContext.isNotBlank()) {
                    append("### TIER 2: DATABASE_KNOWLEDGE_CONTEXT\n")
                    append(longTermContext)
                    append("\n\n")
                }
                
                append("### USER_QUERY\n")
                append(sanitizedUserText)
                
                append("\n\n### CRITICAL INSTRUCTIONS\n")
                append("1. STRICT RELEVANCE: Only use TIER 1 or TIER 2 context if it DIRECTLY and SPECIFICALLY answers the query. If the context is about a different topic, ignore it completely and use your pre-trained knowledge.\n")
                append("2. ATTACHMENT PRIORITY: If TIER 1 contains the answer, use it and STOP searching. Do not combine with irrelevant snippets from TIER 2.\n")
                append("3. NO FORCED ANSWERS: If the user asks about an attachment but it's not in TIER 1 or TIER 2, state that the document doesn't seem to contain that information.\n")
                append("4. SMALL TALK: If the query is greetings or small talk, IGNORE all context and respond naturally.\n")
                append("5. LANGUAGE: Respond in $preferredLanguage.\n")
            }

            val formatInstruction = """
                
                RESPONSE FORMATTING RULES:
                - Use structured Markdown for all responses.
                - FOR CODE: Always use triple backticks with the language name (e.g., ```kotlin). Ensure clean indentation, descriptive comments, and logical structure.
                - FOR LEARNING/GUIDES: Use a hierarchical structure with headings (###), bullet points, and numbered lists. Start with a brief overview, followed by step-by-step details, and end with a summary or key takeaways.
                - FOR TABLES: Use standard Markdown table syntax.
                - VISUAL CLARITY: Use bold text (**word**) to highlight key terms and inline code (`code`) for technical variables.
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
