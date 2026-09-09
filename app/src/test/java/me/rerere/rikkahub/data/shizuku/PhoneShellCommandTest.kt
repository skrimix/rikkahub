package me.rerere.rikkahub.data.shizuku

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneShellCommandTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun runner() = PhoneShellCommand("/bin/sh")

    @Test fun `captures streams exit code cwd and stdin EOF`() {
        val result = runner().execute(ShizukuShellRequest("cat; pwd; printf error >&2; exit 7", temporary.root.path))
        assertEquals(temporary.root.path + "\n", result.stdout)
        assertEquals("error", result.stderr)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test fun `drains simultaneous output after retaining 32 KiB per stream`() {
        val result = runner().execute(ShizukuShellRequest("(head -c 100000 /dev/zero | tr '\\0' x) & head -c 100000 /dev/zero | tr '\\0' y >&2; wait"))
        assertEquals("x".repeat(SHELL_MAX_BYTES), result.stdout)
        assertEquals("y".repeat(SHELL_MAX_BYTES), result.stderr)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode)
    }

    @Test(timeout = 5000) fun `timeout terminates command and retains early output`() {
        val result = runner().execute(ShizukuShellRequest("printf ready; exec sleep 30", timeoutSeconds = 1))
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertEquals("ready", result.stdout)
    }

    @Test fun `cancelling before launch prevents side effects`() {
        val marker = temporary.root.resolve("marker")
        val runner = runner()
        runner.cancel()
        try {
            runner.execute(ShizukuShellRequest("touch marker", temporary.root.path))
            fail("Expected cancellation")
        } catch (_: InterruptedException) {
            assertFalse(marker.exists())
        }
    }

    @Test(timeout = 5000) fun `cancelling active command kills its process`() {
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var killed = false
        val runner = PhoneShellCommand("/bin/sh") { process ->
            process.destroyForcibly()
            killed = true
        }
        try {
            val marker = temporary.root.resolve("started")
            val future = executor.submit<ShizukuShellResult> {
                started.countDown()
                runner.execute(ShizukuShellRequest("touch started; exec sleep 30", temporary.root.path))
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(marker.exists())
            runner.cancel()
            future.get(2, TimeUnit.SECONDS)
            assertTrue(killed)
        } finally {
            executor.shutdownNow()
        }
    }
}
