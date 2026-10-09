package io.github.khopland.versionchecker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One native check at a time, with priorities evaluated again whenever a slot is released. */
internal class CheckQueue(private val isSelected: (String) -> Boolean = { false }) {
    private class Request(val path: String, val interactive: Boolean) {
        val ready = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val waiting = mutableListOf<Request>()
    private var active: Request? = null

    val isBusy: Boolean get() = synchronized(lock) { active != null }
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> withSlot(path: String, interactive: Boolean = false, check: suspend () -> T): T {
        val request = Request(path, interactive)
        synchronized(lock) {
            if (active == null) {
                active = request
                request.ready.complete(Unit)
            } else waiting += request
        }
        try {
            request.ready.await()
            currentCoroutineContext().ensureActive()
            return check()
        } finally {
            synchronized(lock) {
                if (active === request) {
                    // maxByOrNull preserves FIFO order among requests with the same priority.
                    active = waiting.maxByOrNull {
                        when {
                            it.interactive -> 2
                            isSelected(it.path) -> 1
                            else -> 0
                        }
                    }
                    active?.let { next -> waiting.remove(next); next.ready.complete(Unit) }
                } else waiting.remove(request)
            }
        }
    }
}
