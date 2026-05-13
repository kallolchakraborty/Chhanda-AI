package com.chhanda.ai.presentation.viewmodel

import com.chhanda.ai.data.repository.MessageEntity

/**
 * Represents the UI state for the Chat Screen.
 */
data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val currentPartialResponse: String = "",
    val error: String? = null,
    val isModelLoaded: Boolean = false,
    val isModelLoading: Boolean = false,
    val currentTps: Double = 0.0,
    val currentRt: Long = 0
)
