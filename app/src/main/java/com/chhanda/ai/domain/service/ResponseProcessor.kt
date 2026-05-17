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
                        isThinking = processThinkingSuppression(internalBuffer, isThinking, update.tps) { emittedText ->
                            val safeText = if (privacyShieldEnabled) PrivacyGuard.redact(emittedText) else emittedText
                            emit(TokenUpdate.Partial(safeText, update.tps))
                        }
                    } else {
                        // includeThinking mode: emit the raw delta directly (no buffering needed)
                        val safeText = if (privacyShieldEnabled) PrivacyGuard.redact(update.text) else update.text
                        emit(TokenUpdate.Partial(safeText, update.tps))
                    }
                }
                else -> {
                    if (!includeThinking && internalBuffer.isNotEmpty() && !isThinking) {
                        val remaining = internalBuffer.toString()
                        val safeText = if (privacyShieldEnabled) PrivacyGuard.redact(remaining) else remaining
                        emit(TokenUpdate.Partial(safeText, 0.0))
                        internalBuffer.setLength(0)
                    }
                    emit(update)
                }
            }
        }
    }

    private suspend fun processThinkingSuppression(
        buffer: StringBuilder,
        currentlyThinking: Boolean,
        tps: Double,
        onEmit: suspend (String) -> Unit
    ): Boolean {
        var isThinking = currentlyThinking
        while (buffer.isNotEmpty()) {
            if (!isThinking) {
                 // 1. Fuzzy search for tags starting with "<thought" or "<think"
                 val tagMarkers = listOf("<thoughtintsns", "<thoughtintssy", "<thoughtint", "<thought", "<think")
                 var markerIdx = -1
                 var matchedLen = 0

                 for (m in tagMarkers) {
                     val idx = buffer.indexOf(m, ignoreCase = true)
                     if (idx != -1 && (markerIdx == -1 || idx < markerIdx)) {
                         markerIdx = idx
                         val closeIdx = buffer.indexOf(">", idx)
                         matchedLen = if (closeIdx != -1) {
                             (closeIdx - idx) + 1
                         } else {
                             m.length
                         }
                     }
                 }

                 // 2. Fallback to other static start markers
                 if (markerIdx == -1) {
                     val otherMarkers = listOf("Thinking...", "Thought:", "Reasoning:", "Reasoning Process:", "Chain of Thought:")
                     for (m in otherMarkers) {
                         val idx = buffer.indexOf(m, ignoreCase = true)
                         if (idx != -1 && (markerIdx == -1 || idx < markerIdx)) {
                             markerIdx = idx
                             matchedLen = m.length
                         }
                     }
                 }

                 if (markerIdx != -1) {
                     val before = buffer.substring(0, markerIdx)
                     if (before.isNotEmpty()) {
                         onEmit(before)
                     }
                     isThinking = true
                     buffer.delete(0, markerIdx + matchedLen)
                 } else {
                     // Keep at most 32 characters in buffer to allow for partial multi-token start-marker detection (snappy stream)
                     if (buffer.length > 32) {
                         val toEmit = buffer.substring(0, buffer.length - 32)
                         onEmit(toEmit)
                         buffer.delete(0, buffer.length - 32)
                     }
                     break
                 }
             } else {
                 val endMarkers = listOf(
                     "</thoughtintsns>", "</thoughtintssy>", "</thoughtintsns", "</thoughtintssy",
                     "</thoughtint>", "</thought>", "</think>", "</thought", "</think"
                 )
                var foundEnd: String? = null
                var endIdx = -1
                var matchedLen = 0

                for (e in endMarkers) {
                    val idx = buffer.indexOf(e, ignoreCase = true)
                    if (idx != -1 && (endIdx == -1 || idx < endIdx)) {
                        endIdx = idx
                        foundEnd = e
                        val closeIdx = buffer.indexOf(">", idx)
                        matchedLen = if (closeIdx != -1) {
                            (closeIdx - idx) + 1
                        } else {
                            e.length
                        }
                    }
                }

                if (endIdx != -1) {
                    isThinking = false
                    buffer.delete(0, endIdx + matchedLen)
                } else {
                    // Keep the last 100 characters of the buffer to protect split end-markers (e.g. "</" and "thought>") across stream packets
                    val maxEndMarkerLen = 100
                    if (buffer.length > maxEndMarkerLen) {
                        buffer.delete(0, buffer.length - maxEndMarkerLen)
                    }
                    break
                }
            }
        }
        return isThinking
    }

    suspend fun cleanFinalResponse(text: String): ProcessedResponse {
        var cleaned = text
        var extractedThinking: String? = null

        // Super-robust regex to match any start/end thinking tags including gemma specific thoughtintsns, thoughtintssy, etc.
        val startRegex = """<(?:thought|think)[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
        val endRegex = """</(?:thought|think)[^>]*>""".toRegex(RegexOption.IGNORE_CASE)

        val startMatch = startRegex.find(text)
        if (startMatch != null) {
            val startIdx = startMatch.range.first
            val endMatch = endRegex.find(text, startIdx)
            if (endMatch != null) {
                val endIdx = endMatch.range.first
                extractedThinking = text.substring(startIdx + startMatch.value.length, endIdx).trim()
                cleaned = (text.substring(0, startIdx) + text.substring(endIdx + endMatch.value.length)).trim()
            } else {
                // If there is no closing tag, assume the entire rest of the text is thinking
                extractedThinking = text.substring(startIdx + startMatch.value.length).trim()
                cleaned = text.substring(0, startIdx).trim()
            }
        }

        // Proactively strip any raw leftovers of unmatched thought tags that could have leaked
        val cleanupRegex = """</?(?:thought|think)[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
        cleaned = cleaned.replace(cleanupRegex, "").trim()

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
