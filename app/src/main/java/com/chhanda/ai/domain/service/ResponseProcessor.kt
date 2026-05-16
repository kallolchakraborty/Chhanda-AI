package com.chhanda.ai.domain.service

import com.chhanda.ai.domain.model.TokenUpdate
import com.chhanda.ai.util.PrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles real-time token processing, including thinking suppression, tag extraction, and PII redaction.
 */
@Singleton
class ResponseProcessor @Inject constructor(
    private val settingsRepository: com.chhanda.ai.data.repository.SettingsRepository
) {

    fun processStream(
        rawStream: Flow<TokenUpdate>,
        includeThinking: Boolean
    ): Flow<TokenUpdate> = flow {
        var isThinking = false
        val internalBuffer = StringBuilder()
        val privacyShieldEnabled = settingsRepository.privacyShieldEnabledFlow.first()

        rawStream.collect { update ->
            when (update) {
                is TokenUpdate.Partial -> {
                    internalBuffer.append(update.text)
                    if (!includeThinking) {
                        processThinkingSuppression(internalBuffer, isThinking, update.tps) { emittedText, thinkingState ->
                            val safeText = if (privacyShieldEnabled) PrivacyGuard.redact(emittedText) else emittedText
                            emit(TokenUpdate.Partial(safeText, update.tps))
                            isThinking = thinkingState
                        }
                    } else {
                        val content = internalBuffer.toString()
                        val safeText = if (privacyShieldEnabled) PrivacyGuard.redact(content) else content
                        emit(TokenUpdate.Partial(safeText, update.tps))
                        internalBuffer.setLength(0)
                    }
                }
                else -> emit(update)
            }
        }
    }

    private suspend fun processThinkingSuppression(
        buffer: StringBuilder,
        currentlyThinking: Boolean,
        tps: Double,
        onEmit: suspend (String, Boolean) -> Unit
    ) {
        var isThinking = currentlyThinking
        while (buffer.isNotEmpty()) {
            if (!isThinking) {
                val startMarkers = listOf("<thought>", "<think>", "Thinking...", "Thought:", "Reasoning:", "Reasoning Process:", "Chain of Thought:")
                var foundMarker: String? = null
                var markerIdx = -1

                for (m in startMarkers) {
                    val idx = buffer.indexOf(m, ignoreCase = true)
                    if (idx != -1 && (markerIdx == -1 || idx < markerIdx)) {
                        markerIdx = idx
                        foundMarker = m
                    }
                }

                if (foundMarker != null && markerIdx != -1) {
                    val before = buffer.substring(0, markerIdx)
                    if (before.isNotEmpty()) {
                        onEmit(before, false)
                    }
                    isThinking = true
                    buffer.delete(0, markerIdx + foundMarker.length)
                } else {
                    // Buffer management to allow for multi-token marker detection
                    if (buffer.length > 30) {
                        val toEmit = buffer.substring(0, buffer.length - 30)
                        onEmit(toEmit, false)
                        buffer.delete(0, buffer.length - 30)
                    }
                    break
                }
            } else {
                val endMarkers = listOf("</thought>", "</think>")
                var foundEnd: String? = null
                var endIdx = -1

                for (e in endMarkers) {
                    val idx = buffer.indexOf(e, ignoreCase = true)
                    if (idx != -1 && (endIdx == -1 || idx < endIdx)) {
                        endIdx = idx
                        foundEnd = e
                    }
                }

                if (foundEnd != null && endIdx != -1) {
                    isThinking = false
                    buffer.delete(0, endIdx + foundEnd.length)
                } else {
                    buffer.setLength(0)
                    break
                }
            }
        }
    }

    suspend fun cleanFinalResponse(text: String): ProcessedResponse {
        val thinkingRegex = """<(?:thought|think)>([\s\S]*?)</(?:thought|think)>""".toRegex()
        val thinkingMatch = thinkingRegex.find(text)
        val extractedThinking = thinkingMatch?.groupValues?.get(1)?.trim()

        var cleaned = text.replace(thinkingRegex, "").trim()
        
        val prefixesToStrip = listOf("Thinking...", "Thinking:", "Thought:", "Thought...")
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

        val privacyShieldEnabled = settingsRepository.privacyShieldEnabledFlow.first()
        if (privacyShieldEnabled) {
            cleaned = PrivacyGuard.redact(cleaned)
        }

        return ProcessedResponse(cleaned, extractedThinking)
    }

    data class ProcessedResponse(val text: String, val thinking: String?)
}
