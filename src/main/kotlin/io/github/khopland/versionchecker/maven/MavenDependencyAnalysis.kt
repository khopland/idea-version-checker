package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

internal data class DependencyProblem(
    val coordinate: DependencyVersion, val latest: String?, val anchor: XmlTag, val target: XmlTag?,
    val kind: DependencyChangeKind,
    val severity: DependencySeverity,
    val notice: String? = null
) {
    val message: String get() = notice ?: "Newer version of ${if (coordinate.artifactKind == MavenArtifactKind.PLUGIN) "Maven plugin " else ""}${coordinate.groupId}:${coordinate.artifactId} is available: ${coordinate.version} → $latest"
}

internal class MavenDependencyAnalysis(
    private val model: MavenDomProjectModel,
    private val mavenProject: MavenProject,
    private val updates: Map<DependencyVersion, String>,
    private val options: VersionCheckerSettings.Options = VersionCheckerSettings.Options(),
    private val relocations: Map<DependencyVersion, String> = emptyMap()
) {
    private val deprecated = deprecatedDependencies(options.deprecatedDependencies)

    fun coordinate(tag: XmlTag): DependencyVersion? {
        val artifactKind = when {
            isProjectDependency(tag) -> MavenArtifactKind.DEPENDENCY
            isProjectPlugin(tag) -> MavenArtifactKind.PLUGIN
            else -> return null
        }
        var ancestor = tag.parentTag
        while (ancestor != null) {
            if (ancestor.localName == "profile" && ancestor.findFirstSubTag("id")?.value?.trimmedText !in
                mavenProject.activatedProfilesIds.enabledProfiles) return null
            ancestor = ancestor.parentTag
        }
        val group = tag.findFirstSubTag("groupId")?.value?.trimmedText
            ?: if (artifactKind == MavenArtifactKind.PLUGIN) "org.apache.maven.plugins" else return null
        val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText ?: return null
        val versionTag = tag.findFirstSubTag("version")
        val rawVersion = versionTag?.value?.trimmedText
        val resolvedGroup = MavenPropertyResolver.resolve(group, model)
        val resolvedArtifact = MavenPropertyResolver.resolve(artifact, model)
        val current = rawVersion?.let { MavenPropertyResolver.resolve(it, model) }
            ?: if (artifactKind == MavenArtifactKind.DEPENDENCY)
                mavenProject.findManagedDependencyVersion(resolvedGroup, resolvedArtifact)
            else mavenProject.findPlugin(resolvedGroup, resolvedArtifact)?.version
        if (current == null) return null
        if (!DependencyUpdateReport.isFixedVersion(current)) return null
        return DependencyVersion(resolvedGroup, resolvedArtifact, current, artifactKind)
    }

    fun problem(tag: XmlTag): DependencyProblem? {
        val coordinate = coordinate(tag) ?: return null
        val latest = updates[coordinate]
        val id = "${coordinate.groupId}:${coordinate.artifactId}"
        val label = if (coordinate.artifactKind == MavenArtifactKind.PLUGIN) "Maven plugin" else "Dependency"
        val notice = relocations[coordinate]?.let { "$label $id:${coordinate.version} has been relocated to $it" }
            ?: deprecated[id]?.let { "$label $id is marked deprecated: $it" }
        if (latest == null && notice == null) return null
        val kind = if (notice != null) DependencyChangeKind.DEPRECATED else MavenVersionSemantics.between(coordinate.version, latest!!)
        val versionTag = tag.findFirstSubTag("version")
        val rawVersion = versionTag?.value?.trimmedText
        val current = coordinate.version
        val target = when {
            notice != null -> null // Replacements need a coordinate/API review, not a version-only edit.
            versionTag == null -> null
            rawVersion == current -> versionTag
            else -> findLocalVersionProperty(versionTag, rawVersion, current)
        }
        return DependencyProblem(coordinate, latest, versionTag ?: tag.findFirstSubTag("artifactId") ?: tag,
            target, kind, kind.severity(options), notice)
    }

    fun problems(file: PsiFile): List<DependencyProblem> =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(::problem)

    companion object {
        fun forFile(file: PsiFile): MavenDependencyAnalysis? {
            if (!file.project.service<VersionCheckerSettings>().state.enabled) return null
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return null
            val mavenProject = MavenProjectsManager.getInstance(file.project).findProject(file.virtualFile ?: return null) ?: return null
            val service = file.project.service<MavenVersionCheckService>()
            return MavenDependencyAnalysis(model, mavenProject, service.updates(mavenProject),
                file.project.service<VersionCheckerSettings>().state, service.relocations(mavenProject))
        }
    }
}
