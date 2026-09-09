package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.OpenAIMessageMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `commentary joins reasoning and tools while the final answer stays content`() {
        val reasoning = UIMessagePart.Reasoning("Thinking")
        val commentary = UIMessagePart.Text("Checking", OpenAIMessageMetadata(phase = "commentary").toMetadata())
        val tool = UIMessagePart.Tool("call_1", "search", "{}")
        val answer = UIMessagePart.Text("Answer", OpenAIMessageMetadata(phase = "final_answer").toMetadata())
        val legacy = UIMessagePart.Text("Ordinary")

        assertEquals(
            listOf(
                MessagePartBlock.ThinkingBlock(listOf(
                    ThinkingStep.ReasoningStep(reasoning),
                    ThinkingStep.CommentaryStep(commentary),
                    ThinkingStep.ToolStep(tool),
                )),
                MessagePartBlock.ContentBlock(answer, 3),
                MessagePartBlock.ContentBlock(legacy, 4),
            ),
            listOf(reasoning, commentary, tool, answer, legacy).groupMessageParts(),
        )
    }
}
