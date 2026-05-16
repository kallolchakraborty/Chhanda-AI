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

                if (llmEngine.isMultimodal() && attachments.any { it.toString().contains("image") }) {
                    append("VISION CAPABILITY: You have native vision processing enabled. Analyze the provided image attachments to answer the query accurately.\n")
                }

                append("GUARDRAILS: Redact PII (Emails, Phones, CC) in output. No hallucinations. No generic conversational filler.\n")
            }

            val systemInstruction = if (isRefinement) {
                "Professional editor mode. Polish the text in $preferredLanguage. Only return polished text."
            } else {
                "$baseInstructions\n\n$formatInstruction\n\n$agentCapabilities"
            }

            emit(com.chhanda.ai.domain.model.TokenUpdate.Status("Orchestrating model response..."))
            val responseFlow = llmEngine.generateResponse(prompt, history, systemInstruction, attachments)
            
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

                        val sourceTag = when {
                            hasAttachmentKnowledge && hasDbKnowledge -> "\n\n*(Ref: Multi-Source Context)*"
                            hasAttachmentKnowledge -> "\n\n*(Ref: Attached Documents)*"
                            hasDbKnowledge -> "\n\n*(Ref: Local Knowledge Base)*"
                            else -> ""
                        }

                        val toSave = cleanedResponse + if (isContextFound) sourceTag else ""

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
