package io.github.khopland.versionchecker.maven

import org.jetbrains.idea.maven.dom.MavenVersionComparable
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Withholds versions published less than a minimum age ago, for example to match a repository
 * firewall quarantine. Metadata last updated before the cutoff proves every listed version old
 * enough; otherwise candidate POM dates are read, newest first.
 */
internal class MavenReleaseAge(minimumAgeHours: Int, now: Long = System.currentTimeMillis()) {
    val cutoff = now - TimeUnit.HOURS.toMillis(minimumAgeHours.toLong())
    fun settled(history: MavenMetadataValue) = history.lastUpdated?.let { it <= cutoff } == true
    fun oldEnough(published: Long) = published <= cutoff

    companion object {
        fun of(minimumAgeHours: Int): MavenReleaseAge? = if (minimumAgeHours > 0) MavenReleaseAge(minimumAgeHours) else null
    }
}

internal data class MavenCandidateSelection(val selected: Map<DependencyVersion, String>,
                                            val failures: Map<DependencyVersion, Exception>)

/** Picks the newest acceptable candidate per coordinate: old enough when dated, and runnable when a plugin. */
internal object MavenCandidateWalk {
    private const val MAX_LOOKAHEAD = 8
    private const val MAX_REQUESTS = 256

    /**
     * [candidates] are ordered newest first. Each round batches one lookup per unresolved coordinate;
     * release dates are read ahead in growing windows so a run of recent releases needs few rounds.
     */
    suspend fun select(candidates: Map<DependencyVersion, List<String>>, age: MavenReleaseAge?, dated: Set<DependencyVersion>,
                       mavenVersion: String?,
                       load: suspend (List<MavenMetadataKey>) -> Map<MavenMetadataKey, Result<MavenMetadataValue>>): MavenCandidateSelection {
        val pending = linkedMapOf<DependencyVersion, ArrayDeque<String>>()
        candidates.forEach { (coordinate, versions) -> if (versions.isNotEmpty()) pending[coordinate] = ArrayDeque(versions) }
        val facts = hashMapOf<MavenMetadataKey, Result<MavenMetadataValue>>()
        val selected = linkedMapOf<DependencyVersion, String>()
        val failures = linkedMapOf<DependencyVersion, Exception>()
        var lookahead = 1
        while (pending.isNotEmpty()) {
            val requests = linkedSetOf<MavenMetadataKey>()
            val iterator = pending.iterator()
            coordinates@ while (iterator.hasNext()) {
                val (coordinate, versions) = iterator.next()
                val plugin = coordinate.artifactKind == MavenArtifactKind.PLUGIN
                while (true) {
                    val candidate = versions.firstOrNull()
                    if (candidate == null) { iterator.remove(); continue@coordinates }
                    val prerequisite = MavenMetadataKey(coordinate.groupId, coordinate.artifactId, true, candidate)
                    if (age != null && coordinate in dated) {
                        val release = facts[releaseKey(coordinate, candidate)]
                        if (release == null) {
                            versions.take(lookahead).map { releaseKey(coordinate, it) }.filterTo(requests) { it !in facts }
                            if (plugin && prerequisite !in facts) requests += prerequisite
                            continue@coordinates
                        }
                        val failure = release.exceptionOrNull()
                        if (failure != null) {
                            failures[coordinate] = failure as? Exception ?: IOException(failure)
                            iterator.remove(); continue@coordinates
                        }
                        val published = release.getOrThrow().published
                        if (published == null || !age.oldEnough(published)) { versions.removeFirst(); continue }
                    }
                    if (plugin) {
                        val result = facts[prerequisite]
                        if (result == null) { requests += prerequisite; continue@coordinates }
                        val failure = result.exceptionOrNull()
                        if (failure != null) {
                            failures[coordinate] = failure as? Exception ?: IOException(failure)
                            iterator.remove(); continue@coordinates
                        }
                        val required = result.getOrThrow().requiredMaven
                        // Descend when a candidate requires newer Maven.
                        if (required.isNotEmpty() && (mavenVersion == null ||
                                MavenVersionComparable(required) > MavenVersionComparable(mavenVersion))) {
                            versions.removeFirst(); continue
                        }
                    }
                    selected[coordinate] = candidate
                    iterator.remove(); continue@coordinates
                }
            }
            if (requests.isEmpty()) break
            for (chunk in requests.toList().chunked(MAX_REQUESTS)) {
                val loaded = load(chunk)
                chunk.forEach { key -> facts[key] = loaded[key] ?: Result.failure(IOException("Missing Maven metadata response")) }
            }
            lookahead = minOf(MAX_LOOKAHEAD, lookahead * 2)
        }
        return MavenCandidateSelection(selected, failures)
    }

    private fun releaseKey(coordinate: DependencyVersion, version: String) = MavenMetadataKey(coordinate.groupId,
        coordinate.artifactId, coordinate.artifactKind == MavenArtifactKind.PLUGIN, version, release = true)
}
