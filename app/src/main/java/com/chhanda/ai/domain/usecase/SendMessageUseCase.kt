package com.chhanda.ai.domain.usecase

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.model.ContextManager
import kotlinx.coroutines.flow.*

@javax.inject.Singleton
class SendMessageUseCase @javax.inject.Inject constructor(
    private val llmEngineLazy: dagger.Lazy<com.chhanda.ai.domain.model.LLMEngine>,
    private val chatDao: com.chhanda.ai.data.repository.ChatDao,
    private val contextManager: com.chhanda.ai.domain.model.ContextManager,
    private val turnContextIngestor: com.chhanda.ai.domain.usecase.TurnContextIngestor,
    private val personaManager: com.chhanda.ai.domain.model.PersonaManager,
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository,
    private val responseProcessor: com.chhanda.ai.domain.service.ResponseProcessor,
    private val agenticActionHandler: com.chhanda.ai.domain.service.AgenticActionHandler,
    private val webSearchUseCase: com.chhanda.ai.domain.usecase.WebSearchUseCase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val llmEngine get() = llmEngineLazy.get()
    
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

        var saved = false
        var partialAccumulated = ""
        var isContextFound = false

        try {
            val ragEnabled = settingsRepository.ragEnabledFlow.first()
            if (ragEnabled) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Searching local knowledge base..."))
            }

            val (dbHistory, longTermContextRaw) = contextManager.getOptimizedContext(userText, deviceId, modelName, sessionId)

            var currentHistorySize = 0
            val ctxLen = settingsRepository.contextLengthFlow.firstOrNull()?.toIntOrNull() ?: 2048
            val charBudget = (ctxLen * 3.5).toInt() 
            
            val prunedHistory = (externalHistory ?: dbHistory).takeLast(10).filter {
                currentHistorySize += it.second.length
                currentHistorySize < charBudget
            }

            val longTermContext = if (ragEnabled) longTermContextRaw else ""
            val history = prunedHistory

            var hasDbKnowledge = longTermContext.isNotBlank()
            var hasAttachmentKnowledge = false 
            
            // Web Search Fallback if enabled and local database does not yield matches
            var hasWebKnowledge = false
            var webContext = ""
            val retrievedSourcesList = mutableListOf<String>()

            // 1. Gather any sources from local database context
            if (hasDbKnowledge) {
                val sourceRegex = """\[Source #\d+:\s*(.*?)\]""".toRegex()
                sourceRegex.findAll(longTermContext).forEach { match ->
                    val cleanSrc = match.groupValues[1].trim()
                    if (cleanSrc.isNotEmpty() && !retrievedSourcesList.contains(cleanSrc)) {
                        retrievedSourcesList.add(cleanSrc)
                    }
                }
            }

            // 2. Perform web search if local database is empty and RAG is enabled
            if (ragEnabled && !hasDbKnowledge) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("No local matches. Searching the web..."))
                try {
                    val searchResults = webSearchUseCase(userText)
                    if (searchResults.isNotEmpty()) {
                        hasWebKnowledge = true
                        webContext = buildString {
                            append("<retrieved_web_knowledge>\n")
                            searchResults.forEachIndexed { index, result ->
                                append("[Source #$index: ${result.title}]\n")
                                append("URL: ${result.url}\n")
                                append("Content: ${result.snippet}\n\n")
                                val combinedSource = "${result.title}|${result.url}"
                                if (!retrievedSourcesList.contains(combinedSource)) {
                                    retrievedSourcesList.add(combinedSource)
                                }
                            }
                            append("</retrieved_web_knowledge>")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SendMessageUseCase", "Web search failed: ${e.message}")
                }
            }

            isContextFound = hasDbKnowledge || hasWebKnowledge

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

            if (history.isEmpty()) {
                llmEngine.resetSession(sessionId)
            }

            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Applying safety guardrails..."))
            if (com.chhanda.ai.util.SafetyGuardrails.isPotentialInjection(userText)) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected."))
                return@flow
            }

            val sanitizedUserText = com.chhanda.ai.util.SafetyGuardrails.sanitizeInput(userText)
            
            if (attachments.isNotEmpty()) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Processing ${attachments.size} attachments..."))
            }
            val attachmentContext = turnContextIngestor.processTurnContext(userText, attachments)
            if (attachmentContext.isNotBlank()) {
                hasAttachmentKnowledge = true
                isContextFound = true
            }

            val isApiRequest = source.lowercase() == "api"

            val baseInstructions = buildString {
                val role = when (source) {
                    "api", "qr" -> "Senior Technical Architect & Software Engineer"
                    else -> "Chhanda, a highly capable Senior AI Assistant"
                }
                val allowedPersonas = setOf("Senior Teacher", "Senior Software Engineer", "General Companion", "Friend", "Default")
                val validatedPersona = if (persona != null && persona in allowedPersonas && persona != "Default") persona else null
                append("IDENTITY: You are ${validatedPersona ?: role}. Respond in $preferredLanguage.\n")

                append("REASONING (CoVe): For complex queries, coding, or analytical tasks, think step-by-step using a 'Chain of Verification' approach:\n")
                append("1. Analyze the user intent and constraints.\n")
                append("2. Retrieve and verify facts from context.\n")
                append("3. Plan the structure.\n")
                append("4. Wrap your reasoning process inside <thought> tags.\n")
                append("For simple greetings (e.g. 'hi', 'hello', 'hey', 'hi there'), casual talk, or extremely straightforward questions, do NOT use <thought> tags. Respond directly and naturally.\n")

                if (!includeThinking) {
                    append("USER PREFERENCE: The user has requested a compact response. While you can reason internally in <thought> tags if needed, keep the final answer concise and direct.\n")
                }

                // Senior Ingestion Architecture: Prioritization logic
                if (hasDbKnowledge) {
                    append("LOCAL KNOWLEDGE BASE RETRIEVED: Use the provided local database context in the <retrieved_knowledge> section as your primary source of truth. Ground your response heavily in this facts list first.\n")
                }
                if (hasWebKnowledge) {
                    append("WEB SEARCH RESULTS RETRIEVED: Real-time search results are provided in <retrieved_web_knowledge> because no local database matches were found. Synthesize these results into a highly structured, cohesive, and fully integrated explanation. Avoid providing scattered, disjointed snippets or bullet points without context. Write in a clear, easy-to-understand manner so the user can easily check and verify the information. Group related details logically under clear section headers.\n")
                }
                if (hasAttachmentKnowledge) {
                    append("ATTACHMENT DATA RETRIEVED: Use the text extracted from the user's uploaded attachment files to answer the query.\n")
                }
                if (!hasDbKnowledge && !hasWebKnowledge && !hasAttachmentKnowledge) {
                    append("NO CONTEXT RETRIEVED: No local knowledge base, web results, or attachments are available. Answer the question using your pre-trained general knowledge base. Be highly helpful and detailed.\n")
                } else {
                    append("UNSURE CASE: If the question cannot be answered by the retrieved local documents or web search results, explain what is missing, and then provide a helpful response using your pre-trained parameters, clearly stating that you are falling back to pretrained general intelligence.\n")
                }

                if (llmEngine.isMultimodal() && attachments.any { it.toString().contains("image") }) {
                    append("VISION CAPABILITY: You have native vision processing enabled. Analyze the provided image attachments to answer the query accurately.\n")
                }

                append("GUARDRAILS: Redact PII (Emails, Phones, CC) in output. No hallucinations. No generic conversational filler.\n")

                // Perplexity-style & Persona instructions
                append("\nRESPONSE FORMAT (Perplexity Style):\n")
                append("- Structured Synthesis: Start with a concise, direct, and high-level summary that answers the question immediately.\n")
                append("- Logical Deep-Dive: Follow the summary with highly organized sections, using clear bold headers and clean bullet/numbered lists.\n")
                append("- Inline Citations: Ground every fact strictly by citing using [Source #X] inline, corresponding to the provided sources list.\n")
                append("- Persona-Based Adaptation:\n")
                val p = validatedPersona ?: "Default"
                when (p) {
                    "Senior Teacher" -> {
                        append("  * Tone: Educational, patient, highly structured, encouraging.\n")
                        append("  * Style: Explain complex terms using simple analogies. End with a friendly, brief review question to check comprehension.\n")
                    }
                    "Senior Software Engineer" -> {
                        append("  * Tone: Technical, precise, professional, objective.\n")
                        append("  * Style: Focus on architecture, performance, clean code blocks with brief comments, and step-by-step logic. Omit conversational fluff.\n")
                    }
                    "Friend" -> {
                        append("  * Tone: Conversational, highly supportive, friendly, casual, empathetic.\n")
                        append("  * Style: Use warm, encouraging phrases, while maintaining high-quality structured answers. Treat the user as a close friend.\n")
                    }
                    "General Companion" -> {
                        append("  * Tone: Highly helpful, empathetic, balanced, informative.\n")
                        append("  * Style: Provide well-balanced, comprehensive explanations, maintaining a polite and warm dialogue.\n")
                    }
                    else -> {
                        append("  * Tone: Professional, structured, direct, concise.\n")
                        append("  * Style: Organized, clear, objective, and highly professional.\n")
                    }
                }
            }

            val formatInstruction = """
                OUTPUT CONSTRAINTS:
                - Use structured Markdown (Headers, Bold, Lists) for readability.
                - Be technically precise but linguistically natural.
                - FOR CODE: Use triple backticks with the language (e.g. ```kotlin).
                - CITE SOURCES: If using context, use [Source #X] inline.
            """.trimIndent()

            val agentCapabilities = """
                PLATFORM CAPABILITIES:
                - You are an expert AI orchestrator.
                - [CREATE_FILE path="..."]...[/CREATE_FILE] -> Generates raw code files.
                - [GENERATE_FILE type="..." name="..."]...[/GENERATE_FILE] -> Creates Office docs (PDF, DOCX, XLSX).
                - Use these only when explicitly requested or highly beneficial.
            """.trimIndent()

            val systemInstruction = if (isRefinement) {
                "Professional editor mode. Polish the text in $preferredLanguage. Only return polished text."
            } else {
                "$baseInstructions\n\n$formatInstruction\n\n$agentCapabilities"
            }

            val (promptToUse, historyToUse, systemInstructionToUse) = if (isApiRequest) {
                // Reset stateful conversation to prevent session pollution across independent API calls
                llmEngine.resetSession(sessionId)

                val apiPrompt = buildString {
                    if (history.isNotEmpty()) {
                        append("CONVERSATION HISTORY:\n")
                        history.forEach { turn ->
                            val roleLabel = when (turn.first.lowercase()) {
                                "user" -> "User"
                                "model", "assistant" -> "Assistant"
                                "system" -> "System"
                                else -> "User"
                            }
                            append("$roleLabel: ${turn.second}\n")
                        }
                        append("\n")
                    }
                    if (attachmentContext.isNotBlank()) {
                        append("Current Attachments Content:\n")
                        append(attachmentContext)
                        append("\n\n")
                    }
                    if (longTermContext.isNotBlank()) {
                        append("Retrieved Context:\n")
                        append(longTermContext)
                        append("\n\n")
                    }
                    if (webContext.isNotBlank()) {
                        append("Retrieved Web Context:\n")
                        append(webContext)
                        append("\n\n")
                    }
                    append(sanitizedUserText)
                }

                Triple(apiPrompt, history, systemInstruction)
            } else {
                val promptText = if (attachmentContext.isBlank() && longTermContext.isBlank() && webContext.isBlank()) {
                    sanitizedUserText
                } else {
                    buildString {
                        if (attachmentContext.isNotBlank()) {
                            append("Current Attachments Content:\n")
                            append(attachmentContext)
                            append("\n\n")
                        }
                        if (longTermContext.isNotBlank()) {
                            append("Retrieved Context:\n")
                            append(longTermContext) 
                            append("\n\n")
                        }
                        if (webContext.isNotBlank()) {
                            append("Retrieved Web Context:\n")
                            append(webContext)
                            append("\n\n")
                        }
                        append("User Question: ")
                        append(sanitizedUserText)
                    }
                }
                Triple(promptText, history, systemInstruction)
            }

            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Orchestrating model response..."))
            val responseFlow = llmEngine.generateResponse(promptToUse, historyToUse, systemInstructionToUse, attachments, sessionId)
            
            responseProcessor.processStream(responseFlow, includeThinking).collect { update ->
                when (update) {
                    is com.chhanda.ai.domain.model.TokenUpdate.Partial -> {
                        partialAccumulated += update.text
                        emit(update)
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Final -> {
                        val processed = responseProcessor.cleanFinalResponse(partialAccumulated)
                        val cleanedResponse = processed.text
                        val extractedThinking = processed.thinking

                        // Append sources metadata block at the very end
                        val trimmedResponse = cleanedResponse.trim()
                        val sourcesTag = if (retrievedSourcesList.isNotEmpty()) {
                            "\n\n[Sources: ${retrievedSourcesList.joinToString("||")}]"
                        } else {
                            ""
                        }

                        val toSave = trimmedResponse + sourcesTag

                        if (toSave.isNotBlank() || !extractedThinking.isNullOrBlank()) {

                            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Handling agentic actions..."))
                            val actionResult = agenticActionHandler.handleActions(toSave)
                            val filePath = actionResult.generatedFilePath

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
                                        hasWebKnowledge -> "Web Fallback"
                                        else -> null
                                    },
                                    responseTimeMs = update.responseTimeMs,
                                    generatedFilePath = filePath,
                                    source = source,
                                    thinking = extractedThinking
                                ))
                            }
                            saved = true
                            contextManager.maintainMemoryHygiene()
                        }
                        emit(update)
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Status -> emit(update)
                    is com.chhanda.ai.domain.model.TokenUpdate.Error -> {
                        emit(update)
                    }
                }
            }
        } catch (e: Throwable) {
            emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Generation failure: ${e.localizedMessage}"))
        } finally {
            if (!saved && partialAccumulated.trim().isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = partialAccumulated.trim(), role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, isRagUsed = isContextFound))
                    contextManager.maintainMemoryHygiene()
                }
            }
        }
    }
}
