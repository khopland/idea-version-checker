package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import org.jetbrains.idea.maven.dom.MavenVersionComparable

/** The plugin report omits Apache plugin groupIds and includes older/Maven-incompatible alternatives. */
internal object MavenPluginUpdates {
    private val update = Regex("""^\s*(\S+)\s+\.+\s+(\S+)\s+->\s+(\S+)\s*$""")
    private val prerequisite = Regex("""^Require Maven (\S+) to use the following plugin updates:$""")
    private val unstable = Regex(DependencyUpdateReport.IGNORED_VERSIONS)

    fun branch(version: String, mode: UpdateMode): String? {
        if (mode == UpdateMode.MAJOR) return ""
        val match = Regex("""^(\d+)(?:\.(\d+))?(?:\.|-|$).*""").matchEntire(version) ?: return null
        return if (mode == UpdateMode.MINOR) match.groupValues[1]
        else "${match.groupValues[1]}.${match.groupValues[2].ifEmpty { "0" }}"
    }

    fun ignoredVersions(branch: String): String = DependencyUpdateReport.IGNORED_VERSIONS +
        if (branch.isEmpty()) "" else "|(?!${Regex.escape(branch)}(?:[.-]|$)).*"

    fun parse(report: String, plugins: Collection<DependencyVersion>, mode: UpdateMode, mavenVersion: String?): Map<DependencyVersion, String> = buildMap {
        var compatible = false
        for (line in report.lineSequence()) {
            val text = line.trim()
            if (text == "The following plugin updates are available:") compatible = true
            val required = prerequisite.matchEntire(text)?.groupValues?.get(1)
            if (required != null) compatible = mavenVersion != null &&
                MavenVersionComparable(required) <= MavenVersionComparable(mavenVersion)
            if (!compatible) continue
            val match = update.matchEntire(line) ?: continue
            val (name, current, latest) = match.destructured
            if (!DependencyUpdateReport.isFixedVersion(current) || unstable.matches(latest) ||
                MavenVersionComparable(latest) <= MavenVersionComparable(current) || !MavenVersionSemantics.allows(mode, current, latest)) continue
            val group = if (':' in name) name.substringBefore(':') else "org.apache.maven.plugins"
            val artifact = name.substringAfter(':', name)
            val coordinate = DependencyVersion(group, artifact, current, MavenArtifactKind.PLUGIN)
            if (coordinate !in plugins) continue
            val previous = get(coordinate)
            if (previous == null || MavenVersionComparable(latest) > MavenVersionComparable(previous)) put(coordinate, latest)
        }
    }
}
