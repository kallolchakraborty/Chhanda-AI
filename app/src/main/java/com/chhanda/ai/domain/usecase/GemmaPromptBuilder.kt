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
            .append("Task:\n")
            .append("Answer the user’s request using the fewest tokens possible while staying correct.\n\n")
            .append("Output rules:\n")
            .append("- Be concise.\n")
            .append("- Prefer bullet points or short paragraphs.\n")
            .append("- Avoid long explanations unless asked.\n")
            .append("- If producing structured data, use valid JSON only.\n")
            .append("- Do not add extra commentary.\n")
            .append("- Keep the response deterministic and focused.\n")
            .append("- If the request is ambiguous, ask one short clarifying question.\n\n")
        
        if (context.isNotEmpty()) {
            stringBuilder.append("Context:\n")
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
