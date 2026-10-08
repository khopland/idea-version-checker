package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NpmMetadataCacheTest {
    private val context = NpmResolutionContext("workspace", "configuration")
    private fun data(version: String = "1.0.1") = NpmPackageMetadata(listOf("1.0.0", version), emptyMap())

    private suspend fun <T> cached(capacity: Int = 512, budget: Int = 100_000, now: () -> Long = System::nanoTime,
                                   ttl: Long = Long.MAX_VALUE / 2, block: suspend CoroutineScope.(NpmMetadataCache) -> T): T = coroutineScope {
        val cache = NpmMetadataCache(this, now, ttl, capacity, budget)
        try { block(cache) } finally { cache.close() }
    }

    @Test fun `same package reuses complete metadata across callers and preserves raw notices`(): Unit = runBlocking {
        cached { cache ->
            var loads = 0
            val expected = NpmPackageMetadata(listOf("1.0.0", "1.0.1", "2.0.0"), mapOf("1.0.0" to "Retired"))
            repeat(100) { assertEquals(expected, cache.get(context, "pkg") { loads++; expected }) }
            assertEquals(1, loads)
        }
    }

    @Test fun `different roots configurations and packages never share requests`(): Unit = runBlocking {
        cached { cache ->
            var loads = 0
            for ((ctx, name) in listOf(context to "pkg", context.copy(root = "other") to "pkg",
                context.copy(configuration = "changed") to "pkg", context to "other")) {
                cache.get(ctx, name) { loads++; data() }
            }
            assertEquals(4, loads)
        }
    }
    @Test fun `ten thousand repeated workspace packages need one hundred loads per generation`(): Unit = runBlocking {
        cached { cache ->
            var loads = 0
            repeat(2) {
                repeat(100) {
                    repeat(100) { packageIndex -> cache.get(context, "pkg-$packageIndex") { loads++; data() } }
                }
                assertEquals((it + 1) * 100, loads)
                cache.invalidate()
            }
        }
    }

    @Test fun `concurrent callers share an in-flight request and cancelling one preserves the other`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            var stopped = false
            val first = async { cache.get(context, "pkg") {
                loads++; started.complete(Unit)
                try { gate.await(); data() } finally { stopped = true }
            } }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get(context, "pkg") { error("Duplicate request") } }
            first.cancelAndJoin()
            assertFalse(stopped)
            gate.complete(Unit)
            assertEquals(data(), second.await())
            assertEquals(1, loads)
        }
    }

    @Test fun `last cancelled caller stops native work and cancellation is never cached`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val first = async { cache.get(context, "pkg") {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } }
            started.await()
            first.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
            assertEquals(data(), cache.get(context, "pkg") { data() })
        }
    }

    @Test fun `fresh generation cannot join or be populated by an older request`(): Unit = runBlocking {
        cached { cache ->
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val old = async { cache.get(context, "pkg") { started.complete(Unit); gate.await(); data("1.0.1") } }
            started.await()
            cache.invalidate()
            assertEquals(data("1.0.2"), cache.get(context, "pkg") { data("1.0.2") })
            gate.complete(Unit)
            assertEquals(data("1.0.1"), old.await())
            assertEquals(data("1.0.2"), cache.get(context, "pkg") { error("Old completion replaced the fresh entry") })
        }
    }

    @Test fun `ttl begins at successful completion and expiry refetches`(): Unit = runBlocking {
        var clock = 0L
        cached(now = { clock }, ttl = 10) { cache ->
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val request = async { cache.getFresh(context, "pkg") { loads++; started.complete(Unit); gate.await(); data() } }
            started.await()
            clock = 100
            gate.complete(Unit)
            val lease = request.await()
            assertEquals(110L, lease.expiresAt)
            clock = 109
            assertEquals(lease, cache.getFresh(context, "pkg") { loads++; data() })
            assertEquals(1, loads)
            clock = 110
            cache.get(context, "pkg") { loads++; data() }
            assertEquals(2, loads)
        }
    }

    @Test fun `failures propagate without cancelling unrelated packages or caching the failure`(): Unit = runBlocking {
        cached { cache ->
            val broken = IOException("Failed")
            val failure = runCatching { cache.get(context, "pkg") { throw broken } }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(broken.message, failure!!.message)
            assertEquals(data(), cache.get(context, "other") { data() })
            assertEquals(data(), cache.get(context, "pkg") { data() })
        }
    }

    @Test fun `entry and version limits evict completed metadata without evicting in-flight work`(): Unit = runBlocking {
        for ((capacity, budget) in listOf(2 to 100, 100 to 4)) cached(capacity, budget) { cache ->
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val pending = async { cache.get(context, "pending") { started.complete(Unit); gate.await(); data() } }
            started.await()
            cache.get(context, "one") { data() }
            cache.get(context, "two") { data() }
            cache.get(context, "three") { data() }
            val joined = async(start = CoroutineStart.UNDISPATCHED) { cache.get(context, "pending") { error("In-flight request was evicted") } }
            gate.complete(Unit)
            assertEquals(data(), pending.await())
            assertEquals(data(), joined.await())
            var refetched = false
            cache.get(context, "one") { refetched = true; data() }
            assertTrue(refetched)
        }
    }

    @Test fun `oversized histories are shared while loading but are not retained`(): Unit = runBlocking {
        cached(budget = 1) { cache ->
            var loads = 0
            repeat(2) { cache.get(context, "pkg") { loads++; data() } }
            assertEquals(2, loads)
        }
    }
    @Test fun `large deprecation text cannot exceed the retained character budget`(): Unit = runBlocking {
        val cache = NpmMetadataCache(this, characterBudget = 100)
        try {
            var loads = 0
            repeat(2) { cache.get(context, "pkg") {
                loads++
                NpmPackageMetadata(listOf("1.0.0"), mapOf("1.0.0" to "x".repeat(1_000)))
            } }
            assertEquals(2, loads)
        } finally { cache.close() }
    }

    @Test fun `disposal stops old and current generation workers and clears retained results`(): Unit = runBlocking {
        val cache = NpmMetadataCache(this)
        try {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pending = async { cache.get(context, "pkg") {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } }
            started.await()
            cache.invalidate()
            cache.close()
            withTimeout(5_000) { stopped.await() }
            pending.join()
            assertTrue(pending.isCancelled)
            assertTrue(runCatching { cache.get(context, "pkg") { data() } }.isFailure)
        } finally { cache.close() }
    }
}
