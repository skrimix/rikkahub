package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.OpenAIMessageMetadata
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.isCommentary
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiPhaseTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `streamed commentary and final answer retain phases through persistence and replay`() {
        val decoder = ResponseApiStreamDecoder()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("hello"))

        fun emit(payload: JsonObject) {
            decoder.accept(SseEvent(data = payload.toString())).chunks.forEach {
                messages = handler.handle(messages, it)
            }
        }

        listOf("commentary", "final_answer").forEachIndexed { index, phase ->
            val id = "msg_$index"
            emit(itemEvent("response.output_item.added", messageItem(id, phase)))
            emit(buildJsonObject {
                put("type", "response.content_part.added")
                put("item_id", id)
                put("content_index", 0)
                put("part", buildJsonObject { put("type", "output_text") })
            })
            emit(textEvent("response.output_text.delta", id, "Hello "))
            emit(textEvent("response.output_text.delta", id, phase))
            val streaming = messages.last().parts.last() as UIMessagePart.Text
            assertEquals(phase == "commentary", streaming.isCommentary)
            emit(textEvent("response.output_text.done", id))
            emit(textEvent("response.content_part.done", id))
            emit(itemEvent("response.output_item.done", messageItem(id, phase)))
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }

        val saved = json.decodeFromString<UIMessage>(json.encodeToString(messages.last()))
        val parts = saved.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("Hello commentary", "Hello final_answer"), parts.map { it.text })
        assertEquals(listOf("msg_0", "msg_1"), parts.map { it.metadataAs<OpenAIMessageMetadata>()?.messageId })
        assertEquals(
            listOf("commentary", "final_answer"),
            api.buildMessages(listOf(saved)).map { it.jsonObject["phase"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `phase supplied at item completion updates existing streamed text`() {
        val decoder = ResponseApiStreamDecoder()
        val handler = StreamChunkHandler()
        val events = listOf(
            itemEvent("response.output_item.added", messageItem("msg_1", null)),
            textEvent("response.output_text.delta", "msg_1", "Checking"),
            textEvent("response.output_text.done", "msg_1"),
            textEvent("response.content_part.done", "msg_1"),
            itemEvent("response.output_item.done", messageItem("msg_1", "commentary")),
        )
        val messages = events.flatMap { decoder.accept(SseEvent(data = it.toString())).chunks }
            .fold(listOf(UIMessage.user("hello"))) { messages, chunk -> handler.handle(messages, chunk) }
        val part = messages.last().parts.single() as UIMessagePart.Text
        assertEquals("Checking", part.text)
        assertTrue(part.isCommentary)
    }

    @Test
    fun `non-streaming phases survive merging and preserve separate message items`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("output", buildJsonArray {
                add(messageItem("msg_1", "commentary", "Checking ", "sources"))
                add(messageItem("msg_2", "commentary", "Found them"))
                add(messageItem("msg_3", "final_answer", "Answer"))
            })
        })
        val messages = listOf(UIMessage.user("hello"), UIMessage.assistant("Earlier"))
            .handleTextGenerationResult(result)
        val replay = api.buildMessages(listOf(messages.last())).map { it.jsonObject }
        assertEquals(4, replay.size)
        assertFalse(replay[0].containsKey("phase"))
        assertEquals(listOf("commentary", "commentary", "final_answer"),
            replay.drop(1).map { it["phase"]?.jsonPrimitive?.content })
        assertEquals("Checking sources", replay[1]["content"]?.jsonPrimitive?.content)
        assertEquals("Found them", replay[2]["content"]?.jsonPrimitive?.content)
        assertEquals("Answer", replay[3]["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `commentary stays before tool call and final answer after tool output`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Checking", OpenAIMessageMetadata("msg_1", "commentary").toMetadata()),
                UIMessagePart.Tool("call_1", "search", "{}", listOf(UIMessagePart.Text("Found"))),
                UIMessagePart.Text("Answer", OpenAIMessageMetadata("msg_2", "final_answer").toMetadata()),
                UIMessagePart.Text("Legacy"),
            ),
        )
        val replay = api.buildMessages(listOf(assistant)).map { it.jsonObject }
        assertEquals(5, replay.size)
        assertEquals("commentary", replay[0]["phase"]?.jsonPrimitive?.content)
        assertEquals("function_call", replay[1]["type"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", replay[2]["type"]?.jsonPrimitive?.content)
        assertEquals("final_answer", replay[3]["phase"]?.jsonPrimitive?.content)
        assertFalse(replay[4].containsKey("phase"))
    }

    @Test
    fun `missing and null phases remain ordinary text and user input never sends phase`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("output", buildJsonArray {
                add(messageItem("msg_1", null, "Ordinary"))
                add(JsonObject(messageItem("msg_2", null, "Also ordinary") + ("phase" to JsonNull)))
            })
        })
        result.message.parts.filterIsInstance<UIMessagePart.Text>().forEach { assertFalse(it.isCommentary) }
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hello", OpenAIMessageMetadata(phase = "commentary").toMetadata())),
        )
        api.buildMessages(listOf(user, result.message)).forEach {
            assertFalse(it.jsonObject.containsKey("phase"))
        }
    }

    private fun messageItem(id: String, phase: String?, vararg text: String) = buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put("id", id)
        phase?.let { put("phase", it) }
        put("content", buildJsonArray {
            text.forEach {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", it)
                })
            }
        })
    }

    private fun itemEvent(type: String, item: JsonObject) = buildJsonObject {
        put("type", type)
        put("item", item)
    }

    private fun textEvent(type: String, id: String, delta: String? = null) = buildJsonObject {
        put("type", type)
        put("item_id", id)
        put("content_index", 0)
        delta?.let { put("delta", it) }
    }
}
