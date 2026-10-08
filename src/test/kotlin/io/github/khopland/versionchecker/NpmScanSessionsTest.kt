package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NpmScanSessionsTest {
    private val context = NpmResolutionContext("workspace", "configuration")
    private suspend fun sessions(block: suspend CoroutineScope.(NpmScanSessions<String>) -> Unit) = coroutineScope {
        val sessions = NpmScanSessions<String>(this)
        try { block(sessions) } finally { sessions.close() }
    }

    @Test fun `one active scan shares lazy setup across waves and later scans resolve again`(): Unit = runBlocking {
        sessions { sessions ->
            var loads = 0
            val load: suspend () -> String = { loads++; "runtime-$loads" }
            sessions.withSession(context, load) {
                assertEquals(0, loads) // Warm metadata does not need runtime setup.
                repeat(3) {
                    val wave = List(4) { async { sessions.withSession(context, load) { it.await() } } }
                    assertEquals(List(4) { "runtime-1" }, wave.awaitAll())
                }
                assertEquals(1, loads)
            }
            assertEquals("runtime-2", sessions.withSession(context, load) { it.await() })
        }
    }

    @Test fun `roots and configuration isolate sessions and refresh detaches old work`(): Unit = runBlocking {
        sessions { sessions ->
            var loads = 0
            val load: suspend () -> String = { "runtime-${++loads}" }
            sessions.withSession(context, load) { old ->
                assertEquals("runtime-1", old.await())
                assertEquals("runtime-2", sessions.withSession(context.copy(root = "other"), load) { it.await() })
                assertEquals("runtime-3", sessions.withSession(context.copy(configuration = "changed"), load) { it.await() })
                sessions.invalidate()
                sessions.withSession(context, load) { fresh ->
                    assertEquals("runtime-4", fresh.await())
                    assertEquals("runtime-1", old.await())
                    assertEquals("runtime-4", sessions.withSession(context, load) { it.await() })
                }
            }
        }
    }

    @Test fun `shared metadata worker survives cancelling its initiating check during runtime setup`(): Unit = runBlocking {
        sessions { sessions ->
            val cache = NpmMetadataCache(this)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Unit>()
            var stopped = false
            val load: suspend () -> String = {
                started.complete(Unit)
                try { gate.await(); "runtime" } finally { stopped = true }
            }
            suspend fun metadata() = cache.get(context, "pkg") {
                sessions.withSession(context, load) {
                    assertEquals("runtime", it.await())
                    NpmPackageMetadata(listOf("1.0.1"))
                }
            }
            try {
                val first = async { sessions.withSession(context, load) { metadata() } }
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    sessions.withSession(context, load) { joined.complete(Unit); metadata() }
                }
                joined.await()
                first.cancelAndJoin()
                assertFalse(stopped)
                gate.complete(Unit)
                assertEquals(listOf("1.0.1"), second.await().versions)
            } finally { cache.close() }
        }
    }

    @Test fun `last cancellation stops setup and the next check retries`(): Unit = runBlocking {
        sessions { sessions ->
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pending = async { sessions.withSession(context, {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }) { it.await() } }
            started.await()
            pending.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
            assertEquals("retry", sessions.withSession(context, { "retry" }) { it.await() })
        }
    }

    @Test fun `failed setup is not reused even while a scan lease is held`(): Unit = runBlocking {
        sessions { sessions ->
            sessions.withSession(context, { throw IOException("broken") }) {
                assertTrue(runCatching { it.await() }.exceptionOrNull() is IOException)
                assertEquals("retry", sessions.withSession(context, { "retry" }) { it.await() })
            }
        }
    }

    @Test fun `disposal stops detached and current setup workers`(): Unit = runBlocking {
        sessions { sessions ->
            val started = List(2) { CompletableDeferred<Unit>() }
            val stopped = List(2) { CompletableDeferred<Unit>() }
            val pending = List(2) { index ->
                val request = async { sessions.withSession(context, {
                    started[index].complete(Unit)
                    try { awaitCancellation() } finally { stopped[index].complete(Unit) }
                }) { it.await() } }
                started[index].await()
                sessions.invalidate()
                request
            }
            sessions.close()
            withTimeout(5_000) { stopped.forEach { it.await() }; pending.forEach { it.join() } }
            assertTrue(pending.all { it.isCancelled })
            assertTrue(runCatching { sessions.withSession(context, { "closed" }) { it.await() } }.isFailure)
        }
    }
}
