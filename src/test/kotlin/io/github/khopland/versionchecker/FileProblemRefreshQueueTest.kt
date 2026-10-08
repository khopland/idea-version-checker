package io.github.khopland.versionchecker

import kotlinx.coroutines.*
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
}
