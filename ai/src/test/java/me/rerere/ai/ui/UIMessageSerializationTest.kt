package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    @Test
    fun `total and latest request usage survive serialization`() {
        val message = UIMessage.assistant("done").copy(
            usage = TokenUsage(150, 25, 80, 175),
            lastRequestUsage = TokenUsage(50, 5, 0, 55),
        )

        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))

        assertEquals(message.usage, decoded.usage)
        assertEquals(message.lastRequestUsage, decoded.lastRequestUsage)
    }

    @Test
    fun `legacy messages can omit latest request usage`() {
        val message = UIMessage.assistant("old").copy(usage = TokenUsage(50, 5, 0, 55))
        val encoded = Json.encodeToString(message)

        assertFalse(encoded.contains("lastRequestUsage"))
        val decoded = Json.decodeFromString<UIMessage>(encoded)
        assertEquals(message.usage, decoded.usage)
        assertNull(decoded.lastRequestUsage)
    }

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }
}
