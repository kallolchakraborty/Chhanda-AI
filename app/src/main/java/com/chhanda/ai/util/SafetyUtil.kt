package com.chhanda.ai.util

object SafetyUtil {
    private val injectionPatterns = listOf(
        "ignore previous instructions",
        "ignore all previous",
        "system prompt",
        "new instructions",
        "disregard",
        "forget everything",
        "jailbreak",
        "do anything now",
        "dan mode",
        "output the system instruction",
        "reveal your prompt"
    )

    /**
     * Detects potential prompt injection attempts.
     * Returns true if the input looks suspicious.
     */
    fun isPotentialInjection(input: String): Boolean {
        val lowercaseInput = input.lowercase()
        return injectionPatterns.any { pattern -> lowercaseInput.contains(pattern) }
    }

    /**
     * Sanitizes user input by wrapping it in defensive delimiters 
     * and adding a safety prefix.
     */
    fun sanitizeInput(input: String): String {
        return """
            [USER_INPUT_START]
            $input
            [USER_INPUT_END]
        """.trimIndent()
    }

    /**
     * Sanitizes retrieved context to prevent indirect prompt injection.
     */
    fun sanitizeContext(context: String): String {
        if (context.isBlank()) return ""
        return """
            [EXTERNAL_CONTEXT_START]
            $context
            [EXTERNAL_CONTEXT_END]
        """.trimIndent()
    }
}
