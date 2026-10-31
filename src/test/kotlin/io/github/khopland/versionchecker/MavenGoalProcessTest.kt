package io.github.khopland.versionchecker

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.khopland.versionchecker.maven.MavenGoalProcess
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class MavenGoalProcessTest {
    private fun fixture(vararg arguments: String): GeneralCommandLine {
        val classes = com.intellij.util.PathUtil.getJarPathForClass(MavenProcessFixture::class.java)
        return GeneralCommandLine(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes, MavenProcessFixture::class.java.name, *arguments)
    }

    @Test fun `arguments remain literal and both diagnostic streams are captured`() = runBlocking {
        val values = arrayOf("a path with spaces", "password=a&b;$(literal)", "quote=\"value\"", "!disabled-profile")
        val result = MavenGoalProcess.execute(fixture("arguments", *values))
        assertEquals(0, result.exitCode)
        values.forEach { assertTrue(result.text, it in result.text) }
        assertTrue(result.text, "fixture stderr" in result.text)
    }

    @Test fun `nonzero exit retains Maven diagnostics`() = runBlocking {
        val result = MavenGoalProcess.execute(fixture("failure"))
        assertEquals(7, result.exitCode)
        assertTrue(result.text, "401 Unauthorized" in result.text)
    }

    @Test fun `oversized output fails instead of accepting an incomplete result`() = runBlocking {
        try {
            MavenGoalProcess.execute(fixture("output"), maxOutputChars = 1024)
            fail("Expected bounded diagnostics")
        } catch (expected: IOException) {
            assertTrue(expected.message, expected.message!!.contains("diagnostic limit"))
        }
    }

    @Test fun `cancellation terminates the goal and its child before returning`() = runBlocking {
        val directory = Files.createTempDirectory("maven-process-cancellation-")
        val parent = directory.resolve("parent.pid")
        val child = directory.resolve("child.pid")
        val job = launch { MavenGoalProcess.execute(fixture("tree", parent.toString(), child.toString())) }
        try {
            // The parent writes its pid only after the child has written its own.
            withTimeout(30_000) { while (!Files.exists(parent) || !Files.exists(child)) delay(10) }
            val parentHandle = ProcessHandle.of(Files.readString(parent).toLong()).orElseThrow()
            val childHandle = ProcessHandle.of(Files.readString(child).toLong()).orElseThrow()
            withTimeout(5_000) { job.cancelAndJoin() }
            withTimeout(2_000) { while (parentHandle.isAlive || childHandle.isAlive) delay(10) }
            assertTrue(job.isCancelled)
        } finally {
            job.cancelAndJoin()
            for (marker in listOf(child, parent)) {
                if (Files.exists(marker)) ProcessHandle.of(Files.readString(marker).toLong()).ifPresent { it.destroyForcibly() }
                Files.deleteIfExists(marker)
            }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `timeout terminates the native goal`() = runBlocking {
        val marker = Files.createTempDirectory("maven-process-timeout-").resolve("goal.pid")
        try {
            try {
                MavenGoalProcess.execute(fixture("wait", marker.toString()), timeoutMillis = 2_000)
                fail("Expected a goal timeout")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("timed out"))
            }
            assertTrue(Files.exists(marker))
            val handle = ProcessHandle.of(Files.readString(marker).toLong())
            assertFalse(handle.isPresent && handle.get().isAlive)
        } finally {
            if (Files.exists(marker)) ProcessHandle.of(Files.readString(marker).toLong()).ifPresent { it.destroyForcibly() }
            Files.deleteIfExists(marker)
            Files.deleteIfExists(marker.parent)
        }
    }
}
