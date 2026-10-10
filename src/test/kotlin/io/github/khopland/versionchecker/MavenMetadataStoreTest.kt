package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class MavenMetadataStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val file get() = temporary.root.toPath().resolve("cache/histories.bin")
    private var millis = 10_000L
    private var nanos = TimeUnit.MILLISECONDS.toNanos(millis)
    private val key = MavenMetadataKey("private.group", "library")
    private val value = MavenMetadataValue(listOf("1.0", "1.1", "2.0"))
    private val ttl = TimeUnit.MINUTES.toNanos(10)
    private fun store(capacity: Int = 2048, versions: Int = 100_000, characters: Int = 8_000_000) =
        MavenMetadataStore({ file }, now = { nanos }, wallTime = { millis }, capacity = capacity,
            versionBudget = versions, characterBudget = characters)
    private fun lease(value: MavenMetadataValue = this.value) = MavenMetadataLease(value, nanos + ttl, nanos)
    private fun advance(millis: Long) { this.millis += millis; nanos += TimeUnit.MILLISECONDS.toNanos(millis) }

    @Test fun `reopening preserves histories prerequisites original age and deadline`() = runBlocking {
        val original = lease()
        val plugin = key.copy(plugin = true)
        val pom = plugin.copy(version = "2.0")
        val first = store()
        first.write("context-A", mapOf(key to original, plugin to original, pom to lease(MavenMetadataValue(requiredMaven = "3.9"))), 0)
        first.close(); advance(120_000)
        val second = store()
        val reused = second.read("context-A", listOf(key, plugin, pom), 0)
        assertEquals(3, reused.size)
        assertEquals(value, reused.getValue(key).value)
        assertEquals("3.9", reused.getValue(pom).value.requiredMaven)
        assertEquals(original.checkedAtNanos, reused.getValue(key).checkedAtNanos)
        assertTrue(reused.getValue(key).expiresAt <= original.expiresAt)
        assertTrue(second.read("context-B", listOf(key), 0).isEmpty())
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null)
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
    }

    @Test fun `expired data clock changes and different monotonic origins are misses`() = runBlocking {
        store().write("context", mapOf(key to lease()), 0)
        advance(600_000)
        assertTrue(store().read("context", listOf(key), 0).isEmpty())
        millis = 10_000; nanos = TimeUnit.MILLISECONDS.toNanos(millis)
        store().write("context", mapOf(key to lease()), 0)
        advance(120_000); millis -= 60_000
        assertTrue(store().read("context", listOf(key), 0).isEmpty())
        millis += 60_000; nanos += TimeUnit.SECONDS.toNanos(30)
        assertTrue(store().read("context", listOf(key), 0).isEmpty())
    }

    @Test fun `delayed clock sampling preserves fresh data without extending its deadline`() = runBlocking {
        var delay = 0L
        fun sampled() = MavenMetadataStore({ file }, now = { advance(delay); nanos }, wallTime = { millis })
        val original = lease()
        sampled().write("context", mapOf(key to original), 0)
        delay = 20
        val reopened = sampled().read("context", listOf(key), 0).getValue(key)
        assertEquals(original.checkedAtNanos, reopened.checkedAtNanos)
        assertTrue(reopened.expiresAt <= original.expiresAt)
    }

    @Test fun `refresh bypasses prior disk records and old writers cannot replace refreshed data`() = runBlocking {
        val disk = store()
        disk.write("context", mapOf(key to lease()), 0)
        disk.invalidate()
        assertTrue(disk.read("context", listOf(key), 1).isEmpty())
        val fresh = MavenMetadataValue(listOf("3.0"))
        disk.write("context", mapOf(key to lease(fresh)), 1)
        disk.write("context", mapOf(key to lease()), 0)
        disk.clearInvalidated()
        assertEquals(fresh, store().read("context", listOf(key), 0).getValue(key).value)
    }

    @Test fun `corruption truncation and unknown formats fall back to native lookup`() = runBlocking {
        store().write("context", mapOf(key to lease()), 0)
        val bytes = Files.readAllBytes(file)
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (invalid in listOf(changed, bytes.copyOf(12), ByteArray(16) { 99 })) {
            Files.write(file, invalid)
            assertTrue(store().read("context", listOf(key), 0).isEmpty())
        }
        store().write("context", mapOf(key to lease()), 0)
        assertEquals(value, store().read("context", listOf(key), 0).getValue(key).value)
    }

    @Test fun `persistence enforces entry version and character budgets`() = runBlocking {
        val disk = store(capacity = 1)
        disk.write("context", mapOf(key to lease(), key.copy(artifact = "other") to lease()), 0)
        assertEquals(setOf(key.copy(artifact = "other")), store(capacity = 1).read("context", listOf(key, key.copy(artifact = "other")), 0).keys)
        Files.delete(file)
        store(versions = 2).write("context", mapOf(key to lease()), 0)
        assertTrue(store(versions = 2).read("context", listOf(key), 0).isEmpty())
        Files.delete(file)
        store(characters = 1).write("context", mapOf(key to lease()), 0)
        assertTrue(store(characters = 1).read("context", listOf(key), 0).isEmpty())
    }

    @Test fun `disk failures do not discard successful native metadata`() = runBlocking {
        Files.createDirectories(file.parent)
        Files.createDirectory(file) // This path cannot be atomically replaced by a regular file.
        val cache = MavenMetadataCache(this, now = { nanos }, ttl = ttl, persistence = store(), persistEnabled = { true })
        try {
            val result = cache.getMany("context", listOf(key)) { mapOf(key to Result.success(value)) }
            assertEquals(value, result.getValue(key).getOrThrow().value)
        } finally { cache.close() }
    }

    @Test fun `cache restart reuses successes retries failures and refresh performs fresh work`() = runBlocking {
        val b = key.copy(artifact = "failed")
        val first = MavenMetadataCache(this, now = { nanos }, ttl = ttl, persistence = store(), persistEnabled = { true })
        val original = first.getMany("context", listOf(key, b)) {
            mapOf(key to Result.success(value), b to Result.failure(java.io.IOException("offline")))
        }.getValue(key).getOrThrow()
        first.close(); advance(120_000)
        val reopened = MavenMetadataCache(this, now = { nanos }, ttl = ttl, persistence = store(), persistEnabled = { true })
        try {
            val resumed = reopened.getMany("context", listOf(key, b)) { keys ->
                assertEquals(listOf(b), keys); mapOf(b to Result.success(value))
            }
            assertEquals(original.checkedAtNanos, resumed.getValue(key).getOrThrow().checkedAtNanos)
            assertTrue(resumed.getValue(key).getOrThrow().expiresAt <= original.expiresAt)
            reopened.invalidate()
            reopened.getMany("context", listOf(key, b)) { keys ->
                assertEquals(setOf(key, b), keys.toSet()); keys.associateWith { Result.success(value) }
            }
        } finally { reopened.close() }
        Unit
    }

    @Test fun `an old in-flight response cannot overwrite refreshed persisted metadata`() = runBlocking {
        val disk = store()
        val cache = MavenMetadataCache(this, now = { nanos }, ttl = ttl, persistence = disk, persistEnabled = { true })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val old = async { cache.getMany("context", listOf(key)) {
                started.complete(Unit); release.await(); mapOf(key to Result.success(value))
            } }
            started.await(); cache.invalidate()
            val fresh = MavenMetadataValue(listOf("3.0"))
            cache.getMany("context", listOf(key)) { mapOf(key to Result.success(fresh)) }
            release.complete(Unit)
            assertEquals(value, old.await().getValue(key).getOrThrow().value)
            assertEquals(fresh, store().read("context", listOf(key), 0).getValue(key).value)
        } finally { release.complete(Unit); cache.close() }
    }
}
