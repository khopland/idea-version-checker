package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.followup.NativeFileChangeCapture
import io.github.khopland.versionchecker.followup.NativeFileChanges
import io.github.khopland.versionchecker.followup.NpmLockfileSync
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NativeFileChangesTest {
    private fun files(action: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("native-lockfile-review-")
        try { action(directory.resolve("package-lock.json"), directory.resolve("npm-shrinkwrap.json")) }
        finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `captures modified created and deleted files without retaining unrelated inputs`() = files { lock, shrinkwrap ->
        Files.writeString(lock, "old")
        val capture = NativeFileChangeCapture.before(listOf(lock, shrinkwrap))
        Files.writeString(lock, "new")
        Files.writeString(shrinkwrap, "created")
        Files.writeString(lock.parent.resolve("unrelated.txt"), "private")
        val changes = capture.finish()
        assertEquals(2, changes.files.size)
        assertEquals("old", changes.files.first().before)
        assertEquals("new", changes.files.first().after)
        assertNull(changes.files.last().before)
        assertEquals("created", changes.files.last().after)
        assertTrue(changes.omitted.isEmpty())
        val deleted = NativeFileChangeCapture.before(listOf(lock))
        Files.delete(lock)
        assertNull(deleted.finish().files.single().after)
        assertEquals("old", changes.files.first().before)
    }

    @Test fun `incomplete before and after captures disclose whole-file omissions`() = files { lock, shrinkwrap ->
        Files.writeString(lock, "x".repeat(100))
        Files.writeString(shrinkwrap, "small")
        val capture = NativeFileChangeCapture.before(listOf(lock, shrinkwrap), NativeFileChangeCapture.Limits(bytesPerPhase = 20))
        Files.writeString(lock, "now small")
        Files.writeString(shrinkwrap, "y".repeat(100))
        val changes = capture.finish()
        assertTrue(changes.files.isEmpty())
        assertEquals(2, changes.omitted.size)
        assertTrue(changes.omitted.all { it.contains("byte budget") })
        assertTrue(changes.description().contains("2 files omitted"))
    }

    @Test fun `unstable preview baseline invalid UTF8 and symlink outputs never produce a complete diff`() = files { lock, shrinkwrap ->
        Files.writeString(lock, "old")
        val hashes = NpmLockfileSync.capture(listOf(lock))
        Files.writeString(lock, "changed while preparing")
        val stale = NativeFileChangeCapture.before(listOf(lock), expectedHashes = hashes.files).finish()
        assertTrue(stale.files.isEmpty())
        assertTrue(stale.omitted.single().contains("changed while preparing"))
        Files.write(lock, byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals(1, NativeFileChangeCapture.before(listOf(lock)).finish().omitted.size)
        Files.createSymbolicLink(shrinkwrap, lock)
        assertTrue(NativeFileChangeCapture.before(listOf(shrinkwrap)).finish().omitted.single().contains("not a regular"))
    }

    @Test fun `capture remains cancellable and character limits omit rather than truncate files`() = files { lock, _ ->
        Files.writeString(lock, "old")
        val capture = NativeFileChangeCapture.before(listOf(lock), NativeFileChangeCapture.Limits(characters = 4))
        Files.writeString(lock, "new")
        assertTrue(capture.finish().files.isEmpty())
        assertTrue(capture.finish().omitted.single().contains("text budget"))
        var checks = 0
        try {
            NativeFileChangeCapture.before(listOf(lock)) { if (++checks == 2) throw java.util.concurrent.CancellationException() }
            fail("Expected cancellation during physical capture")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(2, checks) }
    }

    @Test fun `unknown termination concurrent edits and omitted snapshots remain observational`() {
        val description = NativeFileChanges(captureUnavailable = true, editorChangesObserved = true,
            processTerminationKnown = false).description()
        assertTrue(description.contains("unavailable or were evicted"))
        assertTrue(description.contains("Unsaved editor changes overlapped"))
        assertTrue(description.contains("termination was not confirmed"))
        assertTrue(description.contains("Concurrent external edits may be included"))
        assertTrue(description.contains("Declaration undo does not undo native writes"))
    }
}
