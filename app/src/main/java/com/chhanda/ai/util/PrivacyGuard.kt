package com.chhanda.ai.util

import java.util.regex.Pattern

/**
 * Utility to identify and redact Personally Identifiable Information (PII) from text.
 */
object PrivacyGuard {

    private val EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        Pattern.CASE_INSENSITIVE
    )

    // Supports various international and local formats
    private val PHONE_PATTERN = Pattern.compile(
        "\\+?[0-9]{1,4}?[-. ]?\\(?[0-9]{1,4}?\\)?[-. ]?[0-9]{1,4}[-. ]?[0-9]{1,9}",
        Pattern.CASE_INSENSITIVE
    )

    // Standard credit card patterns (Visa, Mastercard, Amex, Discover, etc.)
    private val CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b"
    )

    // IP Addresses (IPv4)
    private val IP_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
    )

    /**
     * Redacts PII from the provided text using standard masks.
     */
    fun redact(text: String): String {
        var redacted = text
        
        redacted = EMAIL_PATTERN.matcher(redacted).replaceAll("[EMAIL_REDACTED]")
        redacted = CREDIT_CARD_PATTERN.matcher(redacted).replaceAll("[CREDIT_CARD_REDACTED]")
        
        // Phone numbers are tricky (don't want to redact common small numbers or years)
        // We check length to be safe
        val phoneMatcher = PHONE_PATTERN.matcher(redacted)
        val sb = StringBuilder()
        var lastEnd = 0
        while (phoneMatcher.find()) {
            val match = phoneMatcher.group()
            // Only redact if it looks like a real phone number (usually 7+ digits excluding symbols)
            val digitCount = match.filter { it.isDigit() }.length
            if (digitCount >= 7 && digitCount <= 15) {
                sb.append(redacted.substring(lastEnd, phoneMatcher.start()))
                sb.append("[PHONE_REDACTED]")
                lastEnd = phoneMatcher.end()
            }
        }
        sb.append(redacted.substring(lastEnd))
        redacted = sb.toString()

        // Redact IP addresses if they aren't localhost/loopback
        val ipMatcher = IP_PATTERN.matcher(redacted)
        val ipSb = StringBuilder()
        var lastIpEnd = 0
        while (ipMatcher.find()) {
            val ip = ipMatcher.group()
            if (ip != "127.0.0.1" && !ip.startsWith("192.168.")) {
                ipSb.append(redacted.substring(lastIpEnd, ipMatcher.start()))
                ipSb.append("[IP_REDACTED]")
                lastIpEnd = ipMatcher.end()
            }
        }
        ipSb.append(redacted.substring(lastIpEnd))
        redacted = ipSb.toString()

        return redacted
    }
}
