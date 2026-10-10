package io.github.khopland.versionchecker.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class MavenMetadataKey(val group: String, val artifact: String, val plugin: Boolean = false, val version: String = "")
internal data class MavenMetadataValue(val versions: List<String> = emptyList(), val requiredMaven: String = "") {
    val versionIndex by lazy { CheckPerformance.measure(CheckPerformance.Stage.MAVEN_VERSION_INDEX, versions.size) { MavenVersionIndex(versions) } }
}
internal data class MavenMetadataLease(val value: MavenMetadataValue, val expiresAt: Long, val checkedAtNanos: Long = System.nanoTime())

/** Batched, generation-isolated native workers. Baselines and update modes are deliberately absent. */
internal class MavenMetadataCache(
    scope: CoroutineScope,
    private val now: () -> Long = System::nanoTime,
    private val ttl: Long = TimeUnit.MINUTES.toNanos(10),
    private val capacity: Int = 2048,
    private val versionBudget: Int = 100_000,
    private val characterBudget: Int = 8_000_000,
    private val persistence: MavenMetadataStore? = null,
    private val persistEnabled: () -> Boolean = { false }
) {
    private data class Key(val context: String, val artifact: MavenMetadataKey, val generation: Long)
    private class Batch { lateinit var job: Job; val entries = mutableListOf<Entry>() }
    private class Entry(val batch: Batch) {
        val result = CompletableDeferred<MavenMetadataLease>()
        var callers = 0
        var expires = Long.MAX_VALUE
        var weight = 0L
        var characters = 0L
    }
    private val lock = Any()
    private val worker = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + worker + Dispatchers.IO)
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var generation = 0L
    fun generation(): Long = synchronized(lock) { generation }

    suspend fun getMany(context: String, keys: List<MavenMetadataKey>, generation: Long = generation(),
                        onWorkerCreated: (Job) -> Unit = {},
                        load: suspend (List<MavenMetadataKey>) -> Map<MavenMetadataKey, Result<MavenMetadataValue>>): Map<MavenMetadataKey, Result<MavenMetadataLease>> {
        currentCoroutineContext().ensureActive()
        val selected = synchronized(lock) {
            check(worker.isActive) { "Maven metadata cache is disposed" }
            entries.entries.removeIf { it.value.result.isCompleted && it.value.expires <= now() }
            val batch = Batch()
            val missing = linkedMapOf<Key, Entry>()
            val selected = keys.distinct().associateWith { artifact ->
                val key = Key(context, artifact, generation)
                // Older callers may finish, but cannot populate the current generation.
                val entry = entries[key] ?: Entry(batch).also {
                    missing[key] = it; batch.entries += it
                    if (generation == this.generation) entries[key] = it
                }
                entry.callers++
                entry
            }
            CheckPerformance.record(CheckPerformance.Stage.MAVEN_METADATA_REUSED, System.nanoTime(), selected.size - missing.size)
            if (missing.isNotEmpty()) {
                batch.job = workers.launch(CheckPerformance.context(), start = CoroutineStart.LAZY) {
                    try {
                        val requests = missing.keys.map { it.artifact }
                        val diskRevision = synchronized(lock) {
                            if (persistEnabled() && generation == this@MavenMetadataCache.generation) persistence?.revision() else null
                        }
                        val persisted = diskRevision?.let { persistence!!.read(context, requests, it) }.orEmpty()
                        val fresh = requests.filter { it !in persisted }
                        val results = if (fresh.isEmpty()) emptyMap() else load(fresh)
                        currentCoroutineContext().ensureActive()
                        val checkedAt = now()
                        val expires = checkedAt + ttl
                        val successful = results.mapNotNull { (key, result) -> result.getOrNull()?.let {
                            key to MavenMetadataLease(it, expires, checkedAt)
                        } }.toMap()
                        if (diskRevision != null && successful.isNotEmpty()) persistence!!.write(context, successful, diskRevision)
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            missing.forEach { (key, entry) ->
                                val result = persisted[key.artifact]?.let { Result.success(it) } ?:
                                    successful[key.artifact]?.let { Result.success(it) } ?:
                                    Result.failure(results[key.artifact]?.exceptionOrNull() ?: IOException("Missing Maven metadata response"))
                                result.fold({ lease ->
                                    val value = lease.value
                                    entry.weight = value.versions.size.toLong()
                                    entry.characters = value.versions.sumOf { it.length.toLong() } + value.requiredMaven.length
                                    entry.expires = lease.expiresAt
                                    entry.result.complete(lease)
                                }, { failure ->
                                    if (entries[key] === entry) entries.remove(key)
                                    entry.result.completeExceptionally(failure)
                                })
                            }
                            trim()
                        }
                    } catch (failure: Throwable) {
                        synchronized(lock) {
                            missing.forEach { (key, entry) ->
                                if (entries[key] === entry) entries.remove(key)
                                entry.result.completeExceptionally(failure)
                            }
                        }
                        if (failure is CancellationException) throw failure
                    }
                }
                // A lazy coroutine cancelled before start never enters its try/catch.
                // Still wake every consumer when disposal wins that race.
                batch.job.invokeOnCompletion { failure ->
                    if (failure != null) synchronized(lock) {
                        missing.forEach { (key, entry) ->
                            if (!entry.result.isCompleted) {
                                if (entries[key] === entry) entries.remove(key)
                                entry.result.completeExceptionally(failure)
                            }
                        }
                    }
                }
                onWorkerCreated(batch.job)
            }
            selected
        }
        try {
            selected.values.map { it.batch }.distinct().forEach { it.job.start() }
            return CheckPerformance.measure(CheckPerformance.Stage.MAVEN_METADATA_WAIT, selected.size) { selected.mapValues { (_, entry) ->
                try { Result.success(entry.result.await()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cancelled: ProcessCanceledException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            } }
        } finally {
            synchronized(lock) {
                selected.values.forEach { it.callers-- }
                selected.values.map { it.batch }.distinct().forEach { batch ->
                    if (batch.entries.all { it.callers == 0 } && !batch.job.isCompleted) {
                        entries.entries.removeIf { it.value.batch === batch && !it.value.result.isCompleted }
                        batch.job.cancel()
                    }
                }
            }
        }
    }

    fun invalidate() { synchronized(lock) {
        generation++; entries.clear(); persistence?.invalidate()
        if (persistence != null) workers.launch { persistence.clearInvalidated() }
    } }
    fun close() = synchronized(lock) { generation++; entries.clear(); persistence?.close(); worker.cancel() }
    private fun trim() {
        var weight = entries.values.sumOf { it.weight }
        var characters = entries.values.sumOf { it.characters }
        val iterator = entries.entries.iterator()
        while ((entries.size > capacity || weight > versionBudget || characters > characterBudget) && iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.result.isCompleted) continue
            weight -= entry.weight; characters -= entry.characters; iterator.remove()
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MavenMetadataService(project: Project, scope: CoroutineScope) : Disposable {
    private val persistence = MavenMetadataStore({ PathManager.getSystemDir().resolve("version-checker/maven-metadata")
        .resolve(project.locationHash).resolve("histories.bin") })
    val cache = MavenMetadataCache(scope, persistence = persistence,
        persistEnabled = { project.service<VersionCheckerSettings>().state.mavenPersistentMetadata })
    val contexts = MavenResolutionContexts()
    fun invalidate() { cache.invalidate(); contexts.clear() }
    override fun dispose() { cache.close(); contexts.clear() }
}
