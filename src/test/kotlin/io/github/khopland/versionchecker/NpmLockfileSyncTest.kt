package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.followup.NpmLockfileSync
import junit.framework.TestCase
import java.nio.file.Files

class NpmLockfileSyncTest : TestCase() {
    fun testDetectsCreatedModifiedAndDeletedOutputsWithoutTreatingOtherFilesAsOutputs() {
        val directory = Files.createTempDirectory("npm-lock-snapshot-")
        try {
            val lock = directory.resolve("package-lock.json")
            val shrinkwrap = directory.resolve("npm-shrinkwrap.json")
            Files.writeString(lock, "old")
            val snapshot = NpmLockfileSync.capture(listOf(lock, shrinkwrap))
            assertTrue(snapshot.isCurrent())
            Files.writeString(directory.resolve("unrelated.txt"), "ignored")
            assertTrue(snapshot.isCurrent())
            Files.writeString(lock, "new")
            Files.writeString(shrinkwrap, "new shrinkwrap")
            assertEquals(listOf(lock, shrinkwrap), snapshot.changed())
            assertFalse("A held preview must reject external lockfile changes", snapshot.isCurrent())
            Files.writeString(lock, "old")
            Files.delete(shrinkwrap)
            assertTrue(snapshot.isCurrent())
            Files.delete(lock)
            assertEquals(listOf(lock), snapshot.changed())
        } finally { directory.toFile().deleteRecursively() }
    }

    fun testLargeLockfileHashingRemainsCancellable() {
        val directory = Files.createTempDirectory("npm-lock-cancel-")
        try {
            val lock = directory.resolve("package-lock.json")
            Files.write(lock, ByteArray(32_768))
            var checks = 0
            try {
                NpmLockfileSync.capture(listOf(lock)) {
                    if (++checks == 3) throw java.util.concurrent.CancellationException("cancel hash")
                }
                fail("Expected cancellation during lockfile hashing")
            } catch (_: java.util.concurrent.CancellationException) {
                assertEquals(3, checks)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    fun testFailureAndCancellationDisclosePossiblePartialWrites() {
        for (message in listOf(NpmLockfileSync.result(1, false, emptyList()), NpmLockfileSync.result(null, true, emptyList()))) {
            assertTrue(message.contains("partial changes"))
            assertTrue(message.contains("declaration undo"))
            assertTrue(message.contains("installed dependencies were not synchronized"))
        }
        val success = NpmLockfileSync.result(0, false, emptyList())
        assertTrue(success.contains("completed"))
        assertFalse(success.contains("partial changes"))
    }
}
