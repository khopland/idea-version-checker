package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Test-only view of lifecycle behavior shared by the real npm and Maven caches. */
interface MetadataCacheContract {
    suspend fun get(key: String, load: suspend () -> String): MetadataCacheSnapshot
    fun invalidate()
    fun close()
}

data class MetadataCacheSnapshot(val version: String, val expiresAt: Long)

/** Inherited tests leave batching, persistence, payloads and budget accounting to each provider. */
abstract class MetadataCacheContractTest {
    abstract fun createCache(scope: CoroutineScope, now: () -> Long, ttl: Long, capacity: Int): MetadataCacheContract

    private suspend fun cached(
        now: () -> Long = System::nanoTime,
        ttl: Long = Long.MAX_VALUE / 2,
        capacity: Int = 512,
        block: suspend CoroutineScope.(MetadataCacheContract) -> Unit
    ) = coroutineScope {
        val cache = createCache(this, now, ttl, capacity)
        try { withTimeout(10_000) { block(cache) } } finally { cache.close() }
    }

    @Test fun `shared lifecycle - cancelling one caller preserves the shared worker`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val first = async { cache.get("pkg") {
                started.complete(Unit)
                try { release.await(); "1.0.1" } finally { stopped.complete(Unit) }
            } }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("pkg") { error("A concurrent caller must join the existing worker") }
            }
            first.cancelAndJoin()
            assertFalse(stopped.isCompleted)
            release.complete(Unit)
            assertEquals("1.0.1", second.await().version)
        }
    }

    @Test fun `shared lifecycle - last caller cancellation stops work and allows a retry`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val caller = async { cache.get("pkg") {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } }
            started.await()
            caller.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
            assertEquals("1.0.2", cache.get("pkg") { "1.0.2" }.version)
        }
    }

    @Test fun `shared lifecycle - old generation completion cannot replace fresh metadata`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = async { cache.get("pkg") { started.complete(Unit); release.await(); "1.0.1" } }
            started.await()
            cache.invalidate()
            assertEquals("1.0.2", cache.get("pkg") { "1.0.2" }.version)
            release.complete(Unit)
            assertEquals("1.0.1", old.await().version)
            assertEquals("1.0.2", cache.get("pkg") { error("Stale completion replaced the fresh entry") }.version)
        }
    }

    @Test fun `shared lifecycle - freshness starts at completion and expires at the boundary`(): Unit = runBlocking {
        var clock = 0L
        cached(now = { clock }, ttl = 10) { cache ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var loads = 0
            val caller = async { cache.get("pkg") { loads++; started.complete(Unit); release.await(); "1.0.1" } }
            started.await()
            clock = 100
            release.complete(Unit)
            val first = caller.await()
            assertEquals(110L, first.expiresAt)
            clock = 109
            assertEquals(first, cache.get("pkg") { error("Fresh metadata must be retained") })
            clock = 110
            val next = cache.get("pkg") { loads++; "1.0.2" }
            assertEquals("1.0.2", next.version)
            assertEquals(120L, next.expiresAt)
            assertEquals(2, loads)
        }
    }

    @Test fun `shared lifecycle - capacity evicts completed entries while preserving in-flight work`(): Unit = runBlocking {
        cached(capacity = 2) { cache ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = async { cache.get("pending") { started.complete(Unit); release.await(); "1.0.1" } }
            started.await()
            for (key in listOf("one", "two", "three")) cache.get(key) { "1.0.1" }
            val joined = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("pending") { error("An in-flight worker must not be evicted") }
            }
            release.complete(Unit)
            assertEquals(pending.await(), joined.await())
            var reloaded = false
            cache.get("one") { reloaded = true; "1.0.1" }
            assertTrue("The oldest completed entry must be evicted", reloaded)
        }
    }

    @Test fun `shared lifecycle - disposal stops old and current workers and rejects further requests`(): Unit = runBlocking {
        cached { cache ->
            suspend fun CoroutineScope.pending(key: String): Pair<Deferred<MetadataCacheSnapshot>, CompletableDeferred<Unit>> {
                val started = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                val caller = async { cache.get(key) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                } }
                started.await()
                return caller to stopped
            }
            val (old, oldStopped) = pending("pkg")
            cache.invalidate()
            val (current, currentStopped) = pending("pkg")
            cache.close()
            withTimeout(5_000) {
                oldStopped.await(); currentStopped.await()
                old.join(); current.join()
            }
            assertTrue(old.isCancelled)
            assertTrue(current.isCancelled)
            assertTrue(runCatching { cache.get("pkg") { "1.0.1" } }.exceptionOrNull() is IllegalStateException)
        }
    }
}
