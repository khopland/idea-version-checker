package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CheckQueueTest {
    @Test fun `interactive checks and the newly selected file overtake waiting background checks`() = runBlocking {
        var selected = "old-selection"
        val queue = CheckQueue { it == selected }
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch {
            queue.withSlot("running") { firstStarted.complete(Unit); release.await() }
        }
        firstStarted.await()
        val background = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("background") { order += "background" } }
        val active = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("new-selection") { order += "selected" } }
        val manual = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("manual", interactive = true) { order += "manual" } }
        assertEquals(3, queue.waitingCount)
        selected = "new-selection"
        release.complete(Unit)
        joinAll(first, background, active, manual)
        assertEquals(listOf("manual", "selected", "background"), order)
        assertFalse(queue.isBusy)
    }

    @Test fun `cancelling a queued request removes it and preserves FIFO among equal priorities`() = runBlocking {
        val queue = CheckQueue()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("running") { release.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("cancelled", true) { fail("Cancelled request ran") } }
        val order = mutableListOf<Int>()
        val others = (1..3).map { number ->
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("file-$number") { order += number } }
        }
        cancelled.cancelAndJoin()
        assertEquals(3, queue.waitingCount)
        release.complete(Unit)
        first.join(); others.joinAll()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test fun `cancelling the active check releases its slot`() = runBlocking {
        val queue = CheckQueue()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("running") { awaitCancellation() } }
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("next") { "completed" } }
        first.cancelAndJoin()
        assertEquals("completed", withTimeout(5_000) { next.await() })
        assertFalse(queue.isBusy)
    }

    @Test fun `a cancelled handoff cannot strand the next request`() = runBlocking {
        val queue = CheckQueue()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("running") { release.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("cancelled") { awaitCancellation() } }
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("next") { "completed" } }
        release.complete(Unit)
        first.join() // Cancellation may race the handoff or the resumed check.
        cancelled.cancelAndJoin()
        assertEquals("completed", withTimeout(5_000) { next.await() })
    }
}
