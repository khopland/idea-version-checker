package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

/** The version is part of the key: an edited or shared dependency must not receive a stale result. */
data class DependencyVersion(val groupId: String, val artifactId: String, val version: String,
                             val artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY)

object DependencyUpdateReport {
    // Pin the goal version and line width in MavenVersionLookup; also handle wrapped lines defensively.
    private val update = Regex("""^\s*(\S+):(\S+)\s+\.+\s+(\S+)\s+->\s+(\S+)\s*$""")
    private val wrappedCoordinate = Regex("""^\s*(\S+):(\S+)\s+\.{3}\s*$""")
    private val wrappedVersion = Regex("""^\s*(\S+)\s+->\s+(\S+)\s*$""")
    const val IGNORED_VERSIONS = "(?i).*[.-](alpha|beta|milestone|rc|cr|ea|preview|snapshot|m)[.-]?[0-9]*([.-].*)?"
    private val unstable = Regex(IGNORED_VERSIONS)

    fun parse(report: String): Map<DependencyVersion, String> = buildMap {
        var pending: Pair<String, String>? = null
        report.lineSequence().forEach { line ->
            val match = update.matchEntire(line)
            if (match != null) {
                val (group, artifact, current, latest) = match.destructured
                if (isFixedVersion(current) && !unstable.matches(latest) && current != latest) {
                    put(DependencyVersion(group, artifact, current), latest)
                }
                pending = null
            } else {
                val coordinate = wrappedCoordinate.matchEntire(line)
                val versions = wrappedVersion.matchEntire(line)
                val previous = pending
                if (previous != null && versions != null) {
                    val (current, latest) = versions.destructured
                    if (isFixedVersion(current) && !unstable.matches(latest) && current != latest) {
                        put(DependencyVersion(previous.first, previous.second, current), latest)
                    }
                }
                pending = coordinate?.let { it.groupValues[1] to it.groupValues[2] }
            }
        }
    }

    fun isFixedVersion(version: String): Boolean = version.isNotBlank() &&
        !version.contains('$') && !version.any { it in "[],()" } &&
        !version.equals("LATEST", true) && !version.equals("RELEASE", true) &&
        !version.endsWith("-SNAPSHOT", true)
}
