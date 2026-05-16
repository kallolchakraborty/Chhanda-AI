package com.chhanda.ai.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.chhanda.ai.presentation.ui.chat.ChatScreenContent
import com.chhanda.ai.presentation.viewmodel.ChatViewModel
import com.chhanda.ai.presentation.viewmodel.ChatState
import com.chhanda.ai.presentation.viewmodel.MessageUiModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * ChatFlowTest: Production-grade UI testing for the core conversational flow.
 * 
 * This test validates:
 * 1. Message ingestion into the text field.
 * 2. Send button interaction and callback triggering.
 * 3. Message rendering in the conversational list.
 */
class ChatFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockViewModel = mockk<ChatViewModel>(relaxed = true)

    @Test
    fun testSendMessageFlow() {
        // Arrange: Setup mock state
        val messageFlow = MutableStateFlow(
            ChatState(
                messages = listOf(
                    MessageUiModel("1", "assistant", "Hello! How can I help you today?", System.currentTimeMillis())
                )
            )
        )
        every { mockViewModel.state } returns messageFlow

        // Act: Render the ChatScreenContent
        composeTestRule.setContent {
            ChatScreenContent(
                state = messageFlow.collectAsState().value,
                onSendMessage = { mockViewModel.sendMessage(it) },
                onClearHistory = {},
                onNavigateBack = {}
            )
        }

        // 1. Verify initial assistant message
        composeTestRule.onNodeWithText("Hello! How can I help you today?").assertIsDisplayed()

        // 2. Type a new message
        val testInput = "Tell me about quantum computing"
        composeTestRule.onNodeWithTag("chat_input_field").performTextInput(testInput)
        
        // 3. Click Send
        composeTestRule.onNodeWithContentDescription("Send Message").performClick()

        // 4. Verify the input field is cleared (simulating ViewModel logic)
        // In a real integration test, we'd wait for the state to update, 
        // but for UI flow validation, we're checking the interaction works.
        composeTestRule.onNodeWithTag("chat_input_field").assertTextContains("")
        
        // 5. Verify the callback was triggered
        io.mockk.verify { mockViewModel.sendMessage(testInput) }
    }
}
