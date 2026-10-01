package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.OpenAIMessageMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `successful chart separates commentary from subsequent thinking steps`() {
        val commentary = UIMessagePart.Text("Plotting", OpenAIMessageMetadata(phase = "commentary").toMetadata())
        val chart = UIMessagePart.Tool(
            toolCallId = "call_chart",
            toolName = CHART_DISPLAY_TOOL_NAME,
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"success":true}""")),
        )
        val pendingChart = UIMessagePart.Tool("call_pending", CHART_DISPLAY_TOOL_NAME, "{}")
        val failedChart = UIMessagePart.Tool(
            toolCallId = "call_failed",
            toolName = CHART_DISPLAY_TOOL_NAME,
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"success":false}""")),
        )
        val answer = UIMessagePart.Text("Answer")

        assertEquals(
            listOf(
                MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.CommentaryStep(commentary))),
                MessagePartBlock.ChartBlock(chart, 1),
                MessagePartBlock.ThinkingBlock(listOf(
                    ThinkingStep.ToolStep(pendingChart),
                    ThinkingStep.ToolStep(failedChart),
                )),
                MessagePartBlock.ContentBlock(answer, 4),
            ),
            listOf(commentary, chart, pendingChart, failedChart, answer).groupMessageParts(),
        )
    }

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
