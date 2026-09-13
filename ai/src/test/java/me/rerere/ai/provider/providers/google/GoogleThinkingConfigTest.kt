package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleThinkingConfigTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `thinking off uses low for Gemini models without minimal support`() {
        listOf(
            "gemini-3.7-flash",
            "gemini-3.8-flash",
            "models/gemini-3.8-flash-preview",
            "GEMINI-3.8-FLASH",
            "gemini-3-pro-preview",
            "gemini-3.1-pro-preview",
        ).forEach { modelId ->
            assertEquals(modelId, levelConfig("low"), thinkingConfig(modelId))
        }
    }

    @Test
    fun `thinking off preserves minimal for older Flash models`() {
        listOf(
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview",
            "gemini-3.1-flash-lite-image",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
        ).forEach { modelId ->
            assertEquals(modelId, levelConfig("minimal"), thinkingConfig(modelId))
        }
    }

    @Test
    fun `explicit and automatic thinking levels are preserved`() {
        listOf(
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "high",
        ).forEach { (reasoningLevel, expected) ->
            assertEquals(levelConfig(expected), thinkingConfig("gemini-3.8-flash", reasoningLevel))
        }
        assertEquals(
            buildJsonObject { put("includeThoughts", true) },
            thinkingConfig("gemini-3.8-flash", ReasoningLevel.AUTO),
        )
    }

    @Test
    fun `thinking off preserves Gemini 2 point 5 behavior`() {
        assertEquals(
            buildJsonObject {
                put("includeThoughts", false)
                put("thinkingBudget", 0)
            },
            thinkingConfig("gemini-2.5-flash"),
        )
        assertEquals(
            buildJsonObject { put("includeThoughts", true) },
            thinkingConfig("gemini-2.5-pro"),
        )
    }

    private fun levelConfig(level: String) = buildJsonObject {
        put("includeThoughts", true)
        put("thinkingLevel", level)
    }

    private fun thinkingConfig(
        modelId: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        val body = method.invoke(
            provider,
            listOf(UIMessage.user("Translate hello into French")),
            TextGenerationParams(
                model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                reasoningLevel = reasoningLevel,
            ),
        ) as JsonObject
        return body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
    }
}
