package io.github.khopland.versionchecker.npm

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore

internal class NpmBuildSystemAdapter : BuildSystemAdapter {
    override val id = "npm"
    override val displayName = "npm"
    override val capabilities = AdapterCapabilities(incrementalInspections = true)
    private val registrySlots = Semaphore(4)
    override fun isOffline(project: Project) = false // npm evaluates its own offline/cache configuration.
    override fun supports(project: Project, selection: BuildSelection) =
        selection.scope in capabilities.updateScopes && NpmManifest.files(project, selection).any { NpmWorkspaces.supportsManifest(project, it) }

    override fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val workspaces = NpmManifest.files(project, selection).map { NpmWorkspaces.resolve(project, it) }.distinctBy { it.root }
        val unsaved = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().unsavedDocuments.mapNotNull {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)?.path
        }
        return workspaces.flatMap { workspace ->
            NpmBuildInputs.fingerprint(project, workspace).files.keys.map {
                it.removePrefix("disk-config:").removePrefix("virtual-config:")
            } + unsaved.filter { NpmBuildInputs.hasUnsavedResolutionInputs(project, workspace.root.path, setOf(it)) }
        }.toSet()
    }

    override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = snapshot(project, file, mutableMapOf())

    private fun snapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<VirtualFile, BuildFingerprint>): BuildSnapshot? {
        return CheckPerformance.measure(CheckPerformance.Stage.NPM_SNAPSHOT) { captureSnapshot(project, file, fingerprints) }
    }

    private fun captureSnapshot(project: Project, file: VirtualFile, fingerprints: MutableMap<VirtualFile, BuildFingerprint>): BuildSnapshot? {
        if (!NpmManifest.supported(file) || !NpmWorkspaces.supportsManifest(project, file)) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val workspace = NpmWorkspaces.resolve(project, file)
        val localNames = workspace.names
        val declarations = NpmManifest.declarations(psi).map { declaration ->
            if (declaration.artifact.name in localNames) declaration.copy(baseline = "") else declaration
        }
        val fingerprint = fingerprints.getOrPut(workspace.root) { NpmBuildInputs.fingerprint(project, workspace) }
        return BuildSnapshot(BuildContextId(id, workspace.root.path, file.path), file.path,
            fingerprint, declarations, NpmBuildInputs.inspectionFingerprint(workspace, fingerprint))
    }
    override fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot): UpdateReport? {
        if (previous.context != current.context || previous.inspectionFingerprint == null ||
            previous.inspectionFingerprint != current.inspectionFingerprint) return null
        val declarations = current.declarations.toSet()
        return report.copy(
            candidates = report.candidates.filter { it.declaration in declarations },
            notices = report.notices.filter { it.declaration in declarations }
        )
    }
    override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
        val file = findFile(project, snapshot.sourceFile) ?: return false
        val workspace = NpmWorkspaces.resolve(project, file)
        return NpmWorkspaces.supportsManifest(project, file) && workspace.root.path == snapshot.context.root && snapshot.fingerprint == NpmBuildInputs.fingerprint(project, workspace)
    }
    override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> = readAction {
        val fingerprints = mutableMapOf<VirtualFile, BuildFingerprint>()
        NpmManifest.files(project, selection).mapNotNull { snapshot(project, it, fingerprints) }
    }
    override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport =
        checkIncrementally(project, snapshot, mode) {}

    override suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                          publish: suspend (InspectionUpdate) -> Unit): UpdateReport {
        val directory = Path.of(snapshot.context.root)
        val context = readAction {
            val file = findFile(project, snapshot.sourceFile) ?: error("package.json is no longer available")
            val root = NpmWorkspaces.resolve(project, file).root
            check(root.path == snapshot.context.root) { "npm workspace changed during the check" }
            NpmBuildInputs.resolutionContext(project, root)
        }
        val metadata = project.service<NpmMetadataService>()
        val policy = readAction { parseDeprecationPolicy(project.service<VersionCheckerSettings>().state.deprecatedDependencies) }
        val expires = AtomicLong(Long.MAX_VALUE)
        val report = metadata.runtimes.withSession(context, { NpmRegistry.resolve(project, directory) }) { runtime ->
            checkNpmVersions(snapshot.declarations, mode, policy, registrySlots,
                metadata = { name ->
                    val lease = CheckPerformance.measure(CheckPerformance.Stage.NPM_METADATA_WAIT) {
                        metadata.cache.getFresh(context, name) {
                            // The shared metadata worker owns a separate lease. Cancelling the
                            // initiating check must not stop runtime setup needed by another caller.
                            metadata.runtimes.withSession(context, { NpmRegistry.resolve(project, directory) }) {
                                NpmRegistry.metadata(it.await(), directory, name)
                            }
                        }
                    }
                    expires.updateAndGet { minOf(it, lease.expiresAt) }
                    lease.metadata
                },
                deprecated = { name, version -> NpmRegistry.deprecated(runtime.await(), directory, name, version) },
                publish = { update -> publish(update.copy(report = update.report.copy(
                    validUntilNanos = expires.get().takeUnless { it == Long.MAX_VALUE }))) })
        }
        return report.copy(validUntilNanos = expires.get().takeUnless { it == Long.MAX_VALUE })
    }
    override fun invalidateMetadata(project: Project) { project.service<NpmMetadataService>().invalidate() }
    override fun hasUnsavedResolutionInputs(project: Project, snapshot: BuildSnapshot, unsavedPaths: Set<String>): Boolean =
        super.hasUnsavedResolutionInputs(project, snapshot, unsavedPaths) ||
            NpmBuildInputs.hasUnsavedResolutionInputs(project, snapshot.context.root, unsavedPaths)

    override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = readAction {
        check(areCurrent(project, reports.keys)) { "npm manifests or configuration changed during the check. Run it again." }
        val edits = mutableListOf<VersionEdit>()
        val skipped = mutableListOf<String>()
        for ((snapshot, report) in reports) {
            val file = findFile(project, snapshot.sourceFile) ?: error("package.json is no longer available")
            val psi = PsiManager.getInstance(project).findFile(file) ?: error("package.json is no longer valid")
            val values = NpmManifest.values(psi)
            for (declaration in snapshot.declarations.filter { it.baseline.isEmpty() }) {
                skipped += "${declaration.id.file}: ${declaration.id.location}: ${NpmManifest.reviewReason(declaration)}"
            }
            for (candidate in report.candidates) {
                val value = values[candidate.declaration.id] ?: error("npm declaration no longer exists")
                check(value.value == candidate.declaration.selector) { "npm selector changed during the check" }
                edits += NpmVersionEdit(value, candidate.replacementSelector,
                    "${file.path}: ${candidate.declaration.id.location} (${candidate.declaration.artifact.name})")
            }
            report.notices.filter { notice -> report.candidates.none { it.declaration.id == notice.declaration.id } }
                .forEach { skipped += "${it.declaration.id.file}: ${it.message}" }
        }
        BulkUpdatePlan(edits, skipped.distinct(), isCurrent = { areCurrent(project, reports.keys) },
            followUp = if (edits.isEmpty()) emptyList() else listOf(NpmUpdateGuidance.message))
    }

    internal fun workspaceDeclarations(project: Project, snapshot: BuildSnapshot): List<Pair<VersionDeclaration, JsonStringLiteral>> =
        NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)).flatMap { file ->
            if (!NpmWorkspaces.supportsManifest(project, file) || NpmWorkspaces.resolve(project, file).root.path != snapshot.context.root) return@flatMap emptyList()
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@flatMap emptyList()
            val values = NpmManifest.values(psi)
            NpmManifest.declarations(psi).mapNotNull { declaration -> values[declaration.id]?.let { declaration to it } }
        }

    /** npm members own their declarations: updating the root alone does not update a member. */
    @JvmOverloads
    internal fun quickFixes(project: Project, snapshot: BuildSnapshot, candidate: UpdateCandidate,
                            value: JsonStringLiteral,
                            declarations: List<Pair<VersionDeclaration, JsonStringLiteral>> = workspaceDeclarations(project, snapshot)): Array<LocalQuickFix> {
        return quickFixes(project, snapshot, candidate, value, workspaceFix(project, snapshot, candidate, declarations))
    }

    /** Local edits belong to their declaration; a prepared workspace action may be shared by a pass. */
    internal fun quickFixes(project: Project, snapshot: BuildSnapshot, candidate: UpdateCandidate,
                            value: JsonStringLiteral, workspaceFix: LocalQuickFix?): Array<LocalQuickFix> {
        val ignoredPolicy = project.service<VersionCheckerSettings>().state.ignoredVersions
        val current = { isCurrent(project, snapshot) && project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy }
        if (workspaceFix == null) return arrayOf(UpdateNpmVersionFix(value, candidate.replacementSelector, isCurrent = current))
        return arrayOf(UpdateNpmVersionFix(value, candidate.replacementSelector, "Update locally to ${candidate.replacementSelector}", current),
            workspaceFix)
    }

    internal fun workspaceFix(project: Project, snapshot: BuildSnapshot, candidate: UpdateCandidate,
                              declarations: List<Pair<VersionDeclaration, JsonStringLiteral>>): LocalQuickFix? {
        val latest = NpmVersion.parse(candidate.version) ?: return null
        val edits = declarations.mapNotNull { (declaration, target) ->
            if (declaration.artifact != candidate.declaration.artifact || declaration.baseline.isEmpty()) return@mapNotNull null
            val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector) ?: return@mapNotNull null
            // Keep newer declarations; a workspace update must never downgrade another package.
            if (selector.baseline >= latest) return@mapNotNull null
            NpmVersionEdit(target, selector.replace(candidate.version), "${declaration.id.file}: ${declaration.id.location}")
        }
        if (edits.mapNotNull { it.element?.containingFile?.virtualFile?.path }.distinct().size < 2) return null
        val ignoredPolicy = project.service<VersionCheckerSettings>().state.ignoredVersions
        return UpdateNpmWorkspaceVersionFix(candidate.declaration.artifact.name, candidate.version, edits) {
            isCurrent(project, snapshot) && project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy
        }
    }
    private fun findFile(project: Project, path: String): VirtualFile? =
        NpmManifest.files(project, BuildSelection(UpdateScope.CURRENT_FILE, path)).singleOrNull()

    private fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>): Boolean {
        val fingerprints = mutableMapOf<VirtualFile, BuildFingerprint>()
        return snapshots.all { snapshot ->
            val file = findFile(project, snapshot.sourceFile) ?: return@all false
            val workspace = NpmWorkspaces.resolve(project, file)
            NpmWorkspaces.supportsManifest(project, file) && workspace.root.path == snapshot.context.root &&
                snapshot.fingerprint == fingerprints.getOrPut(workspace.root) { NpmBuildInputs.fingerprint(project, workspace) }
        }
    }
}
