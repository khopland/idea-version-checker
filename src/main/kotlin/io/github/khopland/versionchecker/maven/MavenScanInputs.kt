package io.github.khopland.versionchecker.maven

/** Copies one source snapshot and config read for reuse across goals and retrieval fallbacks. */
internal class MavenScanInputs(
    declarations: List<DependencyVersion>,
    kinds: Set<MavenArtifactKind>,
    properties: Map<String, String>
) {
    private val coordinates = declarations.filter { it.artifactKind in kinds && it.version.isNotBlank() }
        .distinct().groupBy { it.artifactKind }
    val kinds: Set<MavenArtifactKind> = kinds.filterTo(linkedSetOf()) { coordinates[it].orEmpty().isNotEmpty() }
    val properties: Map<String, String> = properties.toMap()
    operator fun get(kind: MavenArtifactKind): List<DependencyVersion> = coordinates[kind].orEmpty()
}
