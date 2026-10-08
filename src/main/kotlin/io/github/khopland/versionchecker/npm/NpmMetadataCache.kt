package io.github.khopland.versionchecker.npm

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** File declarations are deliberately absent: this identifies native resolution configuration. */
internal data class NpmResolutionContext(val root: String, val configuration: String)
internal data class NpmMetadataLease(val metadata: NpmPackageMetadata, val expiresAt: Long)

/** Shared workers survive one cancelled caller, but stop when their last caller leaves. */
internal class NpmMetadataCache(
    scope: CoroutineScope,
    private val now: () -> Long = System::nanoTime,
    private val ttl: Long = TimeUnit.MINUTES.toNanos(10),
    private val capacity: Int = 512,
    private val versionBudget: Int = 100_000,
    private val characterBudget: Int = 8_000_000
) {
    private data class Key(val context: NpmResolutionContext, val name: String, val generation: Long)
    private class Entry(val request: Deferred<NpmMetadataLease>) {
        var callers = 0
        var expires = Long.MAX_VALUE
        var weight = 0
        var characters = 0L
    }
    private val lock = Any()
    private val worker = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + worker)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var generation = 0L

    suspend fun get(context: NpmResolutionContext, name: String, load: suspend () -> NpmPackageMetadata): NpmPackageMetadata =
        getFresh(context, name, load).metadata

    suspend fun getFresh(context: NpmResolutionContext, name: String, load: suspend () -> NpmPackageMetadata): NpmMetadataLease {
        currentCoroutineContext().ensureActive()
        val entry = synchronized(lock) {
            check(worker.isActive) { "npm metadata cache is disposed" }
            entries.entries.removeIf { it.value.request.isCompleted && it.value.expires <= now() }
            val key = Key(context, name, generation)
            val selected = entries[key] ?: run {
                lateinit var created: Entry
                val request = workers.async(start = CoroutineStart.LAZY) {
                    val data = load()
                    synchronized(lock) {
                        created.weight = data.versions.size
                        created.characters = data.versions.sumOf { it.length.toLong() } +
                            data.deprecatedByVersion.orEmpty().entries.sumOf { it.key.length.toLong() + it.value.length }
                        created.expires = now() + ttl
                        NpmMetadataLease(data, created.expires)
                    }
                }
                created = Entry(request)
                entries[key] = created
                request.invokeOnCompletion { failure -> synchronized(lock) {
                    if (entries[key] === created) {
                        if (failure != null) entries.remove(key)
                        else trim()
                    }
                } }
                created
            }
            selected.callers++
            selected
        }
        try {
            entry.request.start()
            return entry.request.await()
        } finally {
            synchronized(lock) {
                entry.callers--
                if (entry.callers == 0 && !entry.request.isCompleted) {
                    entries.entries.removeIf { it.value === entry }
                    entry.request.cancel()
                }
            }
        }
    }

    // Old workers can still serve their existing callers, but cannot populate or join a new generation.
    fun invalidate() = synchronized(lock) { generation++; entries.clear() }

    fun close() {
        synchronized(lock) { generation++; entries.clear(); worker.cancel() }
    }

    private fun trim() {
        var weight = entries.values.sumOf { it.weight.toLong() }
        var characters = entries.values.sumOf { it.characters }
        val iterator = entries.entries.iterator()
        while ((entries.size > capacity || weight > versionBudget || characters > characterBudget) && iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.request.isCompleted) continue
            weight -= entry.weight
            characters -= entry.characters
            iterator.remove()
        }
    }
}

@Service(Service.Level.PROJECT)
internal class NpmMetadataService(scope: CoroutineScope) : Disposable {
    val cache = NpmMetadataCache(scope)
    val runtimes = NpmScanSessions<NpmRuntime>(scope)
    fun invalidate() { runtimes.invalidate(); cache.invalidate() }
    override fun dispose() { cache.close(); runtimes.close() }
}
