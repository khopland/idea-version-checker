package io.github.khopland.versionchecker.npm

import kotlinx.coroutines.*

/** Share lazy runtime setup only while compatible checks or their metadata workers are active. */
internal class NpmScanSessions<T>(scope: CoroutineScope) {
    private data class Key(val context: NpmResolutionContext, val generation: Long)
    private class Entry<T>(val request: Deferred<T>) { var users = 0 }
    private val lock = Any()
    private val worker = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + worker)
    private val entries = mutableMapOf<Key, Entry<T>>()
    private var generation = 0L

    suspend fun <R> withSession(context: NpmResolutionContext, load: suspend () -> T,
                                block: suspend (Deferred<T>) -> R): R {
        currentCoroutineContext().ensureActive()
        val entry = synchronized(lock) {
            check(worker.isActive) { "npm runtime sessions are disposed" }
            val key = Key(context, generation)
            val selected = entries.getOrPut(key) {
                Entry(workers.async(io.github.khopland.versionchecker.CheckPerformance.context(), start = CoroutineStart.LAZY) { load() }).also { created ->
                    created.request.invokeOnCompletion { failure -> synchronized(lock) {
                        if (failure != null && entries[key] === created) entries.remove(key)
                    } }
                }
            }
            selected.users++
            selected
        }
        try {
            return block(entry.request)
        } finally {
            synchronized(lock) {
                entry.users--
                if (entry.users == 0) {
                    entries.entries.removeIf { it.value === entry }
                    entry.request.cancel()
                }
            }
        }
    }

    // Existing callers finish independently; refreshes acquire a fresh runtime.
    fun invalidate() = synchronized(lock) { generation++; entries.clear() }
    fun close() = synchronized(lock) { generation++; entries.clear(); worker.cancel() }
}
