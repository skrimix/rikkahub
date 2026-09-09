package me.rerere.rikkahub.data.shizuku

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.util.UUID

/** Requires a running Shizuku backend and permission granted to the target app. */
@RunWith(AndroidJUnit4::class)
class ShizukuShellDeviceTest {
    private lateinit var manager: ShizukuShellManager

    @Before fun connect() = runBlocking {
        manager = getKoin().get()
        for (attempt in 1..25) {
            if (manager.refreshStatus().available) break
            delay(200)
        }
        if (InstrumentationRegistry.getArguments().getString("requestShizukuPermission") == "true" &&
            !manager.refreshStatus().permissionGranted
        ) {
            withContext(Dispatchers.Main) { manager.requestPermission() }
            for (attempt in 1..150) {
                if (manager.refreshStatus().permissionGranted) break
                delay(200)
            }
        }
    }

    private fun requirePermission() {
        val status = manager.refreshStatus()
        assumeTrue(status.message, status.permissionGranted)
    }

    @Test fun statusAndPermissionFailure() = runBlocking {
        val status = manager.refreshStatus()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Shizuku status: $status\n")
        })
        if (!status.permissionGranted) {
            try {
                manager.execute(ShizukuShellRequest("id"))
                fail("Execution should require Shizuku permission")
            } catch (e: ShizukuShellException) {
                assertTrue(e.code in setOf("NO_PERMISSION", "UNAVAILABLE", "UNSUPPORTED"))
            }
        }
    }

    @Test fun identityCwdStdinAndNonzeroExit() = runBlocking {
        requirePermission()
        val result = manager.execute(ShizukuShellRequest("cat; id -u; pwd; printf error >&2; exit 7", "/data/local/tmp"))
        assertNull(result.error)
        assertEquals("${result.uid}\n/data/local/tmp\n", result.stdout)
        assertEquals("error", result.stderr)
        assertEquals(7, result.exitCode)
        assertEquals(manager.refreshStatus().uid, result.uid)
    }

    @Test fun boundedConcurrentOutput() = runBlocking {
        requirePermission()
        val result = manager.execute(ShizukuShellRequest("(head -c 100000 /dev/zero | tr '\\000' x) & head -c 100000 /dev/zero | tr '\\000' y >&2; wait"))
        assertNull(result.error)
        assertEquals("x".repeat(SHELL_MAX_BYTES), result.stdout)
        assertEquals("y".repeat(SHELL_MAX_BYTES), result.stderr)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode)
    }

    @Test fun timeoutCleansUpChildren() = runBlocking {
        requirePermission()
        val result = manager.execute(ShizukuShellRequest("sleep 60 & child=\$!; echo \$\$ \$child; wait", timeoutSeconds = 1))
        assertNull(result.error)
        assertTrue(result.timedOut)
        result.stdout.trim().split(" ").map(String::toInt).forEach { pid ->
            val check = manager.execute(ShizukuShellRequest("test ! -d /proc/$pid || test \"\$(cut -d ' ' -f 3 /proc/$pid/stat)\" = Z"))
            assertNull(check.error)
            assertEquals("Process $pid survived timeout", 0, check.exitCode)
        }
    }

    @Test fun serviceLossIsNotReplayedAndNextCommandReconnects() = runBlocking {
        requirePermission()
        try {
            manager.execute(ShizukuShellRequest("kill -9 \$PPID"))
            fail("Expected the UserService connection to close")
        } catch (e: ShizukuShellException) {
            assertEquals("SERVICE_LOST", e.code)
        }
        val result = manager.execute(ShizukuShellRequest("printf reconnected"))
        assertNull(result.error)
        assertEquals("reconnected", result.stdout)
    }

    @Test fun cancellationStopsTheCommand() = runBlocking {
        requirePermission()
        val marker = "/data/local/tmp/rikkahub-shell-test-${UUID.randomUUID()}"
        try {
            val running = async {
                manager.execute(ShizukuShellRequest("echo \$\$ > '$marker'; exec sleep 60"))
            }
            var pid: Int? = null
            withTimeout(10_000) {
                while (pid == null) {
                    delay(100)
                    pid = manager.execute(ShizukuShellRequest("cat '$marker'")).stdout.trim().toIntOrNull()
                }
            }
            running.cancelAndJoin()
            withTimeout(10_000) {
                while (manager.execute(ShizukuShellRequest("test -d /proc/$pid")).exitCode == 0) delay(100)
            }
        } finally {
            manager.execute(ShizukuShellRequest("rm -f '$marker'"))
        }
    }
}
