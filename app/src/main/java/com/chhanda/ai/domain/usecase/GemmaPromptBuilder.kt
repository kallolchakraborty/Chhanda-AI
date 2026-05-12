package com.chhanda.ai.domain.usecase

/**
 * Handles the construction of prompts tailored for the Gemma model.
 * Adheres to the specific control tokens required for instruction tuning.
 */
object GemmaPromptBuilder {

    private const val START_TURN = "<start_of_turn>"
    private const val END_TURN = "<end_of_turn>"
    private const val USER_ROLE = "user"
    private const val MODEL_ROLE = "model"

    /**
     * Constructs a prompt from a user message and optional context history.
     * 
     * @param userMessage The current input from the user.
     * @param history A list of previous turns (Pair of Role to Message).
     * @return A fully formatted prompt string for Gemma.
     */
    fun buildPrompt(userMessage: String, history: List<Pair<String, String>> = emptyList()): String {
        val stringBuilder = StringBuilder()

        // Append historical context
        history.forEach { (role, text) ->
            stringBuilder.append(START_TURN)
                .append(role)
                .append("\n")
                .append(text)
                .append(END_TURN)
                .append("\n")
        }

        // Append current user message
        stringBuilder.append(START_TURN)
            .append(USER_ROLE)
            .append("\n")
            .append(userMessage)
            .append(END_TURN)
            .append("\n")

        // Append model start token to trigger generation
        stringBuilder.append(START_TURN)
            .append(MODEL_ROLE)
            .append("\n")
        return stringBuilder.toString()
    }

    /**
     * Constructs an augmented prompt for RAG scenarios with optimized inference instructions.
     */
    fun buildAugmentedPrompt(
        userMessage: String, 
        history: List<Pair<String, String>>, 
        context: String
    ): String {
        val stringBuilder = StringBuilder()

        stringBuilder.append(START_TURN)
            .append("system")
            .append("\n")
            .append("You are a fast, precise assistant.\n\n")
            .append("You will receive retrieved context from a RAG system.\n")
            .append("Use the retrieved context as the primary source of truth.\n")
            .append("If the context is sufficient, answer directly.\n")
            .append("If the context is insufficient or contradictory, say so briefly and ask for more context.\n\n")
            .append("Output rules:\n")
            .append("- Be concise.\n")
            .append("- Prefer bullets or short paragraphs.\n")
            .append("- Do not invent facts not supported by context.\n")
            .append("- If the answer depends on retrieved text, cite the relevant context internally in your reasoning, but do not mention hidden chain-of-thought.\n")
            .append("- If the request is ambiguous, ask one short clarifying question.\n")
            .append("- If the task is structured, return valid JSON only.\n\n")
        
        if (context.isNotEmpty()) {
            stringBuilder.append("RAG context:\n")
                .append(context)
                .append("\n\n")
        }
        
        stringBuilder.append(END_TURN)
            .append("\n")

        // Append historical context
        history.forEach { (role, text) ->
            stringBuilder.append(START_TURN)
                .append(role)
                .append("\n")
                .append(text)
                .append(END_TURN)
                .append("\n")
        }

        // Append current user message
        stringBuilder.append(START_TURN)
            .append(USER_ROLE)
            .append("\n")
            .append("User request:\n")
            .append(userMessage)
            .append(END_TURN)
            .append("\n")

        // Append model start token
        stringBuilder.append(START_TURN)
            .append(MODEL_ROLE)
            .append("\n")
            return stringBuilder.toString()
    }
}
