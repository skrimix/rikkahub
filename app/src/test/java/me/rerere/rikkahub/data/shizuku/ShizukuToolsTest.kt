package me.rerere.rikkahub.data.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.buildShizukuTools
import me.rerere.rikkahub.data.ai.tools.local.parseShizukuShellRequest
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ShizukuToolsTest {
    private class Backend : ShizukuShellBackend {
        var calls = 0
        var failure: Exception? = null
        override fun refreshStatus() = ShizukuStatus()
        override suspend fun execute(request: ShizukuShellRequest): ShizukuShellResult {
            calls++
            failure?.let { throw it }
            return ShizukuShellResult(stdout = request.command, exitCode = 0, uid = 2000)
        }
    }

    @Test fun `existing assistants keep shell disabled and approval enabled`() {
        val assistant = Json.decodeFromString<Assistant>("{}")
        assertFalse(LocalToolOption.ShizukuShell in assistant.localTools)
        assertTrue(assistant.shizukuShellRequiresApproval)
        val enabled = assistant.copy(localTools = listOf(LocalToolOption.ShizukuShell), shizukuShellRequiresApproval = false)
        assertEquals(enabled, Json.decodeFromString<Assistant>(Json.encodeToString(Assistant.serializer(), enabled)))
    }

    @Test fun `status never executes a command and approval is per tool instance`() = runBlocking {
        val backend = Backend()
        val guarded = buildShizukuTools(backend, true)
        val automatic = buildShizukuTools(backend, false)
        assertEquals(listOf("shizuku_status", "shizuku_shell"), guarded.map { it.name })
        assertFalse(guarded[0].needsApproval(JsonNull))
        assertTrue(guarded[1].needsApproval(JsonNull))
        assertFalse(automatic[1].needsApproval(JsonNull))
        guarded[0].execute(buildJsonObject {})
        assertEquals(0, backend.calls)
    }

    @Test fun `defaults and explicit arguments are preserved`() {
        assertEquals(ShizukuShellRequest("id"), parseShizukuShellRequest(Json.parseToJsonElement("""{"command":"id"}""")))
        assertEquals(ShizukuShellRequest("pwd", "/data/local/tmp", 600), parseShizukuShellRequest(Json.parseToJsonElement("""{"command":"pwd","cwd":"/data/local/tmp","timeout_seconds":600}""")))
    }

    @Test fun `invalid arguments never reach backend`() = runBlocking {
        val backend = Backend()
        val tool = buildShizukuTools(backend, false)[1]
        val invalid = listOf(
            "{}", "[]", """{"command":" "}""", """{"command":2}""",
            """{"command":"id","cwd":"relative"}""", """{"command":"id","cwd":null}""",
            """{"command":"id","timeout_seconds":0}""", """{"command":"id","timeout_seconds":601}""",
            """{"command":"id","timeout_seconds":1.5}""", """{"command":"id","timeout_seconds":"30"}""",
            buildJsonObject { put("command", "é".repeat(16385)) }.toString(),
            buildJsonObject { put("command", "id\u0000") }.toString(),
        )
        invalid.forEach {
            val result = tool.execute(Json.parseToJsonElement(it)).single() as UIMessagePart.Text
            assertEquals(it, "INVALID_ARGUMENT", Json.decodeFromString<ShizukuShellResult>(result.text).error)
        }
        assertEquals(0, backend.calls)
    }

    @Test fun `service loss is reported without replay`() = runBlocking {
        val backend = Backend().apply { failure = ShizukuShellException("SERVICE_LOST", "Disconnected") }
        val result = buildShizukuTools(backend, false)[1].execute(buildJsonObject { put("command", "id") }).single() as UIMessagePart.Text
        assertEquals("SERVICE_LOST", Json.decodeFromString<ShizukuShellResult>(result.text).error)
        assertEquals(1, backend.calls)
    }

    @Test fun `coroutine cancellation is not converted into tool output`() = runBlocking {
        val backend = Backend().apply { failure = CancellationException("Stopped") }
        try {
            buildShizukuTools(backend, false)[1].execute(buildJsonObject { put("command", "id") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, backend.calls)
        }
    }
}
