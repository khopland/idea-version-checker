package io.github.khopland.versionchecker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One native check at a time, with priorities evaluated again whenever a slot is released. */
internal class CheckQueue(private val isSelected: (String) -> Boolean = { false }) {
    private class Request(val queue: CheckQueue, val path: String, val interactive: Boolean) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Request>
        var ready = CompletableDeferred<Unit>()
    }

    companion object {
        /** Native adapters call this only between completed goals, while no embedder is borrowed. */
        suspend fun yieldCurrent() {
            currentCoroutineContext()[Request]?.let { it.queue.yieldSlot(it) }
        }
    }

    private val lock = Any()
    private val waiting = mutableListOf<Request>()
    private var active: Request? = null

    val isBusy: Boolean get() = synchronized(lock) { active != null }
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> withSlot(path: String, interactive: Boolean = false, check: suspend () -> T): T {
        val request = Request(this, path, interactive)
        synchronized(lock) {
            if (active == null) {
                active = request
                request.ready.complete(Unit)
            } else waiting += request
        }
        try {
            request.ready.await()
            currentCoroutineContext().ensureActive()
            return withContext(request) { check() }
        } finally {
            synchronized(lock) {
                if (active === request) {
                    handoff()
                } else waiting.remove(request)
            }
        }
    }

    private fun priority(request: Request) = when {
        request.interactive -> 2
        isSelected(request.path) -> 1
        else -> 0
    }

    /** Called under lock. maxByOrNull preserves FIFO order for equal priorities. */
    private fun handoff() {
        active = waiting.maxByOrNull(::priority)
        active?.let { next -> waiting.remove(next); next.ready.complete(Unit) }
    }

    private suspend fun yieldSlot(request: Request) {
        currentCoroutineContext().ensureActive()
        val ready = synchronized(lock) {
            if (active !== request || waiting.none { priority(it) >= priority(request) }) return
            request.ready = CompletableDeferred()
            waiting += request
            handoff()
            request.ready
        }
        val started = System.nanoTime()
        try {
            ready.await()
            currentCoroutineContext().ensureActive()
        } finally { CheckPerformance.record(CheckPerformance.Stage.CHECK_QUEUE, started) }
    }
}
