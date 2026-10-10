package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MavenMetadataCacheTest {
    private val a = MavenMetadataKey("g", "a")
    private val b = MavenMetadataKey("g", "b")
    private val value = MavenMetadataValue(listOf("1.0", "1.0.1", "1.1", "2.0", "3.0-RC1", "4-SNAPSHOT"))

    @Test fun `one version index answers different baselines and modes without extending freshness`() = runBlocking {
        var now = 0L
        val cache = MavenMetadataCache(this, now = { now }, ttl = 10)
        var calls = 0
        suspend fun get() = cache.getMany("context", listOf(a)) { calls++; mapOf(a to Result.success(value)) }.getValue(a).getOrThrow()
        try {
            val first = get()
            assertEquals(10L, first.expiresAt)
            assertEquals("1.0.1", first.value.versionIndex.latest("1.0", UpdateMode.PATCH))
            now = 5
            assertEquals("1.1", get().value.versionIndex.latest("1.0", UpdateMode.MINOR))
            assertEquals("2.0", get().value.versionIndex.latest("1.1", UpdateMode.MAJOR))
            assertEquals(10L, get().expiresAt)
            assertEquals(1, calls)
            now = 10
            assertEquals(20L, get().expiresAt)
            assertEquals(2, calls)
        } finally { cache.close() }
    }

    @Test fun `overlapping batches share native work and survive one cancelled consumer`() = runBlocking {
        val cache = MavenMetadataCache(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            val first = async { cache.getMany("context", listOf(a, b)) { keys ->
                calls.incrementAndGet(); started.complete(Unit); release.await(); keys.associateWith { Result.success(value) }
            } }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.getMany("context", listOf(a)) { error("Must share the active batch") } }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(value, second.await().getValue(a).getOrThrow().value)
            assertEquals(1, calls.get())
        } finally { cache.close() }
    }

    @Test fun `last caller cancellation and disposal terminate native batch workers`() = runBlocking {
        val cache = MavenMetadataCache(this)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val caller = async { cache.getMany("context", listOf(a)) {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        started.await(); caller.cancelAndJoin(); withTimeout(1000) { stopped.await() }
        val againStarted = CompletableDeferred<Unit>()
        val againStopped = CompletableDeferred<Unit>()
        val again = async { cache.getMany("context", listOf(a)) {
            againStarted.complete(Unit)
            try { awaitCancellation() } finally { againStopped.complete(Unit) }
        } }
        againStarted.await(); cache.close()
        withTimeout(1000) { againStopped.await() }
        try { again.await(); fail("Disposed work must be cancelled") } catch (_: CancellationException) { }
    }

    @Test fun `a shared native worker retains the refresh session after its owner is cancelled`() = runBlocking {
        val cache = MavenMetadataCache(this)
        val sessionReady = CompletableDeferred<MavenScanSession>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        try {
            val owner = async {
                withMavenScanSession {
                    val session = currentCoroutineContext()[MavenScanSession]!!
                    sessionReady.complete(session)
                    cache.getMany("context", listOf(a), onWorkerCreated = { job ->
                        session.retain(); job.invokeOnCompletion { session.release(); closed.complete(Unit) }
                    }) {
                        withContext(session) {
                            started.complete(Unit); release.await(); mapOf(a to Result.success(value))
                        }
                    }
                }
            }
            started.await()
            val consumer = async(start = CoroutineStart.UNDISPATCHED) {
                cache.getMany("context", listOf(a)) { error("Must join the retained native worker") }
            }
            owner.cancelAndJoin()
            val session = sessionReady.await()
            session.retain(); session.release() // A surviving worker still owns the session.
            release.complete(Unit)
            assertEquals(value, consumer.await().getValue(a).getOrThrow().value)
            withTimeout(1000) { closed.await() }
            assertTrue("The final worker must release its session", runCatching { session.retain() }.isFailure)
        } finally { cache.close() }
    }

    @Test fun `disposal before a queued worker starts wakes its waiting consumer`() = runBlocking {
        val cache = MavenMetadataCache(this)
        try {
            withTimeout(1000) {
                try {
                    cache.getMany("context", listOf(a), onWorkerCreated = { cache.close() }) {
                        error("Disposed native work must never start")
                    }
                    fail("Disposal must cancel the waiting consumer")
                } catch (cancelled: CancellationException) {
                    assertFalse("The consumer must wake before the timeout", cancelled is TimeoutCancellationException)
                }
            }
        } finally { cache.close() }
    }

    @Test fun `refresh detaches older workers from the next generation`() = runBlocking {
        val cache = MavenMetadataCache(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val old = async { cache.getMany("context", listOf(a)) {
                started.complete(Unit); release.await(); mapOf(a to Result.success(value))
            } }
            started.await(); cache.invalidate()
            val newer = MavenMetadataValue(listOf("5.0"))
            val fresh = cache.getMany("context", listOf(a)) { mapOf(a to Result.success(newer)) }
            release.complete(Unit)
            assertEquals(value, old.await().getValue(a).getOrThrow().value)
            assertEquals(newer, fresh.getValue(a).getOrThrow().value)
            assertEquals(newer, cache.getMany("context", listOf(a)) { error("Old completion must not replace fresh metadata") }.getValue(a).getOrThrow().value)
        } finally { cache.close() }
    }

    @Test fun `artifact failures retry independently while successful siblings remain cached`() = runBlocking {
        val cache = MavenMetadataCache(this)
        try {
            val first = cache.getMany("context", listOf(a, b)) { mapOf(a to Result.success(value), b to Result.failure(IOException("401"))) }
            assertTrue(first.getValue(a).isSuccess); assertTrue(first.getValue(b).isFailure)
            val second = cache.getMany("context", listOf(a, b)) { keys ->
                assertEquals(listOf(b), keys); mapOf(b to Result.success(value))
            }
            assertTrue(second.values.all { it.isSuccess })
        } finally { cache.close() }
    }

    @Test fun `repository context plugin repository and candidate POM keys stay isolated`() = runBlocking {
        val cache = MavenMetadataCache(this)
        var calls = 0
        try {
            val plugin = a.copy(plugin = true)
            val pom = plugin.copy(version = "2.0")
            for (context in listOf("same-id-url-A-auth-A", "same-id-url-B-auth-A", "same-id-url-A-auth-B")) {
                val result = cache.getMany(context, listOf(a, plugin, pom)) { keys -> calls++; keys.associateWith { Result.success(value) } }
                assertEquals(3, result.size)
            }
            assertEquals(3, calls)
        } finally { cache.close() }
    }

    @Test fun `oversized histories serve current callers but are not retained`() = runBlocking {
        val cache = MavenMetadataCache(this, versionBudget = 1)
        var calls = 0
        try {
            repeat(2) { assertEquals(value, cache.getMany("context", listOf(a)) { calls++; mapOf(a to Result.success(value)) }.getValue(a).getOrThrow().value) }
            assertEquals(2, calls)
        } finally { cache.close() }
    }

    @Test fun `plugin candidate lists preserve branch ordering and stable version rules`() {
        assertEquals(listOf("2.0", "1.1", "1.0.1"), MavenBatchedLookup.eligible(value.versions, "1.0", UpdateMode.MAJOR))
        assertEquals(listOf("1.1", "1.0.1"), MavenBatchedLookup.eligible(value.versions, "1.0", UpdateMode.MINOR))
        assertEquals(listOf("1.0.1"), MavenBatchedLookup.eligible(value.versions, "1.0", UpdateMode.PATCH))
    }
}
