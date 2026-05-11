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
     * Constructs an augmented prompt for RAG scenarios.
     */
    fun buildAugmentedPrompt(
        userMessage: String, 
        history: List<Pair<String, String>>, 
        context: String
    ): String {
        val stringBuilder = StringBuilder()

        // Inject Context as a System Instruction if present
        if (context.isNotEmpty()) {
            stringBuilder.append(START_TURN)
                .append("system")
                .append("\n")
                .append("Use the following pieces of context to answer the user's question. If you don't know the answer, just say that you don't know, don't try to make up an answer.\n\nContext:\n")
                .append(context)
                .append(END_TURN)
                .append("\n")
        }

        // Append historical context and current message
        return stringBuilder.append(buildPrompt(userMessage, history)).toString()
    }
}
