package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.CapturedEditInputs

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
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MavenBuildSystemAdapter(
    private val readSavedFingerprint: ((Path) -> String)? = null
) : BuildSystemAdapter {
    override suspend fun <T> withScan(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode,
                                    action: suspend () -> T): T = withMavenScanSession(action)
    override fun reloadProject(project: Project): Boolean {
        if (project.isDisposed) return false
        MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()
        return true
    }
    override val id = "maven"
    override val displayName = "Maven"
    override val capabilities = AdapterCapabilities(incrementalInspections = true, updateScopes = UpdateScope.entries.toSet())

    override fun supports(project: Project, selection: BuildSelection): Boolean {
        val manager = MavenProjectsManager.getInstance(project)
        val projects = selectedProjects(manager, selection)
        return projects.isNotEmpty() && (selection.scope != UpdateScope.MAVEN_PLATFORM || projects.any { MavenPlatformScope.hasPlatform(manager, it) })
    }

    override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val inputs = readAction {
            val manager = MavenProjectsManager.getInstance(project)
            val selected = selectedProjects(manager, selection)
            if (selected.isEmpty()) return@readAction null
            MavenInputInventory.layout(manager, selected)
        } ?: return emptySet()
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            MavenInputInventory.discover(inputs) { context.ensureActive() }.resolutionInputPaths()
        }
    }

    override fun isOffline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    override fun invalidateMetadata(project: Project) { project.service<MavenMetadataService>().invalidate() }

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? =
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SNAPSHOT) {
            val manager = MavenProjectsManager.getInstance(project)
            inspectionSnapshot(project, file, MavenProjectInputs(manager))
        }

    override suspend fun prepareInspectionSnapshot(project: Project, file: VirtualFile): () -> BuildSnapshot? =
        MavenProjectInputs.capture(MavenProjectsManager.getInstance(project), readSavedFingerprint) { inputs ->
            val capture = { if (project.isDisposed || !file.isValid) null else inputs.currentView()?.let {
                inspectionSnapshot(project, file, it)
            } }
            capture
        }

    private fun inspectionSnapshot(project: Project, file: VirtualFile, inputs: MavenProjectInputs): BuildSnapshot? {
        return CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SNAPSHOT) {
            val manager = MavenProjectsManager.getInstance(project)
            val mavenProject = manager.findProject(file)?.takeUnless { manager.isIgnored(it) } ?: return@measure null
            val snapshot = captureSnapshot(project, mavenProject, inputs) ?: return@measure null
            // Retain the full audit's coverage and failures until it expires or is invalidated.
            if (!project.service<VersionCheckerSettings>().state.mavenFastEditorChecks ||
                project.service<VersionCheckService>().cached(snapshot) != null) snapshot else {
                val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: return@measure snapshot
                val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@measure snapshot
                MavenFastEditorScope.select(manager, model, snapshot)
            }
        }
    }

    private fun captureSnapshot(project: Project, mavenProject: MavenProject, inputs: MavenProjectInputs): BuildSnapshot? {
        val file = mavenProject.file
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val model = MavenDomUtil.getMavenDomProjectModel(psi) ?: return null
        val analysis = MavenDependencyAnalysis(model, mavenProject, emptyMap())
        val skipped = linkedMapOf<DeclarationId, String>()
        val declarations = PsiTreeUtil.findChildrenOfType(psi, XmlTag::class.java).mapNotNull { tag ->
            val coordinate = analysis.coordinate(tag) ?: run {
                analysis.skippedReason(tag)?.let { skipped[DeclarationId(file.path, "SKIPPED:${tag.textOffset}")] = it }
                return@mapNotNull null
            }
            VersionDeclaration(DeclarationId(file.path, "${coordinate.artifactKind.name}:${tag.textOffset}"), coordinate.artifactId(),
                tag.findFirstSubTag("version")?.value?.trimmedText ?: coordinate.version, coordinate.version)
        } + (analysis.propertyConsumers + analysis.observedInheritedPropertyConsumers).filter { it.tag.containingFile != psi }.map { consumer ->
            val coordinate = consumer.coordinate
            VersionDeclaration(DeclarationId(file.path,
                "${coordinate.artifactKind.name}:property:${consumer.target.textOffset}:${consumer.tag.containingFile.virtualFile.path}:${consumer.tag.textOffset}"),
                coordinate.artifactId(), consumer.target.value.trimmedText, coordinate.version,
                sourceId = DeclarationId(consumer.tag.containingFile.virtualFile.path, "${coordinate.artifactKind.name}:${consumer.tag.textOffset}"))
        }
        return BuildSnapshot(BuildContextId(id, file.parent.path, file.path), file.path, inputs.fingerprint(mavenProject), declarations,
            inputs.inspectionFingerprint(mavenProject), skippedDeclarations = skipped)
    }

    override fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot): UpdateReport? {
        if (previous.context != current.context || previous.inspectionFingerprint == null ||
            previous.inspectionFingerprint != current.inspectionFingerprint) return null
        // XML offsets move when an earlier version changes length. Match the unchanged declaration
        // by coordinates, kind and selector, then attach the result to its current location.
        fun VersionDeclaration.key() = copy(id = id.copy(location = coordinate().artifactKind.name),
            sourceId = sourceId?.copy(location = coordinate().artifactKind.name))
        val candidates = report.candidates.associateBy { it.declaration.key() }
        val notices = report.notices.groupBy { it.declaration.key() }
        val outcomes = previous.declarations.mapNotNull { declaration -> report.coverage?.get(declaration.id)?.let {
            declaration.key() to it
        } }.toMap()
        return report.copy(
            candidates = current.declarations.mapNotNull { declaration ->
                candidates[declaration.key()]?.copy(declaration = declaration)
            },
            notices = current.declarations.flatMap { declaration ->
                notices[declaration.key()].orEmpty().map { it.copy(declaration = declaration) }
            },
            coverage = report.coverage?.let {
                current.declarations.mapNotNull { declaration -> outcomes[declaration.key()]?.let { declaration.id to it } }.toMap() +
                    current.skippedCoverage()
            }
        )
    }

    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        return areCurrent(project, listOf(snapshot))
    }

    override suspend fun isCurrentInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        MavenProjectInputs.capture(MavenProjectsManager.getInstance(project), readSavedFingerprint) { inputs ->
            !project.isDisposed && areCurrent(inputs, listOf(snapshot))
        }

    override suspend fun prepareEditValidation(project: Project, snapshots: Collection<BuildSnapshot>): CapturedEditInputs {
        val saved = prepareSavedBuildInputValidation(project, snapshots)
        return MavenProjectInputs.capture(MavenProjectsManager.getInstance(project), readSavedFingerprint) { inputs ->
            CapturedEditInputs { saved.isCurrent() && !project.isDisposed && inputs.currentView()?.let { areCurrent(it, snapshots) } == true }
        }
    }

    internal fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>): Boolean {
        if (snapshots.isEmpty()) return true
        return areCurrent(MavenProjectInputs(MavenProjectsManager.getInstance(project)), snapshots)
    }

    private fun areCurrent(inputs: MavenProjectInputs, snapshots: Collection<BuildSnapshot>): Boolean =
        snapshots.all { snapshot -> inputs.find(snapshot.sourceFile)?.let { snapshot.fingerprint == inputs.fingerprint(it) } == true }

    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> =
        MavenProjectInputs.capture(MavenProjectsManager.getInstance(project), readSavedFingerprint) { inputs ->
        val manager = MavenProjectsManager.getInstance(project)
        selectedProjects(manager, selection).mapNotNull { mavenProject ->
            CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SNAPSHOT) {
                val snapshot = captureSnapshot(project, mavenProject, inputs) ?: return@measure null
                if (selection.scope == UpdateScope.MAVEN_PLATFORM) MavenPlatformScope.select(manager, mavenProject, snapshot)
                    .takeIf { it.declarations.isNotEmpty() } else snapshot
            }
        }
    }

    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        return checkReports(project, snapshot, mode, null)
    }

    override suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                          publish: suspend (InspectionUpdate) -> Unit): UpdateReport =
        checkReports(project, snapshot, mode, publish)

    private suspend fun checkReports(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                     publish: (suspend (InspectionUpdate) -> Unit)?): UpdateReport {
        val manager = MavenProjectsManager.getInstance(project)
        val mavenProject = readAction { findProject(manager, snapshot) } ?: error("Maven POM is no longer imported")
        if (publish != null && snapshot.skippedDeclarations.isNotEmpty())
            publish(InspectionUpdate(UpdateReport(coverage = snapshot.skippedCoverage()), snapshot.skippedDeclarations.keys))
        val native = MavenBatchedLookup.check(manager, mavenProject, snapshot, mode, publish?.let { publisher ->
            { delta ->
                val declarations = snapshot.declarations.filter { it.coordinate() in delta.coordinates }
                val report = versionReport(mavenProject, snapshot.copy(declarations = declarations), delta.updates, delta.failedCoordinates).copy(
                    failure = delta.failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message.orEmpty() },
                    failureCause = delta.failures.firstOrNull(), validUntilNanos = delta.validUntil, checkedAtNanos = delta.checkedAtNanos)
                publisher(InspectionUpdate(report, declarations.map { it.id }.toSet()))
            }
        })
        if (native != null) return versionReport(mavenProject, snapshot, native.updates, native.failedCoordinates).copy(
            failure = native.failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message.orEmpty() },
            failureCause = native.failures.firstOrNull(), validUntilNanos = native.validUntil, checkedAtNanos = native.checkedAtNanos)
        val coordinates = snapshot.declarations.map { it.coordinate() }
        val kinds = coordinates.map { it.artifactKind }.toSet()
        val failures = mutableListOf<Exception>()
        val failedCoordinates = mutableSetOf<DependencyVersion>()
        val versions = if (publish == null) MavenVersionLookup.checkAll(manager, mavenProject, mode, kinds, coordinates) else
            MavenVersionLookup.checkAllIncrementally(manager, mavenProject, mode, kinds, coordinates) { kind, result ->
                val declarations = snapshot.declarations.filter { it.coordinate().artifactKind == kind }
                val category = snapshot.copy(declarations = declarations)
                val failure = result.exceptionOrNull() as? Exception
                failure?.let(failures::add)
                if (failure != null) failedCoordinates += declarations.map { it.coordinate() }
                val report = versionReport(mavenProject, category, result.getOrNull().orEmpty(), failedCoordinates).copy(
                    failure = failure?.let { "${kind.name.lowercase()}: ${it.message ?: it.javaClass.simpleName}" }, failureCause = failure)
                publish(InspectionUpdate(report, declarations.map { it.id }.toSet()))
            }
        return versionReport(mavenProject, snapshot, versions, failedCoordinates).copy(
            failure = failures.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message ?: it.javaClass.simpleName },
            failureCause = failures.firstOrNull())
    }

    private fun versionReport(mavenProject: MavenProject, snapshot: BuildSnapshot,
                              versions: Map<DependencyVersion, String>, failed: Set<DependencyVersion> = emptySet()): UpdateReport {
        val relocations = readRelocations(mavenProject, snapshot)
        return UpdateReport(
            snapshot.declarations.mapNotNull { declaration -> versions[declaration.coordinate()]?.let {
                UpdateCandidate(declaration, it, kind = MavenVersionSemantics.between(declaration.baseline, it))
            } },
            snapshot.declarations.mapNotNull { declaration -> relocations[declaration.coordinate()]?.let { UpdateNotice(declaration, NoticeKind.RELOCATED, it) } },
            coverage = snapshot.declarations.associate { declaration -> declaration.id to
                if (declaration.coordinate() in failed) DeclarationCoverage(DeclarationOutcome.FAILED, "Native Maven metadata check failed.")
                else DeclarationCoverage(DeclarationOutcome.CHECKED)
            } + snapshot.skippedCoverage()
        )
    }

    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan =
        MavenProjectInputs.capture(MavenProjectsManager.getInstance(project), readSavedFingerprint) { inputs ->
        val manager = MavenProjectsManager.getInstance(project)
        check(areCurrent(inputs, reports.keys)) { "Maven files or settings changed during the check. Run it again." }
        val files = reports.map { (snapshot, report) ->
            val mavenProject = inputs.find(snapshot.sourceFile) ?: error("Maven POM is no longer imported")
            val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: error("Maven POM is no longer available")
            val model = MavenDomUtil.getMavenDomProjectModel(file) ?: error("Maven POM is no longer valid")
            file to MavenDependencyAnalysis(model, mavenProject, report.mavenUpdates(), project.service<VersionCheckerSettings>().state, report.mavenRelocations())
        }.toMap()
        val usages = inputs.projects.mapNotNull { PsiManager.getInstance(project).findFile(it.file) }
        val plan = MavenBulkUpdatePlan.create(files, usageFiles = usages).copy(
            prepareValidation = { prepareEditValidation(project, reports.keys) })
        val review = reports.keys.flatMap { it.skippedDeclarations.entries }.map {
            VersionReviewItem(it.value, VersionReviewSource(it.key.file), it.key.location, buildContext = "Maven")
        }
        plan.copy(reviewItems = (plan.reviewItems + review).distinct(), skipped = (plan.skipped + reports.keys.flatMap { it.skippedDeclarations.entries }.map {
            "${it.key.file}: ${it.key.location}: ${it.value}"
        }).distinct())
    }

    private fun findProject(manager: MavenProjectsManager, snapshot: BuildSnapshot): MavenProject? =
        manager.nonIgnoredProjects.singleOrNull { it.path == snapshot.sourceFile }

    private fun selectedProjects(manager: MavenProjectsManager, selection: BuildSelection): List<MavenProject> =
        if (selection.scope == UpdateScope.MAVEN_PLATFORM) MavenPlatformScope.projects(manager, selection.currentFile) else
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
