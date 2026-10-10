package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.VersionChangeKind
import io.github.khopland.versionchecker.core.ArtifactId

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
    val message: String get() = notice ?: updateHint(
        ArtifactId(coordinate.groupId, coordinate.artifactId), kind, coordinate.version, latest.orEmpty()) + when (coordinate.artifactKind) {
            MavenArtifactKind.PLUGIN -> " (Maven plugin)"
            MavenArtifactKind.PARENT -> " (parent POM)"
            else -> ""
        }
}

internal class MavenDependencyAnalysis(
    private val model: MavenDomProjectModel,
    private val mavenProject: MavenProject,
    private val updates: Map<DependencyVersion, String>,
    private val options: VersionCheckerSettings.Options = VersionCheckerSettings.Options(),
    private val relocations: Map<DependencyVersion, String> = emptyMap(),
    private val inspection: MavenInspectionResult? = null,
) {
    private val deprecated = deprecatedDependencies(options.deprecatedDependencies)
    private val ignoredPolicy = options.ignoredVersions
    private val ignored = ignoredVersions(ignoredPolicy)
    private val versionProperties by lazy {
        MavenVersionProperties(model, mavenProject.activatedProfilesIds.enabledProfiles, ::coordinate)
    }
    internal val propertyConsumers by lazy { versionProperties.consumers() }
    private val propertyConsumersByTarget by lazy { propertyConsumers.groupBy { it.target } }
    private val sharedConsumers by lazy {
        val manager = MavenProjectsManager.getInstance(model.manager.project)
        val models = manager.nonIgnoredProjects.mapNotNull { pom ->
            com.intellij.psi.PsiManager.getInstance(model.manager.project).findFile(pom.file)?.let { file ->
                MavenDomUtil.getMavenDomProjectModel(file)?.let { it to pom }
            }
        }.ifEmpty { listOf(model to mavenProject) }
        val consumers = models.flatMap { (dom, pom) ->
            val analysis = MavenDependencyAnalysis(dom, pom, emptyMap())
            val local = PsiTreeUtil.findChildrenOfType(dom.xmlTag, XmlTag::class.java).mapNotNull { tag ->
                val coordinate = analysis.coordinate(tag) ?: return@mapNotNull null
                val version = tag.findFirstSubTag("version") ?: analysis.managingVersion(tag)
                val target = analysis.sharedVersionTarget(version, coordinate.version) ?: return@mapNotNull null
                target to Triple(tag, dom.xmlTag!!.containingFile, coordinate)
            }
            local + analysis.propertyConsumers.map { it.target to Triple(it.tag, dom.xmlTag!!.containingFile, it.coordinate) }
        }
        consumers.groupBy({ it.first }, { it.second })
    }
    private val sharedScopes by lazy {
        sharedConsumers.mapValues { (_, contexts) ->
            "${contexts.distinctBy { it.first to it.second }.size} version declarations across ${contexts.map { it.second }.distinct().size} POMs"
        }
    }
    private fun sharedAllowed(target: XmlTag, latest: String) = ignored.isEmpty() || sharedConsumers[target].orEmpty().none {
        IgnoredVersion("maven", ArtifactId(it.third.groupId, it.third.artifactId), latest) in ignored
    }
    private fun scopedAction(target: XmlTag, latest: String): String {
        val scope = sharedScopes[target] ?: "shared scope requires review"
        return "Update ${target.localName} to $latest in ${target.containingFile.name} ($scope)"
    }
    private val quickFixAdapter by lazy { inspection?.adapter ?: MavenBuildSystemAdapter() }
    private val quickFixSnapshot by lazy {
        inspection?.snapshot ?: model.xmlTag?.containingFile?.virtualFile?.let {
            quickFixAdapter.snapshot(model.manager.project, it)
        }
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
        val latest = updates[coordinate]?.takeUnless { IgnoredVersion("maven",
            ArtifactId(coordinate.groupId, coordinate.artifactId), it) in ignored }
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
            versionTag == null -> managingVersion(tag)?.let { versionProperties.target(it, current) }
            rawVersion == current -> versionTag
            else -> versionPropertyTarget(versionTag, rawVersion, current)
        }
        return DependencyProblem(coordinate, latest, versionTag ?: tag.findFirstSubTag("artifactId") ?: tag,
            target, kind, kind.severity(options), notice)
    }

    internal fun versionPropertyTarget(versionTag: XmlTag, raw: String?, current: String): XmlTag? =
        findLocalVersionProperty(versionTag, raw, current, mavenProject.activatedProfilesIds.enabledProfiles)

    internal fun propertyProblems(): List<DependencyProblem> = propertyConsumers.mapNotNull { consumer ->
        val problem = problem(consumer.tag) ?: return@mapNotNull null
        problem.copy(anchor = consumer.target, target = consumer.target.takeIf { problem.notice == null })
    }.distinctBy { it.anchor to it.coordinate }

    internal fun propertyQuickFixes(property: XmlTag): Array<LocalQuickFix> {
        val consumers = propertyConsumersByTarget[property].orEmpty()
        val problems = consumers.map { problem(it.tag) }
        val latest = problems.map { it?.latest }.distinct().singleOrNull() ?: return emptyArray()
        if (problems.any { it?.notice != null } || !sharedAllowed(property, latest)) return emptyArray()
        val snapshot = quickFixSnapshot ?: return emptyArray()
        return arrayOf(UpdateDependencyVersionFix(property, latest,
            isCurrent = { quickFixAdapter.isCurrent(property.project, snapshot) && property.project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy })
            .withActionName { scopedAction(property, latest) })
    }

    fun quickFixes(tag: XmlTag, problem: DependencyProblem): Array<LocalQuickFix> {
        val latest = problem.latest ?: return emptyArray()
        if (problem.notice != null) return emptyArray()
        val project = tag.project
        val adapter = quickFixAdapter
        val snapshot = quickFixSnapshot ?: return emptyArray()
        val isCurrent = { adapter.isCurrent(project, snapshot) && project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy }
        problem.target?.let {
            val shared = it.parentTag?.localName == "properties" || it.parentTag?.parentTag?.parentTag?.localName == "dependencyManagement"
            if (shared && !sharedAllowed(it, latest)) return emptyArray()
            val fix = UpdateDependencyVersionFix(it, latest, isCurrent = isCurrent)
            if (shared) fix.withActionName { scopedAction(it, latest) }
            return arrayOf(fix)
        }
        if (problem.coordinate.artifactKind != MavenArtifactKind.DEPENDENCY) return emptyArray()
        val version = tag.findFirstSubTag("version")
        // Composite expressions and ranges retain their existing manual-review behavior.
        if (version != null && versionPropertyName(version.value.trimmedText) == null) return emptyArray()
        val fixes = mutableListOf<LocalQuickFix>(OverrideDependencyVersionFix(tag, latest, isCurrent))
        val target = sharedVersionTarget(version ?: managingVersion(tag), problem.coordinate.version)
        if (target != null && sharedAllowed(target, latest)) {
            val file = target.containingFile.virtualFile
            val base = project.basePath?.trimEnd('/')
            val path = if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.path
            val location = if (file == tag.containingFile.virtualFile) "managed" else "parent"
            fixes += UpdateDependencyVersionFix(target, latest, isCurrent = isCurrent).withActionName {
                "Update $location version to $latest in $path (${sharedScopes[target] ?: "shared scope requires review"})"
            }
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
            val root = model.xmlTag ?: return null
            val active = mavenProject.activatedProfilesIds.enabledProfiles
            val locallyDeclared = root.findFirstSubTag("properties")?.findFirstSubTag(property) != null ||
                root.findFirstSubTag("profiles")?.subTags.orEmpty().any {
                    it.findFirstSubTag("id")?.value?.trimmedText in active &&
                        it.findFirstSubTag("properties")?.findFirstSubTag(property) != null
                }
            val owner = if (locallyDeclared) findLocalVersionPropertyOwner(root, property, active)
                else MavenDomProjectProcessorUtils.searchProperty(property, model, project)?.let { found ->
                    // The DOM lookup can return the default property even if its source POM
                    // has an active override. Never offer that default as a shared edit.
                    findLocalVersionPropertyOwner(found, property).takeIf { it == found }
                }
            target = owner ?: return null
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
            val inspection = file.project.service<MavenVersionCheckService>().inspection(mavenProject) ?: return null
            return MavenDependencyAnalysis(model, mavenProject, inspection.report.mavenUpdates(),
                file.project.service<VersionCheckerSettings>().state, inspection.report.mavenRelocations(), inspection)
        }
    }
}
