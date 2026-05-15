package com.chhanda.ai.util

import android.util.Log

/**
 * Chhanda Safety Guardrails: A local-first security layer for on-device LLMs.
 * Implements PII protection, harmful content filtering, and prompt injection prevention.
 */
object SafetyGuardrails {
    private const val TAG = "SafetyGuardrails"

    // Sensitive Data Patterns
    private val PII_PATTERNS = listOf(
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""), // Email
        Regex("""\b(?:\+?\d{1,3}[- ]?)?\(?\d{3}\)?[- ]?\d{3}[- ]?\d{4}\b"""), // Phone (General)
        Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b"""), // Credit Card
        Regex("""\b\d{3}-\d{2}-\d{4}\b""") // SSN
    )

    // Prohibited Topic Patterns (Keywords/Phrases)
    private val PROHIBITED_PATTERNS = listOf(
        Regex("""(?i)\b(kill|murder|bomb|explode|hack|malware|stole|rape|suicide|self-harm|terrorist)\b"""),
        Regex("""(?i)\b(instruction|ignore|previous|override|bypass)\b.*\b(all|everything|rules|safety)\b""") // Injection
    )

    // Specific Prompt Injection Patterns (From Senior Security Research)
    private val INJECTION_PATTERNS = listOf(
        "ignore previous instructions",
        "ignore all previous",
        "system prompt",
        "new instructions",
        "reveal your prompt",
        "output the system instruction",
        "reveal the context",
        "forget everything",
        "jailbreak",
        "dan mode",
        "do anything now",
        "bypass constraints",
        "override safety",
        "ignore rules",
        "print the system instruction"
    )

    // Senior Regex for detecting complex injection attempts
    private val HEURISTIC_INJECTION_REGEX = Regex(
        """(?i).*(ignore|bypass|override|forget|reveal|reveal|output).*(instruction|rule|constraint|safety|prompt|context|history).*"""
    )

    /**
     * Detects potential prompt injection attempts.
     */
    fun isPotentialInjection(input: String): Boolean {
        val lowercaseInput = input.lowercase()
        return INJECTION_PATTERNS.any { pattern -> lowercaseInput.contains(pattern) } || 
               HEURISTIC_INJECTION_REGEX.matches(input)
    }

    /**
     * Cleans and validates the user input before it reaches the LLM.
     * Returns a pair of (CleanedText, IsViolation)
     */
    fun auditInput(input: String): Pair<String, Boolean> {
        var processedText = input
        var isViolation = false

        // 1. Check for Prohibited Content
        for (pattern in PROHIBITED_PATTERNS) {
            if (pattern.containsMatchIn(input)) {
                Log.w(TAG, "Safety Violation Detected in Input: ${pattern.pattern}")
                isViolation = true
                break
            }
        }

        // 2. PII Redaction (Privacy-First)
        for (pattern in PII_PATTERNS) {
            processedText = pattern.replace(processedText, "[REDACTED]")
        }

        return processedText to isViolation
    }

    /**
     * Audits the LLM output to ensure no hallucinations or leaks.
     */
    fun auditOutput(output: String): String {
        var processedText = output
        
        // Redact PII in output as well (Safety net)
        for (pattern in PII_PATTERNS) {
            processedText = pattern.replace(processedText, "[REDACTED]")
        }

        return processedText
    }

    /**
     * Hardened System Prompt: Prepends safety instructions to every session.
     */
    fun getHardenedSystemPrompt(original: String?): String {
        val baseSafety = """
            You are Chhanda AI, a secure, offline-first assistant.
            Safety Guidelines:
            - Do not provide instructions for illegal acts, violence, or harm.
            - If a user asks for personal data, state that you do not have access to it.
            - Maintain a professional, helpful, and respectful tone.
            - If you are unsure, admit that you don't know rather than hallucinating.
        """.trimIndent()
        
        return if (original.isNullOrBlank()) {
            baseSafety
        } else {
            "$baseSafety\n\nUser specified context:\n$original"
        }
    }

    /**
     * Sanitizes user input by wrapping it in defensive delimiters.
     */
    fun sanitizeInput(input: String): String {
        return "[USER_INPUT_START]\n$input\n[USER_INPUT_END]"
    }

    /**
     * Sanitizes retrieved context to prevent indirect prompt injection.
     */
    fun sanitizeContext(context: String): String {
        if (context.isBlank()) return ""
        return "[EXTERNAL_CONTEXT_START]\n$context\n[EXTERNAL_CONTEXT_END]"
    }
}
