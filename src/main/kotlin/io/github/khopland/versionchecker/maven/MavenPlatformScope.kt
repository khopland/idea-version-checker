package io.github.khopland.versionchecker.maven

import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.core.BuildSnapshot
import org.jetbrains.idea.maven.dom.MavenDomProjectProcessorUtils
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

/** Only imported, editable ancestors are scanned; repository-cache POMs are never update targets. */
internal object MavenPlatformScope {
    private const val SUFFIX = "#version-checker-platform"
    fun isPlatform(snapshot: BuildSnapshot) = snapshot.context.resolutionId.endsWith(SUFFIX)

    fun projects(manager: MavenProjectsManager, path: String?): List<MavenProject> {
        val selected = manager.nonIgnoredProjects.singleOrNull { it.path == path } ?: return emptyList()
        val model = model(manager, selected) ?: return emptyList()
        return (listOf(model) + MavenDomProjectProcessorUtils.collectParentProjects(model)).mapNotNull { source ->
            source.xmlTag?.containingFile?.virtualFile?.let(manager::findProject)?.takeUnless(manager::isIgnored)
        }.distinctBy { it.path }.sortedByDescending { source -> hasPlatform(manager, source) }
    }

    fun hasPlatform(manager: MavenProjectsManager, project: MavenProject): Boolean {
        val model = model(manager, project) ?: return false
        val analysis = MavenDependencyAnalysis(model, project, emptyMap())
        return tags(model).any { tag -> analysis.coordinate(tag)?.let { coordinate ->
            coordinate.artifactKind == MavenArtifactKind.PARENT || importBom(tag, model)
        } == true }
    }

    fun platformCoordinates(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot): Set<DependencyVersion> {
        val model = model(manager, project) ?: return emptySet()
        val local = tags(model).associateBy { "DEPENDENCY:${it.textOffset}" }
        val analysis = MavenDependencyAnalysis(model, project, emptyMap())
        val inheritedBoms = analysis.propertyConsumers.filter { importBom(it.tag, model) }.map { it.coordinate }.toSet()
        return snapshot.declarations.mapNotNull { declaration ->
            val coordinate = declaration.coordinate()
            coordinate.takeIf { it.artifactKind == MavenArtifactKind.PARENT || it in inheritedBoms ||
                local[declaration.id.location]?.let { tag -> importBom(tag, model) } == true }
        }.toSet()
    }

    fun select(manager: MavenProjectsManager, project: MavenProject, snapshot: BuildSnapshot): BuildSnapshot {
        val model = model(manager, project) ?: return snapshot.copy(declarations = emptyList())
        val platforms = platformCoordinates(manager, project, snapshot)
        val local = tags(model).associateBy { "DEPENDENCY:${it.textOffset}" }
        val inherited = MavenDomProjectProcessorUtils.collectParentProjects(model).flatMap(::tags)
            .filter { isProjectDependency(it) && managed(it) }.map { tag ->
                val group = tag.findFirstSubTag("groupId")?.value?.trimmedText.orEmpty()
                val artifact = tag.findFirstSubTag("artifactId")?.value?.trimmedText.orEmpty()
                MavenPropertyResolver.resolve(group, model) to MavenPropertyResolver.resolve(artifact, model)
            }
        val uncertain = inherited.any { (group, artifact) -> group.isBlank() || artifact.isBlank() || '$' in group || '$' in artifact }
        val selected = snapshot.declarations.filter { declaration ->
            val coordinate = declaration.coordinate()
            coordinate in platforms || declaration.id.location.contains(":property:") || local[declaration.id.location]?.let { tag ->
                tag.findFirstSubTag("version") != null && (!managed(tag) || uncertain || (coordinate.groupId to coordinate.artifactId) in inherited)
            } == true
        }.sortedByDescending { it.coordinate() in platforms }
        return snapshot.copy(context = snapshot.context.copy(resolutionId = snapshot.context.resolutionId + SUFFIX), declarations = selected,
            coverageDescription = "Maven platform scope checks ${selected.size} of ${snapshot.declarations.size} supported declarations: platform versions and explicit overrides. Use Current File or Whole Project for a full audit.")
    }

    private fun model(manager: MavenProjectsManager, project: MavenProject) = PsiManager.getInstance(manager.project).findFile(project.file)
        ?.let(MavenDomUtil::getMavenDomProjectModel)
    private fun tags(model: MavenDomProjectModel) = model.xmlTag?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) }.orEmpty()
    private fun managed(tag: XmlTag) = generateSequence(tag.parentTag) { it.parentTag }.any { it.localName == "dependencyManagement" }
    private fun importBom(tag: XmlTag, model: MavenDomProjectModel) = isProjectDependency(tag) &&
        tag.findFirstSubTag("scope")?.value?.trimmedText?.let { MavenPropertyResolver.resolve(it, model) } == "import"
}
