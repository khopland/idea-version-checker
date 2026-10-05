package io.github.khopland.versionchecker.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import java.nio.file.Files
import java.nio.file.Path

internal class MavenBuildSystemAdapter : BuildSystemAdapter {
    override val id = "maven"
    override val displayName = "Maven"
    override val currentFileLabel = "Current POM"
    override val capabilities = AdapterCapabilities(ArtifactRole.entries.toSet())

    override fun supports(project: Project, selection: BuildSelection): Boolean {
        val manager = MavenProjectsManager.getInstance(project)
        return selectedProjects(manager, selection).isNotEmpty()
    }

    override fun isOffline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        val manager = MavenProjectsManager.getInstance(project)
        val mavenProject = manager.findProject(file)?.takeUnless { manager.isIgnored(it) } ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val model = MavenDomUtil.getMavenDomProjectModel(psi) ?: return null
        val analysis = MavenDependencyAnalysis(model, mavenProject, emptyMap())
        val declarations = PsiTreeUtil.findChildrenOfType(psi, XmlTag::class.java).mapNotNull { tag ->
            val coordinate = analysis.coordinate(tag) ?: return@mapNotNull null
            VersionDeclaration(DeclarationId(file.path, tag.textOffset.toString()), coordinate.artifactId(), coordinate.artifactKind,
                tag.findFirstSubTag("version")?.value?.trimmedText ?: coordinate.version, coordinate.version)
        }
        return BuildSnapshot(BuildContextId(id, file.parent.path, file.path), file.path, fingerprint(manager, mavenProject), declarations)
    }

    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val manager = MavenProjectsManager.getInstance(project)
        val mavenProject = findProject(manager, snapshot) ?: return false
        return snapshot.fingerprint == fingerprint(manager, mavenProject)
    }

    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        val manager = MavenProjectsManager.getInstance(project)
        selectedProjects(manager, selection).mapNotNull { snapshot(project, it.file) }
    }

    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val manager = MavenProjectsManager.getInstance(project)
        val mavenProject = readAction { findProject(manager, snapshot) } ?: error("Maven POM is no longer imported")
        val versions = MavenVersionLookup.check(manager, mavenProject, mode) +
            MavenVersionLookup.check(manager, mavenProject, mode, MavenArtifactKind.PLUGIN)
        val relocations = readRelocations(mavenProject, snapshot)
        return UpdateReport(
            snapshot.declarations.mapNotNull { declaration -> versions[declaration.coordinate()]?.let {
                UpdateCandidate(declaration, it, kind = MavenVersionSemantics.between(declaration.baseline, it))
            } },
            snapshot.declarations.mapNotNull { declaration -> relocations[declaration.coordinate()]?.let { UpdateNotice(declaration, NoticeKind.RELOCATED, it) } }
        )
    }

    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(reports.keys.all { isCurrent(project, it) }) { "Maven files or settings changed during the check. Run it again." }
        val manager = MavenProjectsManager.getInstance(project)
        val files = reports.map { (snapshot, report) ->
            val mavenProject = findProject(manager, snapshot) ?: error("Maven POM is no longer imported")
            val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: error("Maven POM is no longer available")
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: error("Maven POM is no longer valid")
            file to MavenDependencyAnalysis(model, mavenProject, report.mavenUpdates(), project.service<VersionCheckerSettings>().state, report.mavenRelocations())
        }.toMap()
        val usages = manager.nonIgnoredProjects.mapNotNull { PsiManager.getInstance(project).findFile(it.file) }
        MavenBulkUpdatePlan.create(files, usageFiles = usages, isCurrent = { reports.keys.all { isCurrent(project, it) } })
    }

    private fun findProject(manager: MavenProjectsManager, snapshot: BuildSnapshot): MavenProject? =
        manager.nonIgnoredProjects.singleOrNull { it.path == snapshot.sourceFile }

    private fun selectedProjects(manager: MavenProjectsManager, selection: BuildSelection): List<MavenProject> =
        manager.nonIgnoredProjects.filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.path == selection.currentFile }

    private fun fingerprint(manager: MavenProjectsManager, mavenProject: MavenProject): BuildFingerprint {
        // Ownership checks include unselected modules; edits there must invalidate a prepared plan too.
        val files = manager.nonIgnoredProjects.associate { pom ->
            pom.path to "${pom.file.modificationStamp}:${FileDocumentManager.getInstance().getCachedDocument(pom.file)?.takeIf { FileDocumentManager.getInstance().isDocumentUnsaved(it) }?.modificationStamp}"
        }.toMutableMap()
        val settings = manager.generalSettings.userSettingsFile.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".m2", "settings.xml").toString()
        files[settings] = diskStamp(Path.of(settings))
        var ancestor: Path? = mavenProject.file.toNioPath().parent
        while (ancestor != null) {
            for (name in listOf("maven.config", "jvm.config", "wrapper/maven-wrapper.properties")) {
                val config = ancestor.resolve(".mvn").resolve(name)
                files[config.toString()] = diskStamp(config)
            }
            ancestor = ancestor.parent
        }
        val policy = manager.project.service<VersionCheckerSettings>().state.deprecatedDependencies.hashCode()
        return BuildFingerprint(files, "${manager.modificationTracker.modificationCount}:${manager.generalSettings.hashCode()}:${manager.explicitProfiles.hashCode()}:$policy")
    }

    private fun diskStamp(path: Path): String = if (Files.exists(path)) "${Files.getLastModifiedTime(path)}:${Files.size(path)}" else "missing"

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
internal fun VersionDeclaration.coordinate() = DependencyVersion(artifact.name.substringBefore(':'), artifact.name.substringAfter(':'), baseline, role)
internal fun UpdateReport.mavenUpdates() = candidates.associate { it.declaration.coordinate() to it.version }
internal fun UpdateReport.mavenRelocations() = notices.filter { it.kind == NoticeKind.RELOCATED }.associate { it.declaration.coordinate() to it.message }
