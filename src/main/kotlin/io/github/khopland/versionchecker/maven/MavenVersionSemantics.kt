package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.VersionChangeKind

import io.github.khopland.versionchecker.*

/** Maven's numeric-prefix update modes must not become the version rules for other ecosystems. */
internal object MavenVersionSemantics {
    fun allows(mode: UpdateMode, current: String, latest: String): Boolean {
        if (mode == UpdateMode.MAJOR) return true
        val old = numericPrefix(current) ?: return false
        val new = numericPrefix(latest) ?: return false
        return old.first == new.first && (mode == UpdateMode.MINOR || old.second == new.second)
    }

    private fun numericPrefix(version: String): Pair<Int, Int>? {
        val match = Regex("""^(\d+)(?:\.(\d+))?(?:\.|-|$).*""").matchEntire(version) ?: return null
        return (match.groupValues[1].toIntOrNull() ?: return null) to
            (match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0)
    }

    fun between(current: String, latest: String): VersionChangeKind {
        fun numbers(version: String): List<Int>? =
            Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-.].*)?$""").matchEntire(version)
                ?.groupValues?.drop(1)?.map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
        val old = numbers(current) ?: return VersionChangeKind.OTHER
        val new = numbers(latest) ?: return VersionChangeKind.OTHER
        return when {
            old[0] != new[0] -> VersionChangeKind.MAJOR
            old[1] != new[1] -> VersionChangeKind.MINOR
            else -> VersionChangeKind.PATCH
        }
    }
}
