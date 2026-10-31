package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.service
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.CheckQueue
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache

internal data class MavenLookupDelta(val coordinates: Set<DependencyVersion>, val updates: Map<DependencyVersion, String>,
                                     val failures: List<Exception>, val validUntil: Long?, val checkedAtNanos: Long? = null,
                                     val failedCoordinates: Set<DependencyVersion> = emptySet())

/** Explicit coordinates are resolved once per context; every baseline/mode is selected locally. */
internal object MavenBatchedLookup {
    suspend fun check(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot, mode: UpdateMode,
                      publish: (suspend (MavenLookupDelta) -> Unit)?): MavenLookupDelta? = withMavenScanSession {
        checkInSession(manager, project, snapshot, mode, publish)
    }

    private suspend fun checkInSession(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot,
                                       mode: UpdateMode, publish: (suspend (MavenLookupDelta) -> Unit)?): MavenLookupDelta? = coroutineScope {
        val coordinates = snapshot.declarations.map { it.coordinate() }.distinct()
        if (coordinates.isEmpty()) return@coroutineScope MavenLookupDelta(emptySet(), emptyMap(), emptyList(), null)
        val service = manager.project.service<MavenMetadataService>()
        val generation = service.cache.generation()
        val context = service.contexts.get(manager, project, snapshot, generation)
        if (!context.nativeSupported) return@coroutineScope null
        val options = manager.project.service<VersionCheckerSettings>().state.copy()
        val age = MavenReleaseAge.of(options.mavenMinimumReleaseAgeHours)
        val keys = coordinates.groupBy { MavenMetadataKey(it.groupId, it.artifactId, it.artifactKind == MavenArtifactKind.PLUGIN) }
        // Publish dependencies before spending time on plugin candidate POMs; a small first batch
        // lets even a large POM display answers early.
        val ordinary = keys.keys.filter { !it.plugin } + keys.keys.filter { it.plugin }
        val first = ordinary.take(16).takeWhile { it.plugin == ordinary.first().plugin }
        val remaining = ordinary.drop(first.size)
        val batches = listOf(first) + remaining.filter { !it.plugin }.chunked(128) + remaining.filter { it.plugin }.chunked(128)
        val updates = linkedMapOf<DependencyVersion, String>()
        val failures = mutableListOf<Exception>()
        val failedCoordinates = linkedSetOf<DependencyVersion>()
        var expires: Long? = null
        var checkedAt: Long? = null
        val session = currentCoroutineContext()[MavenScanSession]
        CheckPerformance.record(CheckPerformance.Stage.MAVEN_QUERY_COORDINATES, System.nanoTime(), keys.size)
        CheckPerformance.record(CheckPerformance.Stage.MAVEN_QUERY_CONTEXTS, System.nanoTime())
        suspend fun load(requests: List<MavenMetadataKey>) = service.cache.getMany(context.id, requests, generation, onWorkerCreated = { job ->
            session?.retain()
            job.invokeOnCompletion { session?.release() }
        }) { missing ->
            withContext(session ?: EmptyCoroutineContext) {
                MavenNativeMetadata.load(manager, project, context, missing)
            }
        }
        val mavenVersion = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file).version
        // Keep two batches in flight, one per Maven process slot, so goal startup overlaps selection and publishing.
        val pending = ArrayDeque(batches.take(2).map { batch -> async { load(batch) } })
        for ((index, batch) in batches.withIndex()) {
            currentCoroutineContext().ensureActive()
            CheckQueue.yieldCurrent()
            val result = pending.removeFirst().await()
            batches.getOrNull(index + 2)?.let { next -> pending.addLast(async { load(next) }) }
            val deltaUpdates = linkedMapOf<DependencyVersion, String>()
            val deltaFailures = mutableListOf<Exception>()
            val deltaFailedCoordinates = linkedSetOf<DependencyVersion>()
            var deltaExpires: Long? = null
            var deltaCheckedAt: Long? = null
            fun observed(lease: MavenMetadataLease) {
                deltaExpires = minOf(deltaExpires ?: Long.MAX_VALUE, lease.expiresAt)
                deltaCheckedAt = minOf(deltaCheckedAt ?: Long.MAX_VALUE, lease.checkedAtNanos)
            }
            val candidates = linkedMapOf<DependencyVersion, List<String>>()
            val dated = hashSetOf<DependencyVersion>()
            result.forEach { (key, value) ->
                value.fold({ lease ->
                    observed(lease)
                    val checkDates = age != null && !age.settled(lease.value)
                    for (coordinate in keys.getValue(key)) {
                        if (!key.plugin && !checkDates) lease.value.versionIndex.latest(coordinate.version, mode)
                            ?.let { deltaUpdates[coordinate] = it }
                        else {
                            candidates[coordinate] = lease.value.versionIndex.eligible(coordinate.version, mode)
                            if (checkDates) dated += coordinate
                        }
                    }
                }, { failure ->
                    deltaFailures += failure as? Exception ?: Exception(failure)
                    deltaFailedCoordinates += keys.getValue(key)
                })
            }
            // Validate only promising candidates: release dates when recent, and plugin POM prerequisites.
            val selection = MavenCandidateWalk.select(candidates, age, dated, mavenVersion) { requests ->
                CheckQueue.yieldCurrent()
                load(requests).mapValues { (_, result) -> result.map { lease -> observed(lease); lease.value } }
            }
            deltaUpdates.putAll(selection.selected)
            selection.failures.forEach { (coordinate, failure) ->
                deltaFailures += failure
                deltaFailedCoordinates += coordinate
            }
            val delta = MavenLookupDelta(batch.flatMap { keys.getValue(it) }.toSet(), deltaUpdates, deltaFailures,
                deltaExpires, deltaCheckedAt, deltaFailedCoordinates)
            updates.putAll(deltaUpdates); failures += deltaFailures
            failedCoordinates += deltaFailedCoordinates
            deltaExpires?.let { expires = minOf(expires ?: Long.MAX_VALUE, it) }
            deltaCheckedAt?.let { checkedAt = minOf(checkedAt ?: Long.MAX_VALUE, it) }
            publish?.invoke(delta)
        }
        MavenLookupDelta(coordinates.toSet(), updates, failures, expires, checkedAt, failedCoordinates)
    }

    internal fun eligible(versions: List<String>, current: String, mode: UpdateMode): List<String> =
        MavenVersionIndex(versions).eligible(current, mode)
}
