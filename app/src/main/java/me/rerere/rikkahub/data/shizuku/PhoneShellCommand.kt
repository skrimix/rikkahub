package me.rerere.rikkahub.data.shizuku

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One command, with cancellation that also works before the process starts. */
internal class PhoneShellCommand(
    private val shell: String = "/system/bin/sh",
    private val terminate: (Process) -> Unit = { it.destroyForcibly(); Unit },
) {
    private val lock = Any()
    private var cancelled = false
    private var process: Process? = null

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            process?.let(terminate)
        }
    }

    fun execute(request: ShizukuShellRequest): ShizukuShellResult {
        val running = synchronized(lock) {
            if (cancelled) throw InterruptedException("Command cancelled")
            ProcessBuilder(shell, "-c", request.command)
                .directory(File(request.cwd))
                .start().also { process = it }
        }
        val stdout = OutputCollector(running.inputStream)
        val stderr = OutputCollector(running.errorStream)
        try {
            running.outputStream.close()
            val finished = running.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) synchronized(lock) { terminate(running) }
            stdout.join()
            stderr.join()
            return ShizukuShellResult(
                stdout = stdout.text(),
                stderr = stderr.text(),
                exitCode = if (finished) running.exitValue() else -1,
                timedOut = !finished,
                truncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            synchronized(lock) {
                if (running.isAlive) terminate(running)
                process = null
            }
            runCatching { running.outputStream.close() }
            stdout.close()
            stderr.close()
        }
    }
}

private class OutputCollector(private val stream: InputStream) {
    private val bytes = ByteArrayOutputStream()
    @Volatile var truncated = false
        private set
    private val thread = Thread({
        try {
            stream.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) {
                        val retained = minOf(count, SHELL_MAX_BYTES - bytes.size())
                        bytes.write(buffer, 0, retained)
                        if (retained < count) truncated = true
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // Cancellation can close the pipe while read() is blocked.
        }
    }, "phone-shell-output").apply { isDaemon = true; start() }

    fun join() {
        thread.join(1000)
        if (thread.isAlive) truncated = true
    }

    fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }

    fun close() {
        runCatching { stream.close() }
        thread.interrupt()
    }
}
