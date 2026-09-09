package me.rerere.rikkahub.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputStateTest {
    @Test
    fun `attachment-only input is not empty`() {
        val state = ChatInputState()
        state.messageContent = listOf(UIMessagePart.Image("file:///tmp/image.png"))

        assertFalse(state.isEmpty())
    }

    @Test
    fun `blank input without attachments is empty`() {
        val state = ChatInputState()
        state.setMessageText("   ")

        assertTrue(state.isEmpty())
    }
}
