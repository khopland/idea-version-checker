package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.service
import com.intellij.openapi.application.readAction
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.CheckQueue
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import java.util.ArrayDeque
import org.jetbrains.idea.maven.dom.MavenVersionComparable
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache

internal data class MavenLookupDelta(val coordinates: Set<DependencyVersion>, val updates: Map<DependencyVersion, String>,
                                     val failures: List<Exception>, val validUntil: Long?, val checkedAtNanos: Long? = null)

/** Explicit coordinates are resolved once per context; every baseline/mode is selected locally. */
internal object MavenBatchedLookup {
    suspend fun check(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot, mode: UpdateMode,
                      publish: (suspend (MavenLookupDelta) -> Unit)?): MavenLookupDelta? = withMavenScanSession {
        checkInSession(manager, project, snapshot, mode, publish)
    }

    private suspend fun checkInSession(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot,
                                       mode: UpdateMode, publish: (suspend (MavenLookupDelta) -> Unit)?): MavenLookupDelta? {
        val coordinates = snapshot.declarations.map { it.coordinate() }.distinct()
        if (coordinates.isEmpty()) return MavenLookupDelta(emptySet(), emptyMap(), emptyList(), null)
        val service = manager.project.service<MavenMetadataService>()
        val generation = service.cache.generation()
        val context = service.contexts.get(manager, project, snapshot, generation)
        if (!context.nativeSupported) return null
        val options = manager.project.service<VersionCheckerSettings>().state.copy()
        val keys = coordinates.groupBy { MavenMetadataKey(it.groupId, it.artifactId, it.artifactKind == MavenArtifactKind.PLUGIN) }
        val platformScope = MavenPlatformScope.isPlatform(snapshot)
        val ownedPlatforms = if (platformScope) readAction { MavenPlatformScope.platformCoordinates(manager, project, snapshot) } else emptySet()
        val platform = if (options.mavenPlatformFirst || platformScope) keys.filterValues { declarations -> declarations.any {
            it.artifactKind == MavenArtifactKind.PARENT || it.groupId == "org.springframework.boot" &&
                it.artifactId in setOf("spring-boot-dependencies", "spring-boot-starter-parent") || it in ownedPlatforms
        } }.keys else emptySet()
        // Publish dependencies before spending time on plugin candidate POMs. Optional platform
        // priority gets its own batch so even a small POM can display that answer earlier.
        val ordinary = keys.keys.filter { !it.plugin && it !in platform } + keys.keys.filter { it.plugin && it !in platform }
        val first = if (platform.isEmpty()) ordinary.take(16).takeWhile { it.plugin == ordinary.first().plugin } else emptyList()
        val remaining = ordinary.drop(first.size)
        val batches = platform.toList().chunked(128) + listOf(first).filter { it.isNotEmpty() } +
            remaining.filter { !it.plugin }.chunked(128) + remaining.filter { it.plugin }.chunked(128)
        val updates = linkedMapOf<DependencyVersion, String>()
        val failures = mutableListOf<Exception>()
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
                MavenNativeMetadata.load(manager, project, context, missing, options.mavenMetadataThreads)
            }
        }
        val mavenVersion = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file).version
        for (batch in batches) {
            currentCoroutineContext().ensureActive()
            CheckQueue.yieldCurrent()
            val result = load(batch)
            val deltaUpdates = linkedMapOf<DependencyVersion, String>()
            val deltaFailures = mutableListOf<Exception>()
            var deltaExpires: Long? = null
            var deltaCheckedAt: Long? = null
            fun observed(lease: MavenMetadataLease) {
                deltaExpires = minOf(deltaExpires ?: Long.MAX_VALUE, lease.expiresAt)
                deltaCheckedAt = minOf(deltaCheckedAt ?: Long.MAX_VALUE, lease.checkedAtNanos)
            }
            val plugins = linkedMapOf<DependencyVersion, ArrayDeque<String>>()
            result.forEach { (key, value) ->
                value.fold({ lease ->
                    observed(lease)
                    for (coordinate in keys.getValue(key)) {
                        if (!key.plugin) lease.value.versionIndex.latest(coordinate.version, mode)
                            ?.let { deltaUpdates[coordinate] = it }
                        else plugins[coordinate] = ArrayDeque(lease.value.versionIndex.eligible(coordinate.version, mode))
                    }
                }, { failure -> deltaFailures += failure as? Exception ?: Exception(failure) })
            }
            // Validate only promising plugin POMs. Descend when a candidate requires newer Maven.
            while (plugins.values.any { it.isNotEmpty() }) {
                val candidates = plugins.filterValues { it.isNotEmpty() }.map { (coordinate, versions) ->
                    MavenMetadataKey(coordinate.groupId, coordinate.artifactId, true, versions.first())
                }.distinct()
                CheckQueue.yieldCurrent()
                val prerequisites = load(candidates)
                val iterator = plugins.iterator()
                while (iterator.hasNext()) {
                    val (coordinate, versions) = iterator.next()
                    if (versions.isEmpty()) { iterator.remove(); continue }
                    val candidate = versions.removeFirst()
                    val key = MavenMetadataKey(coordinate.groupId, coordinate.artifactId, true, candidate)
                    prerequisites.getValue(key).fold({ lease ->
                        observed(lease)
                        val required = lease.value.requiredMaven
                        if (required.isEmpty() || mavenVersion != null && MavenVersionComparable(required) <= MavenVersionComparable(mavenVersion)) {
                            deltaUpdates[coordinate] = candidate
                            iterator.remove()
                        }
                    }, { failure ->
                        deltaFailures += failure as? Exception ?: Exception(failure)
                        iterator.remove()
                    })
                }
            }
            val delta = MavenLookupDelta(batch.flatMap { keys.getValue(it) }.toSet(), deltaUpdates, deltaFailures, deltaExpires, deltaCheckedAt)
            updates.putAll(deltaUpdates); failures += deltaFailures
            deltaExpires?.let { expires = minOf(expires ?: Long.MAX_VALUE, it) }
            deltaCheckedAt?.let { checkedAt = minOf(checkedAt ?: Long.MAX_VALUE, it) }
            publish?.invoke(delta)
        }
        return MavenLookupDelta(coordinates.toSet(), updates, failures, expires, checkedAt)
    }

    internal fun eligible(versions: List<String>, current: String, mode: UpdateMode): List<String> =
        MavenVersionIndex(versions).eligible(current, mode)
}
