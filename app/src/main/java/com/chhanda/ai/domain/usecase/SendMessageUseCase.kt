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
    private val contextManager: com.chhanda.ai.domain.model.ContextManager,
    private val googleSearchUseCase: GoogleSearchUseCase,
    private val scrapeUrlUseCase: ScrapeUrlUseCase
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val loadBalancer = LoadBalancer(numReplicas = 1) // Default to 1 replica for Android constraints

    /**
     * Executes the RAG-augmented generation pipeline.
     */
    operator fun invoke(userText: String, deviceId: String, modelName: String, sessionId: String, attachments: List<android.net.Uri> = emptyList(), preferredLanguage: String = "English"): kotlinx.coroutines.flow.Flow<com.chhanda.ai.domain.model.TokenUpdate> = kotlinx.coroutines.flow.flow {
        val replica = loadBalancer.getReplica(userText)
        android.util.Log.d("LoadBalancer", "Routed request to replica: $replica")
        
        var saved = false
        var partialAccumulated = ""
        var isContextFound = false
        
        try {
            // STEP 1: Get Optimized Context (Short-term + Long-term)
            val (history, longTermContext) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)
            isContextFound = longTermContext.isNotBlank()
            
            var webContext = ""
            val citedSources = mutableListOf<String>()

            // Web Search Fallback if no local data is found
            if (!isContextFound) {
                android.util.Log.d("SendMessageUseCase", "No local context found. Trying web search...")
                val searchResults = googleSearchUseCase(userText)
                
                if (searchResults.isNotEmpty()) {
                    val builder = StringBuilder()
                    var contentGathered = 0
                    
                    for (result in searchResults) {
                        try {
                            android.util.Log.d("SendMessageUseCase", "Scraping result: ${result.url}")
                            val content = scrapeUrlUseCase(result.url)
                            if (content.length > 200) { // Ensure content is sufficient
                                builder.append("Source: ${result.url}\n")
                                builder.append("Title: ${result.title}\n")
                                builder.append("Content: $content\n\n")
                                citedSources.add(result.url)
                                contentGathered++
                                
                                if (contentGathered >= 2) break // Gather from top 2 sufficient sources
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("SendMessageUseCase", "Failed to scrape ${result.url}: ${e.message}")
                        }
                    }
                    
                    webContext = builder.toString().trim()
                    if (webContext.isNotBlank()) {
                        isContextFound = true
                    }
                }
            }

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
                val contextToUse = if (webContext.isNotBlank()) webContext else longTermContext
                val citationNote = if (webContext.isNotBlank()) "Cite the retrieved sources in your response payload." else "Use the documentation above as your primary source if it contains the answer."
                
                """
                DOCUMENT_CONTEXT_START
                $contextToUse
                DOCUMENT_CONTEXT_END
                
                Please answer the following user query. $citationNote If the information is not in the documentation, use your own general knowledge to answer accurately.
                
                $sanitizedUserText
                """.trimIndent()
            } else {
                sanitizedUserText
            }

            val systemInstruction = """
                    You are a hybrid RAG assistant.

                    Your job is to answer using local knowledge first, and fall back to live web retrieval only when local knowledge is missing, outdated, or insufficient.

                    Core policy:
                    - First try to answer from local context, retrieved documents, and internal knowledge.
                    - If the available local content does not fully answer the question, retrieve live web sources.
                    - Use the live web only as a fallback or supplement, not as the first default.
                    - Prefer authoritative, primary, and recent sources when using the web.

                    Sufficient content rule:
                    - Stop retrieving once the answer is sufficiently supported.
                    - “Sufficient” means the retrieved information directly answers the user’s question with no major gaps.
                    - For simple factual questions, 1–2 strong sources are usually enough.
                    - For complex, technical, legal, financial, or conflicting questions, read 3–5 sources.
                    - If the answer is already clear from one strong source, do not over-collect.

                    Source handling:
                    - Prefer official documentation, primary sources, or original announcements.
                    - If sources conflict, gather more evidence until the best-supported answer is clear.
                    - If the local context and web sources disagree, explain the difference briefly and favor the more reliable source.
                    - Do not invent facts that are not supported by the retrieved content.

                    Response style:
                    - Answer directly and concisely.
                    - Mention uncertainty only when the evidence is incomplete or conflicting.
                    - If the answer cannot be determined with confidence, say what is missing and ask one focused follow-up question.

                    Decision modes:
                    - Fast mode: stop after 1 strong source if it fully answers the question.
                    - Balanced mode: stop after 2–3 agreeing sources.
                    - High-accuracy mode: stop after 4–6 sources or when authoritative agreement is reached.

                    Behavior summary:
                    - Local knowledge first.
                    - Live web fallback when needed.
                    - Stop when content is sufficient.
                    - Do not over-browse.
                    
                    CONSTRAINTS:
                    - RESPONSE LANGUAGE: $preferredLanguage
                """.trimIndent()

            // STEP 4: Streamed Generation — emit tokens as they arrive
            llmEngine.generateResponse(prompt, history, systemInstruction, attachments).collect { update ->
                emit(update)
                when (update) {
                    is com.chhanda.ai.domain.model.TokenUpdate.Partial -> partialAccumulated += update.text
                    is com.chhanda.ai.domain.model.TokenUpdate.Final -> {
                        val toSave = update.fullText.ifBlank { partialAccumulated }.trim()
                        if (toSave.isNotBlank()) {
                            chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = toSave, role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, tps = update.tps, isRagUsed = isContextFound))
                            saved = true
                            contextManager.maintainMemoryHygiene()
                        }
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Error -> { /* Error is emitted; nothing to save */ }
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
