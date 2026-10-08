package io.github.khopland.versionchecker.maven

import java.util.Properties

/** Only exact coordinates can safely be encoded in the Versions Plugin's pattern syntax. */
internal object MavenDependencyFilters {
    private val component = Regex("[A-Za-z0-9_.-]+")

    fun includes(dependencies: List<DependencyVersion>): String? {
        if (dependencies.isEmpty() || dependencies.any {
                !component.matches(it.groupId) || !component.matches(it.artifactId)
            }) return null
        return dependencies.map { "${it.groupId}:${it.artifactId}" }.distinct().sorted().joinToString(",")
    }

    fun apply(properties: Properties, dependencies: List<DependencyVersion>?, excluded: Set<String>) {
        dependencies?.let(::includes)?.let {
            properties.setProperty("dependencyIncludes", it)
            properties.setProperty("dependencyManagementIncludes", it)
        }
        if (excluded.isNotEmpty()) {
            properties.setProperty("dependencyExcludes", excluded.joinToString(","))
            properties.setProperty("dependencyManagementExcludes", excluded.joinToString(","))
        }
    }
}
