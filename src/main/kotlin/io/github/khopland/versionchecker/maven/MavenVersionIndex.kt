package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.UpdateMode
import org.jetbrains.idea.maven.dom.MavenVersionComparable

/** Prepared once per shared history. Numeric branches and Maven ordering are distinct rules. */
internal class MavenVersionIndex(versions: List<String>) {
    private data class Version(val text: String, val comparable: MavenVersionComparable, val branch: Pair<Int, Int>?)
    private val ordered = versions.distinct().filter(MavenRepositoryMetadata::isStable)
        .map { Version(it, MavenVersionComparable(it), MavenVersionSemantics.numericPrefix(it)) }
        // Stable sorting preserves the original winner when Maven considers two spellings equal.
        .sortedWith { left, right -> right.comparable.compareTo(left.comparable) }
    private val majors = ordered.filter { it.branch != null }.groupBy { it.branch!!.first }
    private val branches = ordered.filter { it.branch != null }.groupBy { it.branch!! }

    private fun candidates(current: String, mode: UpdateMode): List<Version> = when (mode) {
        UpdateMode.MAJOR -> ordered
        UpdateMode.MINOR -> MavenVersionSemantics.numericPrefix(current)?.let { majors[it.first] }.orEmpty()
        UpdateMode.PATCH -> MavenVersionSemantics.numericPrefix(current)?.let { branches[it] }.orEmpty()
    }

    fun latest(current: String, mode: UpdateMode): String? {
        val candidate = candidates(current, mode).firstOrNull() ?: return null
        return candidate.text.takeIf { candidate.comparable > MavenVersionComparable(current) }
    }

    fun eligible(current: String, mode: UpdateMode): List<String> {
        val baseline = MavenVersionComparable(current)
        return candidates(current, mode).takeWhile { it.comparable > baseline }.map { it.text }
    }
}
