package io.github.khopland.versionchecker.maven

import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.MavenVerificationTarget
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.xml.XmlTag

/** The consumer context is the imported POM, which can differ from the edited declaration's owner. */
internal data class MavenVerificationConsumer(val pomPath: String, val coordinate: DependencyVersion)

/** Bind expected dependency versions to the resolution generation immediately before the edit. */
internal fun captureMavenVerificationTargets(project: Project, consumers: List<MavenVerificationConsumer>,
                                             latest: String): List<MavenVerificationTarget> {
    val dependencies = consumers.filter { it.coordinate.artifactKind == MavenArtifactKind.DEPENDENCY }.distinct()
    if (dependencies.isEmpty()) return emptyList()
    val generation = project.getService(MavenUpdateVerificationService::class.java).generation()
    return dependencies.map { (pomPath, coordinate) ->
        MavenVerificationTarget(pomPath, coordinate.groupId, coordinate.artifactId, coordinate.artifactKind.name,
            latest, generation)
    }.distinct()
}

/** Provider eligibility and captured physical edit; leases and fresh inputs still authorize application. */
internal data class MavenPreparedUpdate(
    val affectedCoordinates: Set<DependencyVersion>,
    val replacement: String?,
    val blockingReasons: List<String>,
    val edit: MavenVersionEdit?
) {
    internal data class Consumer(val coordinate: DependencyVersion, val replacement: String?, val notice: String? = null)

    companion object {
        fun prepare(target: XmlTag, consumers: List<Consumer>, blockingReasons: List<String> = emptyList(),
                    verificationConsumers: List<MavenVerificationConsumer> = emptyList()): MavenPreparedUpdate {
            val versions = consumers.map { it.replacement }.distinct()
            val latest = versions.singleOrNull()
            val blockers = blockingReasons + consumers.mapNotNull { it.notice } + when {
                consumers.isEmpty() || versions.any { it == null } -> listOf("${target.localName}: shared consumers need review")
                versions.size != 1 -> listOf("${target.localName}: artifacts using this property require different updates")
                else -> emptyList()
            }
            val coordinates = consumers.mapTo(linkedSetOf()) { it.coordinate }
            val edit = if (blockers.isNotEmpty() || latest == null) null else capture(target, latest,
                "${target.containingFile.virtualFile.path}: ${target.localName} (" +
                    coordinates.joinToString { "${it.groupId}:${it.artifactId}" } + ")", verificationConsumers)
            return MavenPreparedUpdate(coordinates, latest, blockers.distinct(), edit)
        }

        fun capture(target: XmlTag, latest: String, location: String = target.containingFile.name,
                    verificationConsumers: List<MavenVerificationConsumer> = emptyList()): MavenVersionEdit =
            MavenVersionEdit(SmartPointerManager.createPointer(target), target.value.trimmedText, latest, location,
                verificationConsumers.toList())
    }
}
