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
            isContextFound = hasDbKnowledge 

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
                llmEngine.resetSession()
            }

            if (com.chhanda.ai.util.SafetyGuardrails.isPotentialInjection(userText)) {
                emit(com.chhanda.ai.domain.model.TokenUpdate.Error("Potential safety violation detected."))
                return@flow
            }

            val sanitizedUserText = com.chhanda.ai.util.SafetyGuardrails.sanitizeInput(userText)
            val attachmentContext = turnContextIngestor.processTurnContext(userText, attachments)
            if (attachmentContext.isNotBlank()) {
                hasAttachmentKnowledge = true
                isContextFound = true
            }

            val prompt = buildString {

                if (attachmentContext.isNotBlank()) {
                    append("<current_attachments>\n")
                    append(attachmentContext)
                    append("\n</current_attachments>\n\n")
                }

                if (longTermContext.isNotBlank()) {
                    append(longTermContext) 
                    append("\n\n")
                }

                append("<user_query>\n")
                append(sanitizedUserText)
                append("\n</user_query>")
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

            val baseInstructions = buildString {
                val role = when (source) {
                    "api", "qr" -> "Senior Technical Architect & Software Engineer"
                    else -> "Chhanda, a highly capable Senior AI Assistant"
                }
                val allowedPersonas = setOf("Senior Teacher", "Senior Software Engineer", "General Companion", "Friend", "Default")
                val validatedPersona = if (persona != null && persona in allowedPersonas && persona != "Default") persona else null
                append("IDENTITY: You are ${validatedPersona ?: role}. Respond in $preferredLanguage.\n")

                append("REASONING (CoVe): You MUST think step-by-step using a 'Chain of Verification' approach.\n")
                append("1. Analyze the user intent and constraints.\n")
                append("2. Retrieve and verify facts from provided context.\n")
                append("3. Plan the structure of the response.\n")
                append("4. Execute the final output. Wrap ALL reasoning in <thought> tags.\n")

                if (!includeThinking) {
                    append("USER PREFERENCE: The user has requested a compact response. While you MUST reason internally in <thought> tags, keep the final answer concise and direct.\n")
                }

                if (isContextFound) {
                    append("STRICT GROUNDING: The provided context is your SOURCE OF TRUTH. Do not use outside knowledge if it contradicts the context.\n")
                    append("UNSURE CASE: If the context doesn't contain the answer, say: 'Based on the provided documents, I don't have enough information.'\n")
                }

                append("GUARDRAILS: Redact PII (Emails, Phones, CC) in output. No hallucinations. No generic conversational filler.\n")
            }

            val systemInstruction = if (isRefinement) {
                "Professional editor mode. Polish the text in $preferredLanguage. Only return polished text."
            } else {
                "$baseInstructions\n\n$formatInstruction\n\n$agentCapabilities"
            }

            var isThinking = false
            val internalBuffer = StringBuilder()

            llmEngine.generateResponse(prompt, history, systemInstruction, attachments).collect { update ->
                when (update) {
                    is com.chhanda.ai.domain.model.TokenUpdate.Partial -> {
                        internalBuffer.append(update.text)

                        if (!includeThinking) {
                            while (internalBuffer.isNotEmpty()) {
                                if (!isThinking) {

                                    val startMarkers = listOf("<thought>", "<think>", "Thinking...", "Thought:", "Reasoning:", "Reasoning Process:", "Chain of Thought:")
                                    var foundMarker: String? = null
                                    var markerIdx = -1

                                    for (m in startMarkers) {
                                        val idx = internalBuffer.indexOf(m, ignoreCase = true)
                                        if (idx != -1 && (markerIdx == -1 || idx < markerIdx)) {
                                            markerIdx = idx
                                            foundMarker = m
                                        }
                                    }

                                    if (foundMarker != null && markerIdx != -1) {

                                        val before = internalBuffer.substring(0, markerIdx)
                                        if (before.isNotEmpty()) {
                                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(before, update.tps))
                                            partialAccumulated += before
                                        }
                                        isThinking = true
                                        internalBuffer.delete(0, markerIdx + foundMarker.length)
                                    } else {

                                        if (internalBuffer.length > 20) {
                                            val toEmit = internalBuffer.substring(0, internalBuffer.length - 20)
                                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(toEmit, update.tps))
                                            partialAccumulated += toEmit
                                            internalBuffer.delete(0, internalBuffer.length - 20)
                                        }
                                        break 
                                    }
                                } else {

                                    val endMarkers = listOf("</thought>", "</think>")
                                    var foundEnd: String? = null
                                    var endIdx = -1

                                    for (e in endMarkers) {
                                        val idx = internalBuffer.indexOf(e, ignoreCase = true)
                                        if (idx != -1 && (endIdx == -1 || idx < endIdx)) {
                                            endIdx = idx
                                            foundEnd = e
                                        }
                                    }

                                    if (foundEnd != null && endIdx != -1) {
                                        isThinking = false
                                        internalBuffer.delete(0, endIdx + foundEnd.length)
                                    } else {

                                        internalBuffer.setLength(0)
                                        break
                                    }
                                }
                            }
                        } else {

                            val content = internalBuffer.toString()
                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(content, update.tps))
                            partialAccumulated += content
                            internalBuffer.setLength(0)
                        }
                    }
                    is com.chhanda.ai.domain.model.TokenUpdate.Final -> {
                        if (internalBuffer.isNotEmpty() && !isThinking) {
                            val content = internalBuffer.toString()
                            partialAccumulated += content
                            emit(com.chhanda.ai.domain.model.TokenUpdate.Partial(content, update.tps))
                        }

                        val thinkingRegex = """<(?:thought|think)>([\s\S]*?)</(?:thought|think)>""".toRegex()
                        val thinkingMatch = thinkingRegex.find(partialAccumulated)
                        val extractedThinking = thinkingMatch?.groupValues?.get(1)?.trim()

                        var cleanedResponse = partialAccumulated.replace(thinkingRegex, "").trim()

                        val sourceTag = when {
                            hasAttachmentKnowledge && hasDbKnowledge -> "\n\n*(Ref: Multi-Source Context)*"
                            hasAttachmentKnowledge -> "\n\n*(Ref: Attached Documents)*"
                            hasDbKnowledge -> "\n\n*(Ref: Local Knowledge Base)*"
                            else -> ""
                        }

                        val prefixesToStrip = listOf("Thinking...", "Thinking:", "Thought:", "Thought...")
                        var changed = true
                        while (changed) {
                            changed = false
                            for (prefix in prefixesToStrip) {
                                if (cleanedResponse.startsWith(prefix, ignoreCase = true)) {
                                    cleanedResponse = cleanedResponse.substring(prefix.length).trim()
                                    changed = true
                                }
                            }
                        }

                        val toSave = cleanedResponse + if (isContextFound) sourceTag else ""

                        if (toSave.isNotBlank() || !extractedThinking.isNullOrBlank()) {

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
                                    source = source,
                                    thinking = extractedThinking
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
            // Load balancer logic removed

            if (!saved && partialAccumulated.trim().isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    chatDao.insertMessage(com.chhanda.ai.data.repository.MessageEntity(text = partialAccumulated.trim(), role = "model", deviceId = deviceId, modelName = modelName, sessionId = sessionId, isRagUsed = isContextFound))
                    contextManager.maintainMemoryHygiene()
                }
            }
        }
    }
}

