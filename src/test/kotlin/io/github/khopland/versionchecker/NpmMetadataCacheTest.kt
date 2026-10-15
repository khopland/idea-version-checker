package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NpmMetadataCacheTest : MetadataCacheContractTest() {
    override fun createCache(scope: CoroutineScope, now: () -> Long, ttl: Long, capacity: Int): MetadataCacheContract {
        val cache = NpmMetadataCache(scope, now = now, ttl = ttl, capacity = capacity)
        return object : MetadataCacheContract {
            override suspend fun get(key: String, load: suspend () -> String): MetadataCacheSnapshot {
                val lease = cache.getFresh(context, key) { NpmPackageMetadata(listOf(load())) }
                return MetadataCacheSnapshot(lease.metadata.versions.single(), lease.expiresAt)
            }
            override fun invalidate() = cache.invalidate()
            override fun close() = cache.close()
        }
    }

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

    @Test fun `parsed history and baseline entries count against the retained version budget`(): Unit = runBlocking {
        // Two raw strings, two parsed records and two baseline-map entries consume six slots.
        for ((budget, expectedLoads) in listOf(5 to 2, 6 to 1)) cached(budget = budget) { cache ->
            var loads = 0
            val first = cache.get(context, "pkg") { loads++; data() }
            val second = cache.get(context, "pkg") { loads++; data() }
            assertEquals(expectedLoads, loads)
            if (expectedLoads == 1) assertSame(first.history, second.history)
        }
    }

    @Test fun `invalid versions and equal precedence do not add fictitious baseline entries`(): Unit = runBlocking {
        // Four raw strings + two parsed build spellings + one shared numeric baseline = seven.
        cached(budget = 7) { cache ->
            var loads = 0
            repeat(2) {
                val metadata = cache.get(context, "pkg") {
                    loads++; NpmPackageMetadata(listOf("1.0.0+a", "1.0.0+b", "1.0.0-beta.1", "invalid"))
                }
                assertEquals("1.0.0+a", metadata.history.publishedBaseline(NpmVersion(1, 0, 0)))
            }
            assertEquals(1, loads)
        }
    }
}
