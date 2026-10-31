package io.github.khopland.versionchecker.maven

import com.intellij.psi.SmartPointerManager
import com.intellij.psi.xml.XmlTag

/** Provider eligibility and captured physical edit; leases and fresh inputs still authorize application. */
internal data class MavenPreparedUpdate(
    val affectedCoordinates: Set<DependencyVersion>,
    val replacement: String?,
    val blockingReasons: List<String>,
    val edit: MavenVersionEdit?
) {
    internal data class Consumer(val coordinate: DependencyVersion, val replacement: String?, val notice: String? = null)

    companion object {
        fun prepare(target: XmlTag, consumers: List<Consumer>, blockingReasons: List<String> = emptyList()): MavenPreparedUpdate {
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
                    coordinates.joinToString { "${it.groupId}:${it.artifactId}" } + ")")
            return MavenPreparedUpdate(coordinates, latest, blockers.distinct(), edit)
        }

        fun capture(target: XmlTag, latest: String, location: String = target.containingFile.name): MavenVersionEdit =
            MavenVersionEdit(SmartPointerManager.createPointer(target), target.value.trimmedText, latest, location)
    }
}
