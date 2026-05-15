package com.chhanda.ai.presentation.ui.chat.components

/**
 * Parses raw LLM output to separate thinking process from the actual response.
 */
fun parseMessageContent(rawText: String): Pair<String?, String> {
    val cleanedText = rawText
        .replace(Regex("<turn\\|?>"), "")
        .replace(Regex("<\\|channel>thought"), "")
        .replace(Regex("<\\|channel>"), "")
        .replace(Regex("<start_of_turn>"), "")
        .replace(Regex("<end_of_turn>"), "")
        .replace("\ufeff", "")
        .replace("\uFEFF", "")
        .replace("[UTF-8]", "")
        .replace("(UTF-8)", "")
        .replace("UTF-8: ", "")
        .replace("\u00A0", " ")
        .trim()

    var thinkingText: String? = null
    var responseText = cleanedText

    val thinkStartIndex = cleanedText.indexOf("<think>")
    val thoughtStartIndex = cleanedText.indexOf("<thought>")
    
    if (thinkStartIndex != -1) {
        val thinkEndIndex = cleanedText.indexOf("</think>", thinkStartIndex)
        if (thinkEndIndex != -1) {
            thinkingText = cleanedText.substring(thinkStartIndex + 7, thinkEndIndex).trim()
            responseText = (cleanedText.substring(0, thinkStartIndex) + cleanedText.substring(thinkEndIndex + 8)).trim()
        } else {
            thinkingText = cleanedText.substring(thinkStartIndex + 7).trim()
            responseText = cleanedText.substring(0, thinkStartIndex).trim()
        }
        return Pair(thinkingText, responseText)
    } else if (thoughtStartIndex != -1) {
        val thoughtEndIndex = cleanedText.indexOf("</thought>", thoughtStartIndex)
        if (thoughtEndIndex != -1) {
            thinkingText = cleanedText.substring(thoughtStartIndex + 9, thoughtEndIndex).trim()
            responseText = (cleanedText.substring(0, thoughtStartIndex) + cleanedText.substring(thoughtEndIndex + 10)).trim()
        } else {
            thinkingText = cleanedText.substring(thoughtStartIndex + 9).trim()
            responseText = cleanedText.substring(0, thoughtStartIndex).trim()
        }
        return Pair(thinkingText, responseText)
    }

    val thinkingMarkers = listOf("Thinking Process:", "Thought:")
    for (marker in thinkingMarkers) {
        val index = responseText.indexOf(marker)
        if (index != -1) {
            val endOfThinking = responseText.indexOf("\n\n", index)
            if (endOfThinking != -1) {
                thinkingText = responseText.substring(index + marker.length, endOfThinking).trim()
                responseText = (responseText.substring(0, index) + responseText.substring(endOfThinking)).trim()
            } else {
                thinkingText = responseText.substring(index + marker.length).trim()
                responseText = responseText.substring(0, index).trim()
            }
            break
        }
    }
    
    return Pair(thinkingText, responseText)
}

/**
 * Strips markdown and special tags for Text-to-Speech engines.
 */
fun cleanTextForTts(text: String): String {
    return text
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        .replace(Regex("\\*(.*?)\\*"), "$1")
        .replace(Regex("`(.*?)`"), "$1")
        .replace(Regex("```[\\s\\S]*?```"), " Please check the code block for details. ")
        .replace(Regex("\\[CREATE_FILE.*?\\][\\s\\S]*?\\[/CREATE_FILE\\]"), "")
        .replace(Regex("\\[GENERATE_FILE.*?\\][\\s\\S]*?\\[/GENERATE_FILE\\]"), "")
        .replace(Regex("#+\\s+"), "")
        .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        .replace(Regex("[_~>]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
