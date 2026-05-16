package com.chhanda.ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.domain.usecase.SendMessageUseCase
import com.chhanda.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val sendMessageUseCase: dagger.Lazy<SendMessageUseCase>,
    private val chatDao: ChatDao,
    private val settingsRepository: SettingsRepository,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao,
    private val voiceAssistant: com.chhanda.ai.util.VoiceAssistant,
    private val hapticManager: com.chhanda.ai.util.HapticManager
) : ViewModel() {
    private val llmEngine get() = llmEngineLazy.get()

    val modelName: String = savedStateHandle["modelName"] ?: "unknown"
    val sessionId: String = savedStateHandle["sessionId"] ?: java.util.UUID.randomUUID().toString()

    private val _isGenerating = MutableStateFlow(false)
    private val _currentPartialResponse = MutableStateFlow("")
    private val _currentTps = MutableStateFlow(0.0)
    private val _currentRt = MutableStateFlow(0L)
    private val _error = MutableStateFlow<String?>(null)
    private val _selectedPersona = MutableStateFlow<String?>(null)
    private val _agentStatus = MutableStateFlow<String?>(null)
    private var messageJob: kotlinx.coroutines.Job? = null

    private val _selectedFiles = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val selectedFiles: StateFlow<List<android.net.Uri>> = _selectedFiles

    val voiceListening = voiceAssistant.isListening
    val voiceResult = voiceAssistant.partialResult
    val voiceError = voiceAssistant.error

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    init {
        loadSuggestions()
    }

    fun loadSuggestions() {
        viewModelScope.launch {
            try {
                val uniqueSources = vectorChunkDao.getDistinctSources(5)
                _suggestions.value = uniqueSources.mapIndexed { index, source ->
                    val cleanSource = if (source.startsWith("http")) {
                        try {
                            java.net.URL(source).host
                        } catch (e: Exception) {
                            "this online resource"
                        }
                    } else {
                        source
                    }

                    when (index % 3) {
                        0 -> "Can you provide a summary of $cleanSource?"
                        1 -> "What are the key topics discussed in $cleanSource?"
                        else -> "Tell me more about the information from $cleanSource."
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    val messages: StateFlow<List<com.chhanda.ai.data.repository.MessageEntity>> =
        chatDao.getMessagesForSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ChatUiState> = combine(
        messages, _isGenerating, _currentPartialResponse, _currentTps, _currentRt, _error, _selectedPersona, _agentStatus
    ) { args: Array<Any?> ->
        ChatUiState(
            messages = args[0] as List<com.chhanda.ai.data.repository.MessageEntity>,
            isGenerating = args[1] as Boolean,
            currentPartialResponse = args[2] as String,
            currentTps = args[3] as Double,
            currentRt = args[4] as Long,
            error = args[5] as String?,
            selectedPersona = args[6] as String?,
            agentStatus = args[7] as String?,
            isModelLoaded = llmEngine.isModelLoaded(),
            isModelLoading = llmEngine.isModelLoading()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    val appLanguage = settingsRepository.appLanguageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "English")

    fun addFile(uri: android.net.Uri) {
        _selectedFiles.value = (_selectedFiles.value + uri).take(3)
    }

    fun removeFile(uri: android.net.Uri) {
        _selectedFiles.value = _selectedFiles.value - uri
    }

    fun sendMessage(text: String, isRefinement: Boolean = false) {
        if (text.isBlank() || _isGenerating.value) return
        
        hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.LIGHT_TICK)

        if (!llmEngine.isModelLoaded()) {
            _error.value = "No model loaded. Go to Dashboard and activate a model first."
            return
        }

        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            val attachments = _selectedFiles.value
            _isGenerating.value = true
            _currentPartialResponse.value = ""
            _currentTps.value = 0.0
            _agentStatus.value = "Preparing workspace..."
            _error.value = null
            _selectedFiles.value = emptyList()

            var buffer = ""
            var lastUpdate = System.currentTimeMillis()
            val language = appLanguage.value

            val promptToSend = if (isRefinement) {
                """
                ### TRANSCRIPT REFINEMENT TASK
                Please polish the following raw spoken transcript into professional, well-structured text. 
                - Fix grammar and punctuation.
                - Remove filler words (like "um", "uh", "you know").
                - Improve sentence flow and clarity.
                - Keep the original tone and all key information.
                - Respond ONLY with the polished text.

                RAW TRANSCRIPT:
                "$text"
                """.trimIndent()
            } else {
                text
            }

            val thinkingMode = settingsRepository.thinkingModeEnabledFlow.first()

            sendMessageUseCase.get()(
                userText = promptToSend, 
                deviceId = "local", 
                modelName = modelName, 
                sessionId = sessionId, 
                attachments = attachments, 
                preferredLanguage = language, 
                isRefinement = isRefinement, 
                source = "device",
                persona = _selectedPersona.value,
                includeThinking = thinkingMode
            )
                .onCompletion { _isGenerating.value = false }
                .collect { update ->
                    when (update) {
                        is TokenUpdate.Partial -> {
                            buffer += update.text
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                _currentPartialResponse.value += buffer
                                _currentTps.value = update.tps
                                _agentStatus.value = null // Clear status when generation starts
                                buffer = ""
                                lastUpdate = now
                                hapticManager.streamingTick()
                            }
                        }
                        is TokenUpdate.Status -> {
                            _agentStatus.value = update.message
                        }
                        is TokenUpdate.Final -> {
                            if (buffer.isNotEmpty()) {
                                _currentPartialResponse.value += buffer
                                buffer = ""
                            }
                            _currentRt.value = update.responseTimeMs
                            _currentPartialResponse.value = ""
                            _currentTps.value = 0.0

                        }
                        is TokenUpdate.Error -> {
                            _error.value = update.message
                            _isGenerating.value = false
                            hapticManager.play(com.chhanda.ai.util.HapticManager.HapticPattern.ERROR_PULSE)
                        }
                    }
                }
        }
    }

    fun refineText(text: String) {
        sendMessage(text, isRefinement = true)
    }

    fun stopInference() {
        messageJob?.cancel()
        llmEngine.stopInference()
        _isGenerating.value = false
        _currentPartialResponse.value = "" 
        _currentTps.value = 0.0
    }

    fun clearHistory() {
        viewModelScope.launch { chatDao.deleteSession(sessionId) }
    }

    fun dismissError() { _error.value = null }

    fun startVoiceInput() {
        viewModelScope.launch {
            val lang = appLanguage.value
            val langCode = when(lang) {
                "Bengali" -> "bn-BD"
                "Hindi" -> "hi-IN"
                else -> "en-US"
            }
            voiceAssistant.startListening(langCode)
        }
    }

    fun stopVoiceInput() {
        voiceAssistant.stopListening()
    }

    fun clearVoiceResult() {
        voiceAssistant.clearResults()
    }

    fun setPersona(persona: String?) {
        _selectedPersona.value = persona
    }

    override fun onCleared() {
        super.onCleared()

    }
}
