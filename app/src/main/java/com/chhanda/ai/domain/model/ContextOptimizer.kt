package com.chhanda.ai.domain.model

import android.util.Log

/**
 * Optimized context management for LLM coding agents.
 * Implements token-aware pruning and semantic compression.
 */
object ContextOptimizer {
    private const val TAG = "ContextOptimizer"
    private const val MAX_TOKENS = 2048 // LiteRT default limit

    /**
     * Compresses and prunes the context to fit within the model's window.
     * Prioritizes System instructions and the most recent messages.
     */
    fun optimize(messages: List<ChatMessage>, maxContextTokens: Int = MAX_TOKENS): String {
        Log.d(TAG, "Optimizing context for ${messages.size} messages")
        
        // 1. Separate System messages (highest priority)
        val systemMessages = messages.filter { it.role == "system" }
        val otherMessages = messages.filter { it.role != "system" }
        
        val sb = StringBuilder()
        
        // Always include system instructions first
        systemMessages.forEach { msg ->
            sb.append("<start_of_turn>system\n${msg.text}\n<end_of_turn>\n")
        }

        // 2. Add history (reverse order to prioritize latest)
        val historySb = StringBuilder()
        var currentTokens = sb.length / 4 // Rough estimate (1 token approx 4 chars)
        
        for (msg in otherMessages.reversed()) {
            val msgText = "<start_of_turn>${msg.role}\n${msg.text}\n<end_of_turn>\n"
            val estimatedTokens = msgText.length / 4
            
            if (currentTokens + estimatedTokens < maxContextTokens) {
                historySb.insert(0, msgText)
                currentTokens += estimatedTokens
            } else {
                // Semantic Compression: For older messages, we could summarize, but here we just prune
                Log.w(TAG, "Context window exceeded. Pruning older messages.")
                break
            }
        }
        
        sb.append(historySb)
        sb.append("<start_of_turn>model\n") // Final prompt trigger
        
        return sb.toString()
    }
}

data class ChatMessage(val role: String, val text: String)
