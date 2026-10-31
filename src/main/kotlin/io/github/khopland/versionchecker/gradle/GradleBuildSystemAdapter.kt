package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

internal class GradleBuildSystemAdapter(
    externalInputBytes: (Path) -> ByteArray = Files::readAllBytes
) : BuildSystemAdapter {
    private val inputs = GradleBuildInputs(externalInputBytes)
    override fun reloadProject(project: Project): Boolean {
        if (project.isDisposed) return false
        ExternalSystemUtil.refreshProjects(ImportSpecBuilder(project, GradleConstants.SYSTEM_ID))
        return true
    }
    override val id = "gradle"
    override val displayName = "Gradle"
    override val capabilities = AdapterCapabilities()
    override fun invalidateMetadata(project: Project) = project.service<GradleProjectCache>().invalidateMetadata()
    override suspend fun <T> withScan(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode,
                                    action: suspend () -> T): T {
        if (!java.lang.Boolean.getBoolean("versionchecker.gradleScanAggregation")) return action()
        val policy = readAction { projectDeprecations(project, "gradle") }
        val eligible = snapshots.map { snapshot ->
            snapshot.copy(declarations = snapshot.declarations.filter { "${it.artifact.namespace}:${it.artifact.name}" !in policy })
        }
        return withGradleScanSession(project, eligible, mode, action)
    }
    override fun isOffline(project: Project) = GradleSettings.getInstance(project).isOfflineWork
    override fun supports(project: Project, selection: BuildSelection): Boolean = when (selection.scope) {
        UpdateScope.CURRENT_FILE -> selection.currentFile?.let { inputs.find(project, it) }?.let { inputs.supportsFile(project, it) } == true
        UpdateScope.WHOLE_PROJECT -> inputs.roots(project).any { root -> inputs.files(project, root).any { inputs.supportsFile(project, it) } }
    }
    override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> =
        inputs.resolutionInputPaths(project, selection)

    override suspend fun prepareEditValidation(project: Project, snapshots: Collection<BuildSnapshot>): CapturedEditInputs =
        inputs.prepareEditValidation(project, snapshots)

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
        return CheckPerformance.measure(CheckPerformance.Stage.GRADLE_SNAPSHOT) { snapshot(project, file, mutableMapOf(), mutableMapOf()) }
    }
    override fun inspectionSnapshot(project: Project, file: VirtualFile): BuildSnapshot? =
        snapshot(project, file, mutableMapOf(), mutableMapOf(), allowUnsaved = true)

    internal fun declarations(project: Project, file: PsiFile): List<GradleDeclaration> =
        inputs.declarations(project, file.virtualFile, file.text)

    override suspend fun prepareInspectionSnapshot(project: Project, file: VirtualFile): () -> BuildSnapshot? =
        inputs.capture(project) { external ->
            val home = inputs.externalHome(project)
            val capture = { if (project.isDisposed || !file.isValid || home != inputs.externalHome(project)) null else
                snapshot(project, file, mutableMapOf(), mutableMapOf(), allowUnsaved = true, externalHashes = external) }
            capture
        }

    override fun canCheckInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        !inputs.inventory(project, snapshot.context.root).hasUnsavedDocuments()

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<String, BuildFingerprint>, unsaved: MutableMap<String, Boolean>,
                         allowUnsaved: Boolean = false, inspectionFingerprints: MutableMap<String, BuildFingerprint> = mutableMapOf(),
                         externalHashes: Map<String, String>? = null): BuildSnapshot? {
        if (!inputs.supportsFile(project, file)) return null
        val root = inputs.owner(project, file) ?: return null
        if (!allowUnsaved && unsaved.getOrPut(root) { inputs.inventory(project, root).hasUnsavedDocuments() }) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val fingerprint = fingerprints.getOrPut(root) { inputs.fingerprint(project, root, externalHashes) }
        val inspectionFingerprint = inspectionFingerprints.getOrPut(root) {
            fingerprint.copy(files = fingerprint.files + project.service<GradleProjectCache>().inspectionHashes(root, inputs.files(project, root), inputs.catalogFiles(project, root)))
        }
        val parsed = inputs.declarations(project, file, psi.text)
        return BuildSnapshot(BuildContextId(id, root, file.path), file.path, fingerprint,
            parsed.map { it.declaration }, inspectionFingerprint, skippedDeclarations = parsed.filter {
                it.declaration.baseline.isEmpty()
            }.associate { it.declaration.id to (it.reason ?: "Unsupported Gradle version selector needs manual review.") })
    }

    override fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot): UpdateReport? {
        if (previous.context != current.context || previous.inspectionFingerprint == null ||
            previous.inspectionFingerprint != current.inspectionFingerprint) return null
        // Normalized build text is identical, so declarations retain their order even when offsets move.
        val declarations = previous.declarations.zip(current.declarations).filter { (old, new) ->
            old.copy(id = new.id) == new
        }.toMap()
        return report.copy(
            candidates = report.candidates.mapNotNull { candidate ->
                declarations[candidate.declaration]?.let { candidate.copy(declaration = it) }
            },
            notices = report.notices.mapNotNull { notice ->
                declarations[notice.declaration]?.let { notice.copy(declaration = it) }
            },
            coverage = report.coverage?.let { outcomes ->
                declarations.mapNotNull { (old, new) -> outcomes[old.id]?.let { new.id to it } }.toMap() + current.skippedCoverage()
            }
        )
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot) = inputs.find(project, snapshot.sourceFile)?.let { inputs.owner(project, it) == snapshot.context.root && snapshot.fingerprint == inputs.fingerprint(project, snapshot.context.root) } == true
    override suspend fun isCurrentInBackground(project: Project, snapshot: BuildSnapshot): Boolean =
        inputs.capture(project) { external -> !project.isDisposed && inputs.areCurrent(project, listOf(snapshot), external) }

    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = inputs.capture(project) { external ->
        val files = if (selection.scope == UpdateScope.CURRENT_FILE) listOfNotNull(selection.currentFile?.let { inputs.find(project, it) }) else inputs.roots(project).flatMap { inputs.files(project, it) }.distinct()
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        val unsaved = mutableMapOf<String, Boolean>()
        val inspectionFingerprints = mutableMapOf<String, BuildFingerprint>()
        files.mapNotNull { snapshot(project, it, fingerprints, unsaved, inspectionFingerprints = inspectionFingerprints, externalHashes = external) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
        val limitation = readAction { inputs.coverageLimitation(project, snapshot.context.root) }
        if (limitation != null) return UpdateReport(emptyList(), snapshot.declarations.map {
            UpdateNotice(it, NoticeKind.MANUAL_REVIEW, limitation)
        }, coverage = snapshot.declarations.associate {
            it.id to DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, limitation)
        } + snapshot.skippedCoverage())
        val policy = readAction { projectDeprecations(project, "gradle") }
        val checked = GradleVersionLookup.check(project, snapshot.copy(declarations = snapshot.declarations.filter { "${it.artifact.namespace}:${it.artifact.name}" !in policy }), mode)
        val results = checked.results
        return UpdateReport(snapshot.declarations.mapNotNull { declaration ->
            val versions = results[declaration.id.location]?.versions.orEmpty().distinct()
            val latest = versions.singleOrNull() ?: return@mapNotNull null
            if (!GradleVersions.newer(declaration.baseline, latest)) return@mapNotNull null
            UpdateCandidate(declaration, latest, kind = GradleVersions.kind(declaration.baseline, latest))
        }, snapshot.declarations.mapNotNull { declaration ->
            policy["${declaration.artifact.namespace}:${declaration.artifact.name}"]?.let {
                UpdateNotice(declaration, NoticeKind.DEPRECATED, "${declaration.artifact.namespace}:${declaration.artifact.name} is deprecated: $it")
            } ?: results[declaration.id.location]?.reason?.let {
                UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, it)
            }
        }, validUntilNanos = checked.expiresAtNanos, checkedAtNanos = checked.checkedAtNanos,
            coverage = snapshot.declarations.associate { declaration ->
                val result = results[declaration.id.location]
                declaration.id to when {
                    declaration.baseline.isEmpty() -> DeclarationCoverage(DeclarationOutcome.SKIPPED,
                        snapshot.skippedDeclarations[declaration.id] ?: "Unsupported Gradle version selector needs manual review.")
                    "${declaration.artifact.namespace}:${declaration.artifact.name}" in policy -> DeclarationCoverage(
                        DeclarationOutcome.MANUAL_REVIEW, "Artifact is explicitly marked deprecated; review its replacement.")
                    result?.reason != null -> DeclarationCoverage(DeclarationOutcome.MANUAL_REVIEW, result.reason)
                    result == null -> DeclarationCoverage(DeclarationOutcome.SKIPPED,
                        "Native Gradle task returned no result for this declaration.")
                    else -> DeclarationCoverage(DeclarationOutcome.CHECKED)
                }
            } + snapshot.skippedCoverage())
    }
    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = inputs.capture(project) { external ->
        check(inputs.areCurrent(project, reports.keys, external)) { "Gradle build files or settings changed. Run the check again." }
        val edits = mutableListOf<VersionEdit>(); val skipped = mutableListOf<String>()
        val review = mutableListOf<VersionReviewItem>()
        val editReviews = mutableMapOf<VersionEdit, List<VersionDeclaration>>()
        val followUp = mutableSetOf<String>()
        for ((snapshot, report) in reports) {
            val limitation = inputs.coverageLimitation(project, snapshot.context.root)
            if (limitation != null) {
                skipped += "${snapshot.sourceFile}: $limitation"
                review += VersionReviewItem(limitation, VersionReviewSource(snapshot.sourceFile), buildContext = "Gradle")
                continue
            }
            val psi = inputs.find(project, snapshot.sourceFile)?.let { PsiManager.getInstance(project).findFile(it) } ?: error("Gradle build file is unavailable")
            for (prepared in GradlePreparedUpdate.prepare(psi, report, inputs.declarations(project, psi.virtualFile, psi.text))) {
                prepared.edit?.let { edits += it; editReviews[it] = prepared.consumers.map { consumer -> consumer.declaration }; followUp += prepared.followUp }
                if (prepared.edit == null && prepared.reportSkipped) {
                    skipped += "${prepared.location}: ${prepared.blockingReasons.first()}"
                    review += VersionReviewItem(prepared.blockingReasons.joinToString("; "), VersionReviewSource(snapshot.sourceFile, prepared.target?.range?.startOffset ?: 0), prepared.location, prepared.consumers.map { it.declaration.selector }.distinct().joinToString(), prepared.replacement, prepared.consumers.map { "${it.declaration.artifact.namespace}:${it.declaration.artifact.name}" }, "Gradle")
                }
            }
            skipped += report.notices.map { "${it.declaration.id.file}: ${it.message}" }
            review += report.notices.map { VersionReviewItem(it.message, VersionReviewSource(it.declaration.id.file), it.declaration.id.location, it.declaration.selector, buildContext = "Gradle") }
            skipped += report.coverage.orEmpty().filter { (id, result) ->
                result.outcome == DeclarationOutcome.SKIPPED && id !in snapshot.skippedDeclarations
            }.map { (id, result) ->
                review += VersionReviewItem(result.reason.orEmpty(), VersionReviewSource(id.file), id.location, buildContext = "Gradle")
                "${id.file}: ${id.location}: ${result.reason}"
            }
        }
        BulkUpdatePlan(edits.sortedByDescending { (it as GradleVersionEdit).range.startOffset }.map { it.withReview(editReviews[it].orEmpty(), "Gradle") }, skipped, reviewItems = review.distinct(),
            followUp = followUp.toList(),
            prepareValidation = { prepareEditValidation(project, reports.keys) })
    }
}
