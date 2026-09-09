package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shizuku.ShizukuJson
import me.rerere.rikkahub.data.shizuku.ShizukuShellBackend
import me.rerere.rikkahub.data.shizuku.ShizukuShellException
import me.rerere.rikkahub.data.shizuku.ShizukuShellRequest
import me.rerere.rikkahub.data.shizuku.ShizukuShellResult

internal fun buildShizukuTools(backend: ShizukuShellBackend, requiresApproval: Boolean): List<Tool> = listOf(
    Tool(
        name = "shizuku_status",
        description = "Check device shell availability, Shizuku permission, and ADB/root identity. " +
            "Does not request permission or execute a command.",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text(ShizukuJson.encodeToString(backend.refreshStatus()))) },
    ),
    Tool(
        name = "shizuku_shell",
        description = "Run a command or script on the device using Shizuku's ADB shell or root identity. " +
            "This accesses the device, outside the workspace sandbox. Uses /system/bin/sh -c; " +
            "each call starts fresh with stdin closed. No persistent sessions, PTY, or background jobs. " +
            "Output is limited to 32 KiB per stream. Check shizuku_status for current privileges.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command or script, at most 32 KiB of UTF-8 text.")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute device directory. Defaults to /.")
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 600)
                        put("description", "Command timeout in seconds. Defaults to 30.")
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = { requiresApproval },
        execute = { input ->
            val result = try {
                backend.execute(parseShizukuShellRequest(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                ShizukuShellResult(error = "INVALID_ARGUMENT", message = e.message)
            } catch (e: ShizukuShellException) {
                ShizukuShellResult(error = e.code, message = e.message)
            }
            listOf(UIMessagePart.Text(ShizukuJson.encodeToString(result)))
        },
    ),
)

internal fun parseShizukuShellRequest(input: JsonElement): ShizukuShellRequest {
    val args = input as? JsonObject ?: throw IllegalArgumentException("Expected an object")
    fun string(name: String, default: String? = null): String {
        if (name !in args && default != null) return default
        val value = args[name] as? JsonPrimitive
        require(value != null && value.isString) { "$name must be a string" }
        return value.content
    }
    val timeout = if ("timeout_seconds" in args) {
        val value = args["timeout_seconds"] as? JsonPrimitive
        require(value != null && !value.isString && value.longOrNull != null) {
            "timeout_seconds must be an integer"
        }
        value.longOrNull!!
    } else 30L
    return ShizukuShellRequest(string("command"), string("cwd", "/"), timeout)
}
