package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class FileProblemRefreshQueueTest {
    @Test fun `nearby completions refresh each affected file once`() = runBlocking {
        val batch = CompletableDeferred<Set<String>>()
        val queue = FileProblemRefreshQueue(this, 20) { batch.complete(it) }
        queue.request("pom.xml")
        queue.request("package.json")
        queue.request("pom.xml")
        assertEquals(setOf("pom.xml", "package.json"), withTimeout(5_000.milliseconds) { batch.await() })
    }

    @Test fun `new completions during a refresh get their own batch`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Set<String>>()
        val batches = mutableListOf<Set<String>>()
        val queue = FileProblemRefreshQueue(this, 20) {
            batches += it
            if (batches.size == 1) { first.complete(Unit); release.await() }
            else second.complete(it)
        }
        queue.request("first/build.gradle")
        withTimeout(5_000.milliseconds) { first.await() }
        queue.request("second/build.gradle")
        assertEquals(setOf("second/build.gradle"), withTimeout(5_000.milliseconds) { second.await() })
        release.complete(Unit)
        assertEquals(2, batches.size)
    }

    @Test fun `project scope cancellation drops pending refreshes`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        var called = false
        val queue = FileProblemRefreshQueue(scope, 20) { called = true }
        queue.request("pom.xml")
        scope.cancel()
        scope.coroutineContext[Job]!!.join()
        assertFalse(called)
    }

    @Test fun `continuous completions do not postpone the first refresh`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val queue = FileProblemRefreshQueue(this, 20) { first.complete(Unit) }
        val producer = launch {
            repeat(10_000) { queue.request("build.gradle"); delay(5.milliseconds) }
        }
        withTimeout(5_000.milliseconds) { first.await() }
        assertTrue(producer.isActive)
        producer.cancelAndJoin()
    }

    @Test fun `selected files are promoted without refreshing twice or dragging background files along`() = runBlocking {
        val batches = Channel<Set<String>>(Channel.UNLIMITED)
        val queue = FileProblemRefreshQueue(this, windowMillis = 1_000, activeWindowMillis = 20) {
            batches.send(it)
        }
        queue.request("selected/pom.xml")
        queue.request("background/pom.xml")
        queue.request("selected/pom.xml", active = true)
        queue.request("selected/pom.xml", active = true)
        queue.request("selected/pom.xml")
        assertEquals(setOf("selected/pom.xml"), withTimeout(5_000) { batches.receive() })
        assertEquals(setOf("background/pom.xml"), withTimeout(5_000) { batches.receive() })
        assertTrue(batches.tryReceive().isFailure)
    }

    @Test fun `continuous selected file completions do not postpone the fast refresh`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val queue = FileProblemRefreshQueue(this, windowMillis = 10_000, activeWindowMillis = 20) {
            first.complete(Unit)
        }
        val producer = launch {
            repeat(10_000) { queue.request("build.gradle", active = true); delay(5.milliseconds) }
        }
        withTimeout(5_000) { first.await() }
        assertTrue(producer.isActive)
        producer.cancelAndJoin()
    }

    @Test fun `cancellation drops both selected and background refreshes`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        var called = false
        val queue = FileProblemRefreshQueue(scope, 20, 10) { called = true }
        queue.request("background/pom.xml")
        queue.request("selected/pom.xml", active = true)
        scope.cancel()
        scope.coroutineContext[Job]!!.join()
        assertFalse(called)
    }
}
