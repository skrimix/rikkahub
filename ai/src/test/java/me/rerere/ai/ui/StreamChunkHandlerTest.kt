package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamChunkHandlerTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `streamed tool chain should sum requests without summing usage snapshots`() {
        var messages = listOf(UIMessage.user("use tools"))

        repeat(3) { step ->
            val handler = StreamChunkHandler(model)
            messages = handler.handle(messages, StreamChunk.ReasoningDelta("reasoning", "think"))
            messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 10)))
            messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(completionTokens = 2)))
            val finalUsage = StreamChunk.Usage(TokenUsage(10, 5, 4, 15))
            messages = handler.handle(messages, finalUsage)
            messages = handler.handle(messages, finalUsage)

            if (step < 2) {
                messages = handler.handle(messages, StreamChunk.ToolCallStart("call-$step", "search"))
                messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-$step"))
            } else {
                messages = handler.handle(messages, StreamChunk.TextDelta("answer", "done"))
            }
            messages = handler.handle(messages, StreamChunk.Finish())
            messages = messages.dropLast(1) + messages.last().finishPendingTools {
                it.copy(output = listOf(UIMessagePart.Text("result")))
            }

            val requests = step + 1
            assertEquals(TokenUsage(10 * requests, 5 * requests, 4 * requests, 15 * requests), messages.last().usage)
            assertEquals(TokenUsage(10, 5, 4, 15), messages.last().lastRequestUsage)
        }

        assertEquals(2, messages.size)
        assertEquals(2, messages.last().getTools().size)
        assertEquals("done", messages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `new request should not inherit cached or output tokens from earlier requests`() {
        val previous = UIMessage.assistant("earlier").copy(usage = TokenUsage(100, 20, 80, 120))
        val handler = StreamChunkHandler(model)
        var messages = handler.handle(listOf(previous), StreamChunk.Usage(TokenUsage(promptTokens = 50)))

        assertEquals(TokenUsage(150, 20, 80, 170), messages.last().usage)
        assertEquals(TokenUsage(50, 0, 0, 50), messages.last().lastRequestUsage)

        messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(completionTokens = 5)))
        assertEquals(TokenUsage(150, 25, 80, 175), messages.last().usage)
        assertEquals(TokenUsage(50, 5, 0, 55), messages.last().lastRequestUsage)
    }

    @Test
    fun `retry from response base should discard failed attempt usage`() {
        val base = listOf(UIMessage.assistant("tool response").copy(usage = TokenUsage(100, 20, 80, 120)))
        val failedAttempt = StreamChunkHandler(model).handle(base, StreamChunk.Usage(TokenUsage(50, 2)))
        assertEquals(TokenUsage(150, 22, 80, 172), failedAttempt.last().usage)

        val retried = StreamChunkHandler(model).handle(base, StreamChunk.Usage(TokenUsage(50, 5)))
        assertEquals(TokenUsage(150, 25, 80, 175), retried.last().usage)
    }

    @Test
    fun `new assistant reply should not include previous reply usage`() {
        val messages = listOf(
            UIMessage.assistant("previous").copy(usage = TokenUsage(100, 20, 80, 120)),
            UIMessage.user("next"),
        )
        val updated = StreamChunkHandler(model).handle(messages, StreamChunk.Usage(TokenUsage(10, 5)))

        assertEquals(3, updated.size)
        assertEquals(TokenUsage(10, 5, 0, 15), updated.last().usage)
    }

    @Test
    fun `non streaming tool chain should sum usage across requests`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val toolResult = TextGenerationResult(
            id = "tool-response",
            model = model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = "call-1", toolName = "search", input = "{}"),
            )),
            usage = TokenUsage(100, 20, 80, 120),
        )
        messages = messages.handleTextGenerationResult(toolResult, model)
        messages = messages.dropLast(1) + messages.last().finishPendingTools {
            it.copy(output = listOf(UIMessagePart.Text("result")))
        }
        val finalResult = TextGenerationResult(
            id = "final-response",
            model = model.modelId,
            message = UIMessage.assistant("done"),
            usage = TokenUsage(50, 5),
        )
        messages = messages.handleTextGenerationResult(finalResult, model)

        assertEquals(2, messages.size)
        assertEquals(1, messages.last().getTools().size)
        assertEquals(TokenUsage(150, 25, 80, 175), messages.last().usage)
        assertEquals(finalResult.usage, messages.last().lastRequestUsage)
    }

    @Test
    fun `missing usage should preserve known totals and remain null when unknown`() {
        val result = TextGenerationResult(
            id = "response",
            model = model.modelId,
            message = UIMessage.assistant("done"),
        )
        val unknown = listOf(UIMessage.assistant("start"))
            .handleTextGenerationResult(result, model)
        assertNull(unknown.last().usage)
        assertNull(unknown.last().lastRequestUsage)

        val knownUsage = TokenUsage(100, 20, 80, 120)
        val known = listOf(UIMessage.assistant("start").copy(usage = knownUsage))
        val streamed = StreamChunkHandler(model).handle(known, StreamChunk.TextDelta("text", "done"))
        assertEquals(knownUsage, streamed.last().usage)
        val nonStreamed = known.handleTextGenerationResult(result, model)
        assertEquals(knownUsage, nonStreamed.last().usage)
        assertEquals(knownUsage, nonStreamed.last().lastRequestUsage)
    }

    @Test
    fun `text lifecycle should create and update assistant message`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "hel"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "lo"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("hello", messages.last().toText())
        assertEquals(model.id, messages.last().modelId)
        assertNotNull(messages.last().finishedAt)
    }

    @Test
    fun `reasoning tool and usage events should preserve semantic order`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ReasoningStart("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ReasoningDelta("reasoning-1", "think"))
        messages = handler.handle(messages, StreamChunk.ReasoningEnd("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(
                id = "call-1",
                toolNameDelta = "search",
                inputDelta = "{\"q\":\"test\"}",
            ),
        )
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))
        messages = handler.handle(messages,
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)),
        )

        val assistant = messages.last()
        val reasoning = assistant.parts[0] as UIMessagePart.Reasoning
        val tool = assistant.parts[1] as UIMessagePart.Tool
        assertEquals("think", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":\"test\"}", tool.input)
        assertEquals(15, assistant.usage?.totalTokens)
    }

    @Test
    fun `interleaved text chunks should be merged by id`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "A"))
        messages = handler.handle(messages, StreamChunk.TextStart("text-2"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-2", "B"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "C"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-2"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))

        val textParts = messages.last().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("AC", "B"), textParts.map { it.text })
    }

    @Test
    fun `image snapshots should replace previous image data`() {
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ImageStart("image-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-2"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "final"))
        messages = handler.handle(messages, StreamChunk.ImageEnd("image-1"))

        val image = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/png;base64,final", image.url)
    }

    @Test
    fun `non streaming result should keep image data url intact`() {
        val messages = listOf(UIMessage.user("draw an image"))
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/jpeg;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/jpeg;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `non streaming result appended to assistant turn should not merge images`() {
        val messages = listOf(
            UIMessage.user("draw an image"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("sure"))),
        )
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/png;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/png;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `server tool lifecycle should merge streamed input result and metadata`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ServerToolStart(
            id = "srv-1",
            toolName = "web_search",
            metadata = buildJsonObject { put("call", "raw") },
        ))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "{\"query\":"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "\"Kotlin\"}"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputEnd("srv-1"))
        messages = handler.handle(messages, StreamChunk.ServerToolEnd(
            id = "srv-1",
            output = buildJsonObject { put("result", "docs") },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("result", "raw") },
        ))

        val tool = messages.last().parts.single() as UIMessagePart.ServerTool
        assertEquals("web_search", tool.toolName)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals("docs", tool.output?.jsonObject?.get("result")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals("raw", tool.metadata?.get("call")?.jsonPrimitive?.content)
        assertEquals("raw", tool.metadata?.get("result")?.jsonPrimitive?.content)
    }
}
