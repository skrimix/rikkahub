package me.rerere.rikkahub.data.shizuku

import android.os.Bundle
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** Runs only in Shizuku's privileged process; it does not use application services. */
class ShizukuShellService : IShizukuShellService.Stub() {
    private val workers = Executors.newFixedThreadPool(2)
    private val commands = mutableMapOf<String, Command>()

    private class Command(val callback: IShizukuShellCallback) {
        val runner = PhoneShellCommand(terminate = ::terminatePhoneProcess)
        @Volatile var thread: Thread? = null
        val death = IBinder.DeathRecipient { cancel() }
        fun cancel() {
            runner.cancel()
            thread?.interrupt()
        }
    }

    override fun execute(
        requestId: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        callback: IShizukuShellCallback,
    ) {
        val request = try {
            require(timeoutMillis % 1000 == 0L) { "Invalid timeout" }
            require(requestId.length in 1..128) { "Invalid request ID" }
            ShizukuShellRequest(command, cwd, timeoutMillis / 1000)
        } catch (e: IllegalArgumentException) {
            deliver(callback, ShizukuShellResult(error = "INVALID_ARGUMENT", message = e.message))
            return
        }
        synchronized(commands) {
            if (commands.size >= 2 || requestId in commands || workers.isShutdown) {
                deliver(callback, ShizukuShellResult(error = "BUSY", message = "Device shell is busy. Try again later."))
                return
            }
            val active = Command(callback)
            commands[requestId] = active
            try {
                callback.asBinder().linkToDeath(active.death, 0)
                workers.submit {
                    try {
                        active.thread = Thread.currentThread()
                        val result = active.runner.execute(request)
                        deliver(callback, result)
                    } catch (_: InterruptedException) {
                        // The client cancelled or died; it no longer needs a result.
                    } catch (e: Exception) {
                        deliver(callback, ShizukuShellResult(error = "EXECUTION_FAILED", message = e.message))
                    } finally {
                        active.thread = null
                        runCatching { callback.asBinder().unlinkToDeath(active.death, 0) }
                        synchronized(commands) { commands.remove(requestId) }
                    }
                }
            } catch (e: Exception) {
                commands.remove(requestId)
                runCatching { callback.asBinder().unlinkToDeath(active.death, 0) }
                deliver(callback, ShizukuShellResult(error = "EXECUTION_FAILED", message = e.message))
            }
        }
    }

    override fun cancel(requestId: String) {
        synchronized(commands) { commands[requestId] }?.cancel()
    }

    override fun destroy() {
        synchronized(commands) { commands.values.toList() }.forEach { it.cancel() }
        workers.shutdownNow()
        exitProcess(0)
    }

    private fun deliver(callback: IShizukuShellCallback, result: ShizukuShellResult) {
        // Keep output as separate strings: JSON escaping can inflate a Binder transaction.
        val bundle = Bundle().apply {
            putString("stdout", result.stdout)
            putString("stderr", result.stderr)
            putInt("exitCode", result.exitCode)
            putBoolean("timedOut", result.timedOut)
            putBoolean("truncated", result.truncated)
            putInt("uid", Os.getuid())
            putString("error", result.error)
            putString("message", result.message?.take(1024))
        }
        runCatching { callback.onResult(bundle) }
    }
}

/** Best effort for descendants still attached to this process; detached jobs are unsupported. */
private fun terminatePhoneProcess(process: Process) {
    if (!process.isAlive) return
    // Android versions differ in whether Process exposes pid(). UserServices allow reflection.
    val pid = runCatching {
        Process::class.java.getMethod("pid").invoke(process).let { (it as Number).toInt() }
    }.getOrElse {
        runCatching {
            process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
        }.getOrNull()
    }
    if (pid != null && pid > 0) {
        fun killChildren(parent: Int, depth: Int = 0) {
            if (depth > 32) return
            val children = runCatching {
                File("/proc/$parent/task/$parent/children").readText().trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrElse {
                // Many Android kernels omit task/.../children. Read PPid from procfs instead.
                File("/proc").listFiles().orEmpty().mapNotNull { entry ->
                    val child = entry.name.toIntOrNull() ?: return@mapNotNull null
                    val ppid = runCatching {
                        entry.resolve("stat").readText().substringAfterLast(')').trim()
                            .split(Regex("\\s+"))[1].toInt()
                    }.getOrNull()
                    child.takeIf { ppid == parent }
                }
            }
            children.forEach { child ->
                runCatching { Os.kill(child, OsConstants.SIGSTOP) }
                killChildren(child, depth + 1)
                runCatching { Os.kill(child, OsConstants.SIGKILL) }
            }
        }
        runCatching { Os.kill(pid, OsConstants.SIGSTOP) }
        try {
            killChildren(pid)
        } finally {
            // Android's Process.destroyForcibly() can delegate to SIGTERM, which cannot
            // terminate a stopped process. Send SIGKILL explicitly before the fallback.
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
    }
    process.destroyForcibly()
}
