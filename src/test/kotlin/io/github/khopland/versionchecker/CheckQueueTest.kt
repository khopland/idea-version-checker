package io.github.khopland.versionchecker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CheckQueueTest {
    @Test fun `selected work overtakes the remaining batches without concurrent checks`() = runBlocking {
        val queue = CheckQueue { it == "selected" }
        val firstBatch = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val background = launch {
            queue.withSlot("background") {
                order += "first batch"; firstBatch.complete(Unit); release.await()
                CheckQueue.yieldCurrent()
                order += "remaining batches"
            }
        }
        firstBatch.await()
        val selected = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("selected") { order += "selected" }
        }
        release.complete(Unit)
        joinAll(background, selected)
        assertEquals(listOf("first batch", "selected", "remaining batches"), order)
        assertFalse(queue.isBusy)
    }

    @Test fun `yield keeps higher priority active work ahead of background requests`() = runBlocking {
        val queue = CheckQueue()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val interactive = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("manual", true) { release.await(); CheckQueue.yieldCurrent(); order += "manual" }
        }
        val background = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("background") { order += "background" } }
        release.complete(Unit)
        joinAll(interactive, background)
        assertEquals(listOf("manual", "background"), order)
    }

    @Test fun `equal priority checks rotate fairly at batch boundaries`() = runBlocking {
        val queue = CheckQueue()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("first") { release.await(); order += "first-1"; CheckQueue.yieldCurrent(); order += "first-2" }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("second") { order += "second-1"; CheckQueue.yieldCurrent(); order += "second-2" }
        }
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("first-1", "second-1", "first-2", "second-2"), order)
    }

    @Test fun `cancelling a yielded request leaves the next native slot usable`() = runBlocking {
        val queue = CheckQueue { it == "selected" }
        val release = CompletableDeferred<Unit>()
        val selectedStarted = CompletableDeferred<Unit>()
        val finishSelected = CompletableDeferred<Unit>()
        val background = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("background") { release.await(); CheckQueue.yieldCurrent(); fail("Cancelled batch resumed") }
        }
        val selected = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot("selected") { selectedStarted.complete(Unit); finishSelected.await() }
        }
        release.complete(Unit); selectedStarted.await()
        background.cancelAndJoin()
        assertEquals(0, queue.waitingCount)
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.withSlot("next") { "completed" } }
        finishSelected.complete(Unit); selected.join()
        assertEquals("completed", withTimeout(1000) { next.await() })
        assertFalse(queue.isBusy)
    }

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
