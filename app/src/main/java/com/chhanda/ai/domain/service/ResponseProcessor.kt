package com.chhanda.ai.domain.service

import com.chhanda.ai.domain.model.TokenUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles real-time token processing, including thinking suppression and tag extraction.
 */
@Singleton
class ResponseProcessor @Inject constructor() {

    fun processStream(
        rawStream: Flow<TokenUpdate>,
        includeThinking: Boolean
    ): Flow<TokenUpdate> = flow {
        var isThinking = false
        val internalBuffer = StringBuilder()

        rawStream.collect { update ->
            when (update) {
                is TokenUpdate.Partial -> {
                    internalBuffer.append(update.text)
                    if (!includeThinking) {
                        processThinkingSuppression(internalBuffer, isThinking, update.tps) { emittedText, thinkingState ->
                            emit(TokenUpdate.Partial(emittedText, update.tps))
                            isThinking = thinkingState
                        }
                    } else {
                        val content = internalBuffer.toString()
                        emit(TokenUpdate.Partial(content, update.tps))
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
                    if (buffer.length > 20) {
                        val toEmit = buffer.substring(0, buffer.length - 20)
                        onEmit(toEmit, false)
                        buffer.delete(0, buffer.length - 20)
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

    fun cleanFinalResponse(text: String): ProcessedResponse {
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

        return ProcessedResponse(cleaned, extractedThinking)
    }

    data class ProcessedResponse(val text: String, val thinking: String?)
}
