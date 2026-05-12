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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val sendMessageUseCase: dagger.Lazy<SendMessageUseCase>,
    private val chatDao: ChatDao,
    private val settingsRepository: SettingsRepository,
    private val llmEngineLazy: dagger.Lazy<LLMEngine>,
    private val vectorChunkDao: com.chhanda.ai.data.repository.VectorChunkDao
) : ViewModel() {
    private val llmEngine get() = llmEngineLazy.get()
    
    val modelName: String = savedStateHandle["modelName"] ?: "unknown"
    val sessionId: String = savedStateHandle["sessionId"] ?: java.util.UUID.randomUUID().toString()

    private val _isGenerating = MutableStateFlow(false)
    private val _currentPartialResponse = MutableStateFlow("")
    private val _currentTps = MutableStateFlow(0.0)
    private val _error = MutableStateFlow<String?>(null)
    private var messageJob: kotlinx.coroutines.Job? = null

    private val _selectedFiles = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val selectedFiles: StateFlow<List<android.net.Uri>> = _selectedFiles

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    init {
        loadSuggestions()
    }

    fun loadSuggestions() {
        viewModelScope.launch {
            try {
                val chunks = vectorChunkDao.getAll()
                val uniqueSources = chunks.map { it.source }.distinct().take(5)
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
                // Ignore
            }
        }
    }

    val messages: StateFlow<List<com.chhanda.ai.data.repository.MessageEntity>> =
        chatDao.getMessagesForSession(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ChatUiState> = combine(
        messages, _isGenerating, _currentPartialResponse, _currentTps, _error
    ) { msgList, generating, partial, tps, error ->
        ChatUiState(
            messages = msgList,
            isGenerating = generating,
            currentPartialResponse = partial,
            currentTps = tps,
            error = error,
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

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return

        // Guard: model must be loaded before attempting inference
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
            _error.value = null
            _selectedFiles.value = emptyList()

            var buffer = ""
            var lastUpdate = System.currentTimeMillis()
            val language = appLanguage.value
            
            sendMessageUseCase.get()(text, "local", modelName, sessionId, attachments, preferredLanguage = language)
                .onCompletion { _isGenerating.value = false }
                .collect { update ->
                    when (update) {
                        is TokenUpdate.Partial -> {
                            buffer += update.text
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                _currentPartialResponse.value += buffer
                                _currentTps.value = update.tps
                                buffer = ""
                                lastUpdate = now
                            }
                        }
                        is TokenUpdate.Final -> {
                            // Clear streaming bubble — message is now in the DB list
                            _currentPartialResponse.value = ""
                            _currentTps.value = 0.0
                            // isGenerating will be set false by onCompletion
                        }
                        is TokenUpdate.Error -> {
                            _error.value = update.message
                            _isGenerating.value = false
                        }
                    }
                }
        }
    }

    fun stopInference() {
        messageJob?.cancel()
        llmEngine.stopInference()
        _isGenerating.value = false
        _currentPartialResponse.value = "" // Cleared because the DB will save and render the partial output via the use-case's finally block
        _currentTps.value = 0.0
    }

    fun clearHistory() {
        viewModelScope.launch { chatDao.clearHistory() }
    }

    fun dismissError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        // Ensure the engine is completely stopped when leaving the chat screen
        stopInference()
    }
}
