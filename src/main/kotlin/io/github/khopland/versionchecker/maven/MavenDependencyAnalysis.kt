package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.VersionChangeKind

import io.github.khopland.versionchecker.*

import com.intellij.openapi.components.service
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import com.intellij.util.xml.DomManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

internal data class DependencyProblem(
    val coordinate: DependencyVersion, val latest: String?, val anchor: XmlTag, val target: XmlTag?,
    val kind: VersionChangeKind,
    val severity: VersionSeverity,
    val notice: String? = null
) {
    val message: String get() = notice ?: "Newer version of ${when (coordinate.artifactKind) {
        MavenArtifactKind.PLUGIN -> "Maven plugin "
        MavenArtifactKind.PARENT -> "parent POM "
        MavenArtifactKind.DEPENDENCY -> ""
    }}${coordinate.groupId}:${coordinate.artifactId} is available: ${coordinate.version} → $latest"
}

internal class MavenDependencyAnalysis(
    private val model: MavenDomProjectModel,
    private val mavenProject: MavenProject,
    private val updates: Map<DependencyVersion, String>,
    private val options: VersionCheckerSettings.Options = VersionCheckerSettings.Options(),
    private val relocations: Map<DependencyVersion, String> = emptyMap()
) {
    private val deprecated = deprecatedDependencies(options.deprecatedDependencies)
    private val quickFixAdapter by lazy { MavenBuildSystemAdapter() }
    private val quickFixSnapshot by lazy {
        model.xmlTag?.containingFile?.virtualFile?.let { quickFixAdapter.snapshot(model.manager.project, it) }
    }

    fun coordinate(tag: XmlTag): DependencyVersion? {
        if (isProjectParent(tag)) return parentCoordinate(tag)
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

    private fun parentCoordinate(tag: XmlTag): DependencyVersion? {
        val group = tag.findFirstSubTag("groupId")?.value?.trimmedText ?: return null
        val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText ?: return null
        val version = tag.findFirstSubTag("version")?.value?.trimmedText ?: return null
        if (!DependencyUpdateReport.isFixedVersion(version)) return null
        // Workspace parents are versioned with the reactor, so their children must not be bumped to a release.
        if (MavenProjectsManager.getInstance(tag.project).projects.any {
                it.mavenId.groupId == group && it.mavenId.artifactId == artifact }) return null
        return DependencyVersion(group, artifact, version, MavenArtifactKind.PARENT)
    }

    fun problem(tag: XmlTag): DependencyProblem? {
        val coordinate = coordinate(tag) ?: return null
        val latest = updates[coordinate]
        val id = "${coordinate.groupId}:${coordinate.artifactId}"
        val label = when (coordinate.artifactKind) {
            MavenArtifactKind.PLUGIN -> "Maven plugin"
            MavenArtifactKind.PARENT -> "Parent POM"
            MavenArtifactKind.DEPENDENCY -> "Dependency"
        }
        val notice = relocations[coordinate]?.let { "$label $id:${coordinate.version} has been relocated to $it" }
            ?: deprecated[id]?.let { "$label $id is marked deprecated: $it" }
        if (latest == null && notice == null) return null
        val kind = if (notice != null) VersionChangeKind.DEPRECATED else MavenVersionSemantics.between(coordinate.version, latest!!)
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

    fun quickFixes(tag: XmlTag, problem: DependencyProblem): Array<LocalQuickFix> {
        val latest = problem.latest ?: return emptyArray()
        if (problem.notice != null) return emptyArray()
        val project = tag.project
        val adapter = quickFixAdapter
        val snapshot = quickFixSnapshot ?: return emptyArray()
        val isCurrent = { adapter.isCurrent(project, snapshot) }
        problem.target?.let { return arrayOf(UpdateDependencyVersionFix(it, latest, isCurrent = isCurrent)) }
        if (problem.coordinate.artifactKind != MavenArtifactKind.DEPENDENCY) return emptyArray()
        val version = tag.findFirstSubTag("version")
        // Composite expressions and ranges retain their existing manual-review behavior.
        if (version != null && versionPropertyName(version.value.trimmedText) == null) return emptyArray()
        val fixes = mutableListOf<LocalQuickFix>(OverrideDependencyVersionFix(tag, latest, isCurrent))
        val target = sharedVersionTarget(version ?: managingVersion(tag), problem.coordinate.version)
        if (target != null) {
            val file = target.containingFile.virtualFile
            val base = project.basePath?.trimEnd('/')
            val path = if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
            val location = if (file == tag.containingFile.virtualFile) "managed" else "parent"
            fixes += UpdateDependencyVersionFix(target, latest,
                "Update $location version to $latest in $path", isCurrent)
        }
        return fixes.toTypedArray()
    }

    private fun managingVersion(tag: XmlTag): XmlTag? {
        val dependency = DomManager.getDomManager(tag.project).getDomElement(tag) as? MavenDomDependency ?: return null
        return MavenDomProjectProcessorUtils.searchManagingDependency(dependency)?.version?.xmlTag
    }

    private fun sharedVersionTarget(version: XmlTag?, current: String): XmlTag? {
        var target = version ?: return null
        val project = target.project
        val manager = MavenProjectsManager.getInstance(project)
        val sourceFiles = (MavenDomProjectProcessorUtils.collectParentProjects(model) + model)
            .mapNotNull { it.xmlTag?.containingFile?.virtualFile }.toSet()
        val visited = mutableSetOf<XmlTag>()
        while (visited.add(target)) {
            val file = target.containingFile.virtualFile
            if (file !in sourceFiles || manager.findProject(file)?.let { manager.isIgnored(it) } != false) return null
            val raw = target.value.trimmedText
            if (raw == current) return target
            val property = versionPropertyName(raw) ?: return null
            target = MavenDomProjectProcessorUtils.searchProperty(property, model, project) ?: return null
        }
        return null
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
