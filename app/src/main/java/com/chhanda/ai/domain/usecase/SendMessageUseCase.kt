package com.chhanda.ai.domain.usecase

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.MessageEntity
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.model.ContextManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    private val networkManager: com.chhanda.ai.data.repository.NetworkManager,
    private val ingestDocumentUseCaseLazy: dagger.Lazy<com.chhanda.ai.domain.usecase.IngestDocumentUseCase>,
    private val uploadedFileDaoLazy: dagger.Lazy<com.chhanda.ai.data.repository.UploadedFileDao>,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val llmEngine get() = llmEngineLazy.get()
    private val ingestDocumentUseCase get() = ingestDocumentUseCaseLazy.get()
    private val uploadedFileDao get() = uploadedFileDaoLazy.get()
    
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
            val isInternetPresent = networkManager.isConnected.value || run {
                try {
                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val net = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(net)
                    caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } catch (e: Exception) {
                    false
                }
            }
            val isQrRequest = source.lowercase() == "qr"
            val isGreetingMsg = isGreeting(userText)
            val isRealTime = isWeatherOrNewsQuery(userText)
            val ragEnabled = settingsRepository.ragEnabledFlow.first() && !isQrRequest && !isGreetingMsg
            val webSearchEnabled = settingsRepository.webSearchEnabledFlow.first() && !isQrRequest && !isGreetingMsg

            if (attachments.isNotEmpty()) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Processing ${attachments.size} attachments..."))
            }
            val attachmentContextRaw = turnContextIngestor.processTurnContext(userText, attachments)

            val activeAttachmentPaths = attachments.map { it.toString() }
            val (dbHistory, longTermContextRaw) = contextManager.getOptimizedContext(
                userText, 
                deviceId, 
                modelName, 
                sessionId,
                activeAttachmentPaths
            )

            // Dynamic Token/Character Budget Allocation System to prevent prompt bloat and engine JNI crashes
            val ctxLen = settingsRepository.contextLengthFlow.firstOrNull()?.toIntOrNull() ?: 2048
            // Total prompt character budget (reserving 512 tokens for model output generation)
            val promptCharBudget = ((ctxLen - 512).coerceAtLeast(1024) * 3.5).toInt()
            
            // Allocate shares of promptCharBudget
            val systemReserved = 1800
            val remainingBudget = (promptCharBudget - systemReserved).coerceAtLeast(1500)
            
            // 1. Cap Current User Message (max 1200 characters)
            val userTextLimit = (remainingBudget * 0.25).toInt().coerceAtMost(1200).coerceAtLeast(500)
            val sanitizedUserText = com.chhanda.ai.util.SafetyGuardrails.sanitizeInput(userText)
            val cappedUserText = if (sanitizedUserText.length > userTextLimit) {
                sanitizedUserText.take(userTextLimit) + "... [truncated due to context limits]"
            } else {
                sanitizedUserText
            }

            // 2. Cap Augmented Contexts (RAG, Web, Attachments)
            val contextBudget = (remainingBudget * 0.45).toInt().coerceAtLeast(1000)
            val singleContextLimit = (contextBudget / 3).coerceAtLeast(400)

            if (ragEnabled) {
                if (isInternetPresent) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Online mode active: Searching local database..."))
                } else {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Offline mode active: Searching local database..."))
                }
            }

            // Process RAG long-term context (skip for real-time queries to avoid stale database pollution)
            val longTermContext = if (ragEnabled && !isRealTime && longTermContextRaw.isNotBlank()) {
                val sourceSegments = longTermContextRaw
                    .substringAfter("<retrieved_knowledge>\n")
                    .substringBefore("</retrieved_knowledge>")
                    .split("\n\n")
                    .filter { it.isNotBlank() }
                
                val distinctSegments = deduplicateContexts(sourceSegments)
                if (distinctSegments.isNotEmpty()) {
                    buildString {
                        append("<retrieved_knowledge>\n")
                        distinctSegments.take(3).forEach { segment ->
                            val lines = segment.trim().lines()
                            val header = lines.firstOrNull() ?: ""
                            val content = lines.drop(1).joinToString("\n")
                            val cappedContent = if (content.length > singleContextLimit) {
                                content.take(singleContextLimit) + "... [truncated]"
                            } else {
                                content
                            }
                            append("$header\n$cappedContent\n\n")
                        }
                        append("</retrieved_knowledge>")
                    }
                } else ""
            } else ""

            var hasDbKnowledge = longTermContext.isNotBlank()
            var hasAttachmentKnowledge = false 
            
            // Web Search Fallback if enabled, internet is present, and local database does not yield matches
            var hasWebKnowledge = false
            var webContext = ""
            val retrievedSourcesList = mutableListOf<String>()

            // Gather any sources from local database context
            if (hasDbKnowledge && !isRealTime) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Local database context found. Formulating grounded response..."))
                val sourceRegex = """\[Source #\d+:\s*(.*?)\]""".toRegex()
                sourceRegex.findAll(longTermContext).forEach { match ->
                    val cleanSrc = match.groupValues[1].trim()
                    if (cleanSrc.isNotEmpty() && !retrievedSourcesList.contains(cleanSrc)) {
                        retrievedSourcesList.add(cleanSrc)
                    }
                }
            }

            // Web Search Fallback or forced Real-Time query search
            // Web Search Fallback or forced Real-Time query search
            // ONLY triggered if: 
            // 1. It is a forced news/weather query (isRealTime is true)
            // 2. OR local RAG retrieved no relevant facts/context (hasDbKnowledge is false)
            val shouldTriggerWebSearch = isInternetPresent && webSearchEnabled && (isRealTime || !hasDbKnowledge)

            if (shouldTriggerWebSearch) {
                if (isRealTime) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Fetching real-time updates for news/weather..."))
                } else {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Local database yielded no results. Searching the web..."))
                }
                try {
                    val searchResults = webSearchUseCase(userText)
                    if (searchResults.isNotEmpty()) {
                        val snippetsList = searchResults.map { it.snippet }
                        val distinctSnippets = deduplicateContexts(snippetsList)
                        val filteredResults = searchResults.filter { distinctSnippets.contains(it.snippet) }
                        
                        if (filteredResults.isNotEmpty()) {
                            hasWebKnowledge = true
                            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Web matches found. Synthesizing real-time information..."))
                            webContext = buildString {
                                append("<retrieved_web_knowledge>\n")
                                filteredResults.take(3).forEachIndexed { index, result ->
                                    val combinedSource = "${result.title}|${result.url}"
                                    if (!retrievedSourcesList.contains(combinedSource)) {
                                        retrievedSourcesList.add(combinedSource)
                                    }
                                    val rawSnippet = result.snippet
                                    val cappedSnippet = if (rawSnippet.length > singleContextLimit) {
                                        rawSnippet.take(singleContextLimit) + "... [truncated]"
                                    } else {
                                        rawSnippet
                                    }
                                    append("[Source #$index: ${result.title}]\n")
                                    append("URL: ${result.url}\n")
                                    append("Content: $cappedSnippet\n\n")
                                }
                                append("</retrieved_web_knowledge>")
                            }
                        }
                        
                        // Ingest the search results to the vector database for later use in the background
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                searchResults.forEach { result ->
                                    val resultText = "Title: ${result.title}\nURL: ${result.url}\nSnippet: ${result.snippet}"
                                    val finalLabel = result.title.ifBlank { result.url }.take(50).trim()
                                    val existing = uploadedFileDao.findByNameAndSize(finalLabel, resultText.length.toLong())
                                    if (existing == null) {
                                        ingestDocumentUseCase.ingestScrapedText(resultText, result.url, finalLabel)
                                        uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = finalLabel,
                                            format = "WEB_URL",
                                            size = resultText.length.toLong(),
                                            path = result.url,
                                            timestamp = System.currentTimeMillis()
                                        ))
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("SendMessageUseCase", "Failed to ingest web search results: ${e.message}")
                            }
                        }
                    } else {
                        emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Web search returned no matches. Answering from pre-trained knowledge..."))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SendMessageUseCase", "Web search failed: ${e.message}")
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Web search error. Answering from pre-trained knowledge..."))
                }
            } else if (!hasDbKnowledge) {
                // If local RAG has no matches, and web search was not run, report why and fallback to pre-trained weights
                if (isRealTime && !webSearchEnabled) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Real-time updates requested but web search is disabled. Answering from pre-trained knowledge..."))
                } else if (!isInternetPresent && isRealTime) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Real-time updates requested but device is offline. Answering from pre-trained knowledge..."))
                } else if (isInternetPresent && !webSearchEnabled) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("No local matches & web search disabled. Answering from pre-trained knowledge..."))
                } else if (!isInternetPresent) {
                    emit(com.chhanda.ai.domain.model.TokenUpdate.Status("No local matches and device offline. Answering from pre-trained knowledge..."))
                }
            }

            isContextFound = hasDbKnowledge || hasWebKnowledge

            val attachmentContext = if (attachmentContextRaw.isNotBlank()) {
                hasAttachmentKnowledge = true
                isContextFound = true
                val attachmentLimit = 3000
                if (attachmentContextRaw.length > attachmentLimit) {
                    attachmentContextRaw.take(attachmentLimit) + "... [truncated]"
                } else {
                    attachmentContextRaw
                }
            } else ""

            val finalUserText = if (attachmentContextRaw.isNotBlank()) {
                "$userText\n\n<turn_context>\n${attachmentContextRaw.take(3000)}\n</turn_context>"
            } else {
                userText
            }

            val attachmentPathsString = if (attachments.isNotEmpty()) attachments.joinToString(",") { it.toString() } else null
            chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(
                text = finalUserText, 
                role = "user", 
                deviceId = deviceId, 
                modelName = modelName, 
                sessionId = sessionId, 
                source = source,
                attachmentPaths = attachmentPathsString
            ))

            // 3. Process conversation history (cap at historyBudget)
            val historyBudget = (remainingBudget * 0.30).toInt().coerceAtLeast(800)
            var currentHistorySize = 0
            val compactedHistory = (externalHistory ?: dbHistory).map { (role, msg) ->
                role to compactPastMessage(msg)
            }
            val prunedHistory = compactedHistory.takeLast(6).filter {
                currentHistorySize += it.second.length
                currentHistorySize < historyBudget
            }
            val history = prunedHistory

            if (history.isEmpty()) {
                llmEngine.resetSession(sessionId)
            }

            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Applying safety guardrails..."))
            if (com.chhanda.ai.util.SafetyGuardrails.isPotentialInjection(userText)) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected."))
                return@flow
            }

            val isApiRequest = source.lowercase() == "api"

            val likedResponses = try { chatDao.getLikedMessagesGlobal() } catch (e: Exception) { emptyList() }
            val dislikedResponses = try { chatDao.getDislikedMessagesGlobal() } catch (e: Exception) { emptyList() }

            val baseInstructions = buildString {
                val role = when (source) {
                    "api", "qr" -> "Senior Technical Architect & Software Engineer"
                    else -> "Chhanda, a highly capable Senior AI Assistant"
                }
                val allowedPersonas = setOf("Senior Teacher", "Senior Software Engineer", "General Companion", "Friend", "Default")
                val validatedPersona = if (persona != null && persona in allowedPersonas && persona != "Default") persona else null
                append("IDENTITY: You are ${validatedPersona ?: role}. Respond in $preferredLanguage.\n")

                if (isShortAffirmation(userText)) {
                    append("BREVITY CONSTRAINT: The user sent a short acknowledgment or affirmation ('$userText'). Do NOT repeat previous facts, do NOT list bullet points, and do NOT ramble. Respond with a single, extremely brief, friendly, and natural sentence (e.g. 'Glad to help! Let me know if you need anything else.', 'My pleasure!', or 'You got it!').\n")
                }

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
                    append("LOCAL KNOWLEDGE BASE RETRIEVED: You MUST use the provided local database context in the <retrieved_knowledge> section as your primary source of truth. Ground your response completely in this retrieved facts list first. Do NOT hallucinate, do NOT fabricate facts, and do NOT use external pre-trained general knowledge if it contradicts or goes beyond the retrieved local database context. If the query asks for specific facts, locate and extract them from the <retrieved_knowledge> block. Quote or reference the sources explicitly using [Source #X] inline.\n")
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
                    append("UNSURE CASE: If local knowledge or web search results are retrieved but do not contain the answer, explicitly state: 'I could not find the exact answer in the local database or search results.' Then, and ONLY then, provide a cautious response based on your pre-trained parameters, clearly separating it as a fallback answer.\n")
                }

                if (llmEngine.isMultimodal() && attachments.any { it.toString().contains("image") }) {
                    append("VISION CAPABILITY: You have native vision processing enabled. Analyze the provided image attachments to answer the query accurately.\n")
                }

                append("GUARDRAILS: Redact PII (Emails, Phones, CC) in output. No hallucinations. No generic conversational filler.\n")
                append("KNOWLEDGE BASE PERSISTENCE: If the query requires a technical, architectural, or factual response, answer with extreme precision, detail, and structure. Important information from this turn will be automatically indexed into your permanent local RAG memory for future recall. Simple greetings, casual pleasantries, or basic chit-chat are NOT saved, so keep those direct and natural.\n")

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

                if (likedResponses.isNotEmpty()) {
                    append("\nUSER PREFERENCES (LIKED RESPONSES):\n")
                    append("The user previously liked these responses. Analyze their structure, detail, or formatting, and continue to produce responses of similar high quality:\n")
                    likedResponses.forEachIndexed { i, msg ->
                        append("- Feedback Sample #${i + 1}:\n")
                        append("  Response: \"${msg.text.take(300)}\"\n")
                    }
                }
                if (dislikedResponses.isNotEmpty()) {
                    append("\nUSER PREFERENCES (DISLIKED RESPONSES):\n")
                    append("The user disliked these previous responses. Make sure to improve and avoid repeating these mistakes:\n")
                    dislikedResponses.forEachIndexed { i, msg ->
                        append("- Disliked Sample #${i + 1}:\n")
                        append("  Avoid this style/content: \"${msg.text.take(300)}\"\n")
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
                    append(cappedUserText)
                }

                Triple(apiPrompt, history, systemInstruction)
            } else {
                val promptText = if (attachmentContext.isBlank() && longTermContext.isBlank() && webContext.isBlank()) {
                    cappedUserText
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
                        append(cappedUserText)
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

                            val filePath: String?

                            if (!isApiRequest) {
                                emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Handling agentic actions..."))
                                val actionResult = agenticActionHandler.handleActions(toSave)
                                filePath = actionResult.generatedFilePath
                            } else {
                                filePath = null
                            }

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
                             saved = true
                             contextManager.maintainMemoryHygiene()

                             // Asynchronously generate session title on the first conversation turn
                             val titleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                             titleScope.launch {
                                 try {
                                     val currentMessages = chatDao.getRecentMessagesForSession(sessionId, 5)
                                     val hasTitle = currentMessages.any { !it.sessionTitle.isNullOrBlank() }
                                     if (!hasTitle && currentMessages.isNotEmpty()) {
                                         // Retrieve the first user message
                                         val firstUserMessage = currentMessages.firstOrNull { it.role == "user" }?.text ?: userText
                                         val titlePrompt = """
                                             Generate an extremely brief chat title (3 to 5 words max) in the language of the query that summarizes the following user question. Do not use quotes, punctuation, or any introductory text. Output only the title.
                                             
                                             USER QUESTION: "$firstUserMessage"
                                         """.trimIndent()
                                         
                                         var generatedTitle = ""
                                         llmEngine.generateResponse(titlePrompt, emptyList(), "Title Generator Mode. Output 3-5 words title only.", emptyList(), "title_gen_$sessionId").collect { titleUpdate ->
                                             if (titleUpdate is com.chhanda.ai.domain.model.TokenUpdate.Partial) {
                                                 generatedTitle += titleUpdate.text
                                             } else if (titleUpdate is com.chhanda.ai.domain.model.TokenUpdate.Final) {
                                                 val cleanTitle = generatedTitle.trim()
                                                     .replace("^[\"']|[\"']$".toRegex(), "") // remove leading/trailing quotes
                                                     .replace("[.#?]$".toRegex(), "") // remove trailing punctuation
                                                     .trim()
                                                 if (cleanTitle.isNotBlank() && cleanTitle.length < 50) {
                                                     chatDao.updateSessionTitle(sessionId, cleanTitle)
                                                     android.util.Log.i("SendMessageUseCase", "Generated session title for $sessionId: $cleanTitle")
                                                 }
                                             }
                                         }
                                     }
                                 } catch (e: Exception) {
                                     android.util.Log.e("SendMessageUseCase", "Failed to generate session title: ${e.message}")
                                 }
                             }

                             // 🚀 Automated Knowledge Base Ingestion for Important facts/conversations!
                             if (toSave.isNotBlank()) {
                                 val userQuery = userText
                                 val aiResponse = trimmedResponse
                                 val msgId = "fact-" + System.currentTimeMillis()
                                 val autoSaveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                                 autoSaveScope.launch {
                                     try {
                                         if (false) {
                                             val cleanQuerySnippet = userQuery.take(40).trim().replace("[^a-zA-Z0-9 ]".toRegex(), "")
                                             val label = "Chat Fact: $cleanQuerySnippet..."
                                             
                                             val factText = buildString {
                                                 append("Topic: $cleanQuerySnippet\n")
                                                 append("User Query: $userQuery\n\n")
                                                 append("Verified Answer:\n$aiResponse\n")
                                             }
                                             
                                             // Ingest fact as a semantic RAG entry
                                             ingestDocumentUseCase.ingestScrapedText(factText, "chat://fact/$msgId", label)
                                             
                                             // Store reference record in UploadedFileEntity
                                             uploadedFileDao.insertFile(com.chhanda.ai.data.repository.UploadedFileEntity(
                                                 id = java.util.UUID.randomUUID().toString(),
                                                 name = label,
                                                 format = "CHAT_FACT",
                                                 size = factText.length.toLong(),
                                                 path = "chat://fact/$msgId",
                                                 timestamp = System.currentTimeMillis()
                                             ))
                                             android.util.Log.i("SendMessageUseCase", "💡 Autosaved important chat turn to Knowledge Base RAG: $label")
                                         }
                                     } catch (e: Exception) {
                                         android.util.Log.e("SendMessageUseCase", "Failed to autosave chat fact: ${e.message}")
                                     }
                                 }
                             }
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
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private fun compactPastMessage(text: String): String {
        val codeRegex = "(?s)```.*?```".toRegex()
        return text.replace(codeRegex) { match ->
            val code = match.value
            if (code.length > 250) {
                "```\n[Code block truncated for efficiency]\n```"
            } else {
                code
            }
        }
    }

    private fun deduplicateContexts(contexts: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return contexts.map { it.trim() }.filter { ctx ->
            if (ctx.isEmpty()) return@filter false
            val normalized = ctx.lowercase().replace("\\s+".toRegex(), "")
            val isDuplicate = seen.any { prev -> 
                prev.contains(normalized) || normalized.contains(prev) || 
                (prev.length > 30 && normalized.length > 30 && prev.take(30) == normalized.take(30)) 
            }
            if (!isDuplicate) {
                seen.add(normalized)
                true
            } else {
                false
            }
        }
    }

    private fun shouldAutosaveToKnowledgeBase(query: String, response: String): Boolean {
        val q = query.lowercase().trim()
        val r = response.lowercase().trim()
        
        // 1. Identify Greetings & Casual Chit-Chat (No storage)
        val greetings = listOf(
            "hi", "hello", "hey", "hola", "greetings", "good morning", "good afternoon", 
            "good evening", "how are you", "how's it going", "howdy", "thank you", 
            "thanks", "thanks a lot", "bye", "goodbye", "see ya", "nice to meet you", 
            "appreciate it", "great", "awesome", "ok", "okay", "yes", "no", "sure"
        )
        if (greetings.any { q == it || q.startsWith("$it ") || q.endsWith(" $it") || q.replace("?", "").trim() == it }) {
            return false
        }
        
        // If the query is extremely short (casual back-and-forth)
        if (q.length < 15 && !q.contains("code") && !q.contains("api") && !q.contains("db")) {
            return false
        }
        
        // 2. Identify Technical / Informative Content
        // - Contains markdown code blocks
        if (response.contains("```") || query.contains("```")) return true
        
        // - Contains programming code keywords
        val codeKeywords = listOf(
            "fun ", "val ", "var ", "class ", "interface ", "import ", "def ", "function", 
            "const ", "let ", "public ", "private ", "func ", "struct ", "package "
        )
        if (codeKeywords.any { response.contains(it) || query.contains(it) }) return true
        
        // - Contains structured informational cues (Markdown tables, lists, section headers)
        if (response.contains("|") && response.contains("-")) return true // potential table
        if (response.contains("\n- ") || response.contains("\n* ") || response.contains("\n#")) return true
        
        // - Contains explicit technical or factual query markers
        val informativeKeywords = listOf(
            "how to", "explain", "tutorial", "architecture", "database", "install", 
            "configure", "setup", "error", "exception", "failed", "crash", "bug", 
            "fix", "resolve", "api", "endpoint", "url", "server", "ip", "port", "network", 
            "schema", "table", "query", "sql", "git", "github", "docker", "maven", "gradle"
        )
        if (informativeKeywords.any { q.contains(it) || r.contains(it) }) return true
        
        // - General length check: If the user query is reasonably long and informative, or the model response is highly detailed
        if (q.length > 50 || r.length > 500) return true
        
        return false
    }

    private fun isGreeting(text: String): Boolean {
        val clean = text.trim().lowercase().replace(Regex("[^a-zA-Z0-9\\s\\u0980-\\u09FF\\u0900-\\u097F]"), "")
        if (clean.isBlank()) return false
        val greetings = setOf(
            "hi", "hello", "hey", "hola", "greetings", "good morning", "good afternoon", "good evening", "howdy",
            "hi there", "hello there", "namaste", "pranam", "namaskar",
            // Bengali
            "হ্যালো", "হাই", "নমস্কার", "প্রণাম", "শুভ সকাল", "শুভ দুপুর", "শুভ সন্ধ্যা", "শুভ রাত্রি", "কেমন আছো", "কেমন আছেন",
            // Hindi
            "नमस्ते", "नमस्कार", "प्रणाम", "शुभ प्रभात", "शुभ दोपहर", "शुभ संध्या", "शुभ रात्रि", "कैसे हो", "कैसे हैं", "हेलो"
        )
        return clean in greetings || greetings.any { clean == it || clean.startsWith(it + " ") }
    }

    private fun isWeatherOrNewsQuery(text: String): Boolean {
        val clean = text.lowercase().trim()
        val keywords = listOf(
            "weather", "temperature", "forecast", "rain", "sunny", "climate", "celsius", "fahrenheit", "wind", "humidity",
            "news", "headline", "current event", "breaking news", "latest update",
            // Bengali
            "আবহাওয়া", "আবহাওয়া", "তাপমাত্রা", "বৃষ্টি", "খবর", "সংবাদ", "শিরোনাম",
            // Hindi
            "मौसम", "तापमान", "बारिश", "समाचार", "खबर", "सुर्खियां"
        )
        val words = clean.split("""\s+""".toRegex()).map { it.replace("""[^\w]""".toRegex(), "") }
        return keywords.any { keyword ->
            if (keyword.all { it.code in 0..127 }) {
                words.contains(keyword)
            } else {
                clean.contains(keyword)
            }
        }
    }

    private fun isShortAffirmation(text: String): Boolean {
        val clean = text.trim().lowercase().replace(Regex("[^a-z0-9\\u0980-\\u09FF\\u0900-\\u097F]"), "")
        val affirmations = setOf(
            "ok", "okay", "understood", "gotit", "thanks", "thankyou", "fine", "cool", "perfect", "done", "gotitthanks",
            // Bengali affirmations
            "ঠিকআছে", "বুঝেছি", "ধন্যবাদ", "ওকে",
            // Hindi affirmations
            "ठीकहै", "समझगया", "धन्यवाद", "ओके"
        )
        return clean in affirmations || (clean.length <= 12 && affirmations.any { clean.contains(it) })
    }
}
