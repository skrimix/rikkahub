package me.rerere.rikkahub.data.shizuku

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SHELL_MAX_BYTES = 32 * 1024
internal val ShizukuJson = Json { encodeDefaults = true }

@Serializable
data class ShizukuStatus(
    val available: Boolean = false,
    val supported: Boolean = false,
    val permissionGranted: Boolean = false,
    val permissionDeniedPermanently: Boolean = false,
    val uid: Int? = null,
    val privilege: String? = null,
    val message: String = "Shizuku is unavailable. Start Shizuku, then return to this page.",
)

internal fun privilegeName(uid: Int): String = when (uid) {
    0 -> "root"
    2000 -> "adb"
    else -> "uid:$uid"
}

data class ShizukuShellRequest(
    val command: String,
    val cwd: String = "/",
    val timeoutSeconds: Long = 30,
) {
    init {
        require(command.isNotBlank()) { "command must not be blank" }
        require('\u0000' !in command) { "command must not contain NUL" }
        require(command.toByteArray(Charsets.UTF_8).size <= SHELL_MAX_BYTES) {
            "command must not exceed 32 KiB of UTF-8 text"
        }
        require(cwd.startsWith('/') && '\u0000' !in cwd) { "cwd must be an absolute device path" }
        require(cwd.toByteArray(Charsets.UTF_8).size <= 4096) { "cwd is too long" }
        require(timeoutSeconds in 1..600) { "timeout_seconds must be between 1 and 600" }
    }
}

@Serializable
data class ShizukuShellResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val uid: Int? = null,
    val error: String? = null,
    val message: String? = null,
)

interface ShizukuShellBackend {
    fun refreshStatus(): ShizukuStatus
    suspend fun execute(request: ShizukuShellRequest): ShizukuShellResult
}

internal class ShizukuShellException(val code: String, message: String) : Exception(message)
