package io.github.khopland.versionchecker.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

internal class MavenBuildSystemAdapter : BuildSystemAdapter {
    override val id = "maven"
    override val displayName = "Maven"
    override val capabilities = AdapterCapabilities()

    override fun supports(project: Project, selection: BuildSelection): Boolean {
        val manager = MavenProjectsManager.getInstance(project)
        return selectedProjects(manager, selection).isNotEmpty()
    }

    override fun isOffline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? =
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SNAPSHOT) {
            val manager = MavenProjectsManager.getInstance(project)
            val mavenProject = manager.findProject(file)?.takeUnless { manager.isIgnored(it) } ?: return@measure null
            captureSnapshot(project, mavenProject, MavenProjectInputs(manager))
        }

    private fun captureSnapshot(project: Project, mavenProject: MavenProject, inputs: MavenProjectInputs): BuildSnapshot? {
        val file = mavenProject.file
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val model = MavenDomUtil.getMavenDomProjectModel(psi) ?: return null
        val analysis = MavenDependencyAnalysis(model, mavenProject, emptyMap())
        val declarations = PsiTreeUtil.findChildrenOfType(psi, XmlTag::class.java).mapNotNull { tag ->
            val coordinate = analysis.coordinate(tag) ?: return@mapNotNull null
            VersionDeclaration(DeclarationId(file.path, "${coordinate.artifactKind.name}:${tag.textOffset}"), coordinate.artifactId(),
                tag.findFirstSubTag("version")?.value?.trimmedText ?: coordinate.version, coordinate.version)
        }
        return BuildSnapshot(BuildContextId(id, file.parent.path, file.path), file.path, inputs.fingerprint(mavenProject), declarations)
    }

    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        return areCurrent(project, listOf(snapshot))
    }

    internal fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>): Boolean {
        if (snapshots.isEmpty()) return true
        return areCurrent(MavenProjectInputs(MavenProjectsManager.getInstance(project)), snapshots)
    }

    private fun areCurrent(inputs: MavenProjectInputs, snapshots: Collection<BuildSnapshot>): Boolean =
        snapshots.all { snapshot -> inputs.find(snapshot.sourceFile)?.let { snapshot.fingerprint == inputs.fingerprint(it) } == true }

    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        val manager = MavenProjectsManager.getInstance(project)
        val inputs = MavenProjectInputs(manager)
        inputs.projects.filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile }.mapNotNull { mavenProject ->
            CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SNAPSHOT) { captureSnapshot(project, mavenProject, inputs) }
        }
    }

    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val manager = MavenProjectsManager.getInstance(project)
        val mavenProject = readAction { findProject(manager, snapshot) } ?: error("Maven POM is no longer imported")
        val coordinates = snapshot.declarations.map { it.coordinate() }
        val kinds = coordinates.map { it.artifactKind }.toSet()
        val versions = MavenVersionLookup.checkAll(manager, mavenProject, mode, kinds, coordinates)
        val relocations = readRelocations(mavenProject, snapshot)
        return UpdateReport(
            snapshot.declarations.mapNotNull { declaration -> versions[declaration.coordinate()]?.let {
                UpdateCandidate(declaration, it, kind = MavenVersionSemantics.between(declaration.baseline, it))
            } },
            snapshot.declarations.mapNotNull { declaration -> relocations[declaration.coordinate()]?.let { UpdateNotice(declaration, NoticeKind.RELOCATED, it) } }
        )
    }

    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        val manager = MavenProjectsManager.getInstance(project)
        val inputs = MavenProjectInputs(manager)
        check(areCurrent(inputs, reports.keys)) { "Maven files or settings changed during the check. Run it again." }
        val files = reports.map { (snapshot, report) ->
            val mavenProject = inputs.find(snapshot.sourceFile) ?: error("Maven POM is no longer imported")
            val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: error("Maven POM is no longer available")
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: error("Maven POM is no longer valid")
            file to MavenDependencyAnalysis(model, mavenProject, report.mavenUpdates(), project.service<VersionCheckerSettings>().state, report.mavenRelocations())
        }.toMap()
        val usages = inputs.projects.mapNotNull { PsiManager.getInstance(project).findFile(it.file) }
        MavenBulkUpdatePlan.create(files, usageFiles = usages, isCurrent = { areCurrent(project, reports.keys) })
    }

    private fun findProject(manager: MavenProjectsManager, snapshot: BuildSnapshot): MavenProject? =
        manager.nonIgnoredProjects.singleOrNull { it.path == snapshot.sourceFile }

    private fun selectedProjects(manager: MavenProjectsManager, selection: BuildSelection): List<MavenProject> =
        manager.nonIgnoredProjects.filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile }

    private fun readRelocations(mavenProject: MavenProject, snapshot: BuildSnapshot): Map<DependencyVersion, String> = buildMap {
        for (coordinate in snapshot.declarations.map { it.coordinate() }.distinct()) {
            try {
                MavenRelocation.read(mavenProject.localRepositoryPath, coordinate)?.let { put(coordinate, it) }
            } catch (failure: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(MavenBuildSystemAdapter::class.java).debug("Could not read relocation for $coordinate", failure)
            }
        }
    }
}

internal fun DependencyVersion.artifactId() = ArtifactId("maven", "$groupId:$artifactId")
internal fun VersionDeclaration.coordinate() = DependencyVersion(artifact.name.substringBefore(':'), artifact.name.substringAfter(':'), baseline,
    MavenArtifactKind.valueOf(id.location.substringBefore(':')))
internal fun UpdateReport.mavenUpdates() = candidates.associate { it.declaration.coordinate() to it.version }
internal fun UpdateReport.mavenRelocations() = notices.filter { it.kind == NoticeKind.RELOCATED }.associate { it.declaration.coordinate() to it.message }
