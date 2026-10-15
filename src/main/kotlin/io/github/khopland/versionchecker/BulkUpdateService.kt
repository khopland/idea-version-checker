package io.github.khopland.versionchecker

import com.intellij.codeInsight.FileModificationService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import io.github.khopland.versionchecker.core.*
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.swing.JComponent

internal data class PreparedVersionPreview @JvmOverloads constructor(val plan: BulkUpdatePlan, val checkedAtNanos: Long,
                                          val reusedReports: Int, val totalReports: Int, val coverage: CoverageSummary? = null)

@Service(Service.Level.PROJECT)
class BulkUpdateService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val running = AtomicBoolean()
    private val log = Logger.getInstance(BulkUpdateService::class.java)
    @Volatile private var disposed = false
    private val activeDialogs = mutableSetOf<DialogWrapper>() // Accessed on EDT, including service disposal.

    override fun dispose() {
        disposed = true
        activeDialogs.toList().forEach { if (!it.isDisposed) it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        activeDialogs.clear()
    }

    /** Await the user's response without keeping a modal event loop on the plugin's stack. */
    internal suspend fun showDialog(create: () -> DialogWrapper): Boolean = withContext(Dispatchers.EDT) {
        suspendCancellableCoroutine { continuation ->
            if (disposed || project.isDisposed || !continuation.isActive) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val dialog = create()
            dialog.setModal(false)
            activeDialogs += dialog
            Disposer.register(dialog.disposable) {
                activeDialogs -= dialog
                if (continuation.isActive) continuation.resume(dialog.isOK && !disposed)
            }
            continuation.invokeOnCancellation {
                scope.launch(Dispatchers.EDT) {
                    if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            }
            try {
                dialog.show()
                if (dialog is BulkUpdateDialog && dialog.isShowing) {
                    dialog.contentPanel.paintImmediately(dialog.contentPanel.visibleRect)
                    CheckPerformance.record(CheckPerformance.Stage.PREVIEW_READY, CheckPerformance.current()?.started ?: System.nanoTime(),
                        dialog.model.rowCount)
                }
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
                dialog.disposeIfNeeded()
            }
        }
    }

    fun preview(mode: UpdateMode, updateScope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                currentFile: VirtualFile? = null) = previewWithRefresh(mode, updateScope, currentFile, forceRefresh = false)

    private fun previewWithRefresh(mode: UpdateMode, updateScope: UpdateScope,
                                   currentFile: VirtualFile?, forceRefresh: Boolean) {
        if (disposed || !scope.isActive) return
        if (!running.compareAndSet(false, true)) {
            scope.launch(Dispatchers.EDT) { activeDialogs.lastOrNull()?.takeUnless { it.isDisposed }?.toFront() }
            return
        }
        val interaction = CheckPerformance.current() ?: CheckPerformance.start(CheckPerformance.Stage.PREVIEW_INVOKED)
        scope.launch(Dispatchers.IO + CheckPerformance.context(interaction)) {
            var buildSystems = "Dependency"
            var selectedMode = mode
            var selectedScope = updateScope
            try {
                var refreshRequired = forceRefresh
                var nextInteraction = interaction
                while (isActive && !disposed) {
                    val recheck = CheckPerformance.traced(nextInteraction) {
                        var matching = readAction { BuildSystemAdapter.matching(project, BuildSelection(selectedScope, currentFile?.path)) }
                        if (matching.isEmpty()) return@traced false
                        buildSystems = matching.joinToString { it.displayName }
                        val currentAvailable = currentFile != null && readAction {
                            BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, currentFile.path)).isNotEmpty()
                        }
                        val platformAvailable = currentFile != null && readAction {
                            BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.MAVEN_PLATFORM, currentFile.path)).isNotEmpty()
                        }
                        val prepared = withBackgroundProgress(project, "Checking versions: ${selectedScope.label} — ${selectedMode.label}", cancellable = true) {
                            saveBuildInputs(project, BuildSelection(selectedScope, currentFile?.path), matching)
                            CheckPerformance.measure(CheckPerformance.Stage.PREVIEW_PREPARATION) {
                                preparePreview(selectedMode, selectedScope, currentFile, matching, refreshRequired)
                            }
                        }
                        var recheck = false
                        withContext(Dispatchers.EDT) {
                            if (disposed || project.isDisposed) return@withContext
                            var dialog: BulkUpdateDialog? = null
                            var appliedInDialog = false
                            val accepted = showDialog {
                                BulkUpdateDialog(project, selectedMode, selectedScope.label, matching.joinToString { it.displayName }, prepared.plan).also {
                                    dialog = it; it.configure(prepared, currentAvailable, platformAvailable)
                                    val review = it
                                    val requests = PreviewRequestController(scope,
                                        isAvailable = { !disposed && !project.isDisposed && !review.isDisposed },
                                        load = { request -> loadPreviewRequest(request, currentFile) },
                                        onPrepared = { request, result ->
                                            matching = result.adapters
                                            review.replacePrepared(result.preview, request.mode, request.updateScope,
                                                result.adapters.joinToString { it.displayName }, result.currentAvailable, result.platformAvailable)
                                        },
                                        onFailure = review::recheckFailed)
                                    Disposer.register(review.disposable, requests)
                                    review.onApplyRequested = apply@{ plan, trace ->
                                        var changed = false
                                        try {
                                            if (disposed || project.isDisposed || plan.changes.isEmpty()) return@apply
                                            if (!FileModificationService.getInstance().preparePsiElementsForWrite(plan.changes.mapNotNull { it.element })) return@apply
                                            if (disposed || review.isDisposed) return@apply
                                            if (!plan.apply(project, trace)) {
                                                review.refreshAction.actionPerformed(java.awt.event.ActionEvent(review, 0, "Refresh"))
                                                return@apply
                                            }
                                            changed = true
                                            saveChangedVersionFiles(plan)
                                            notifyVersionUpdates(project, plan.followUp, buildSystems = plan.changes.mapNotNull { it.buildSystemId }.toSet())
                                            project.service<VersionCheckService>().recheckAfterEdits(matching,
                                                BuildSelection(selectedScope, currentFile?.path))
                                            appliedInDialog = true
                                            review.close(DialogWrapper.OK_EXIT_CODE)
                                        } catch (cancelled: ProcessCanceledException) {
                                            review.recheckFailed("Update cancelled.")
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            log.warn("Could not apply reviewed updates", failure)
                                            review.recheckFailed(if (changed)
                                                "Versions changed in the editor, but follow-up work failed. Save the changed files and refresh the review."
                                                else "Could not apply updates. Review the build files before retrying.")
                                        }
                                    }
                                    review.onRecheckRequested = { mode, updateScope, forceRefresh, trace ->
                                        selectedMode = mode
                                        selectedScope = updateScope
                                        requests.request(PreviewRecheckRequest(mode, updateScope, forceRefresh, trace))
                                    }
                                }
                            }
                            val shown = dialog ?: return@withContext
                            if (!accepted && shown.exitCode == BulkUpdateDialog.RECHECK_EXIT_CODE && !disposed) {
                                selectedMode = shown.modeSelector.selectedItem as UpdateMode
                                selectedScope = shown.scopeSelector.selectedItem as UpdateScope
                                refreshRequired = shown.refreshRequested
                                nextInteraction = shown.recheckInteraction
                                recheck = true
                            } else if (accepted && !appliedInDialog) {
                                val plan = shown.selectedPlan()
                                if (plan.changes.isEmpty()) return@withContext
                                val targets = plan.changes.mapNotNull { it.element }
                                if (!FileModificationService.getInstance().preparePsiElementsForWrite(targets)) return@withContext
                                currentCoroutineContext().ensureActive()
                                if (disposed) return@withContext
                                if (!plan.apply(project, shown.applyInteraction)) {
                                    showDialog { BulkUpdateMessageDialog(project, "Version Checker", "Build files, settings or version results changed after the preview. Review refreshed results before applying.") }
                                    recheck = !disposed
                                    refreshRequired = true
                                    nextInteraction = if (recheck) CheckPerformance.start(CheckPerformance.Stage.PREVIEW_INVOKED) else null
                                    return@withContext
                                }
                                saveChangedVersionFiles(plan)
                                notifyVersionUpdates(project, plan.followUp, buildSystems = plan.changes.mapNotNull { it.buildSystemId }.toSet())
                                project.service<VersionCheckService>().recheckAfterEdits(matching,
                                    BuildSelection(selectedScope, currentFile?.path))
                            }
                        }
                        recheck
                    }
                    if (!recheck) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("$buildSystems bulk update check failed (${mode.label})", failure)
                if (!disposed && scope.isActive && !project.isDisposed) {
                    val retryMode = selectedMode
                    val retryScope = selectedScope
                    project.service<VersionCheckFeedback>().previewFailure(buildSystems, failure) {
                        previewWithRefresh(retryMode, retryScope, currentFile, forceRefresh = true)
                    }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun loadPreviewRequest(request: PreviewRecheckRequest, currentFile: VirtualFile?): PreviewRecheckResult {
        val selection = BuildSelection(request.updateScope, currentFile?.path)
        val adapters = readAction { BuildSystemAdapter.matching(project, selection) }
        val prepared = withBackgroundProgress(project,
            "Checking versions: ${request.updateScope.label} — ${request.mode.label}", cancellable = true) {
            saveBuildInputs(project, selection, adapters)
            preparePreview(request.mode, request.updateScope, currentFile, adapters, request.forceRefresh)
        }
        val current = currentFile != null && readAction {
            BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, currentFile.path)).isNotEmpty()
        }
        val platform = currentFile != null && readAction {
            BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.MAVEN_PLATFORM, currentFile.path)).isNotEmpty()
        }
        return PreviewRecheckResult(prepared, adapters, current, platform)
    }

    internal suspend fun createPlan(mode: UpdateMode, updateScope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                                   currentFile: VirtualFile? = null, forceRefresh: Boolean = false): BulkUpdatePlan =
        preparePreview(mode, updateScope, currentFile,
            readAction { BuildSystemAdapter.matching(project, BuildSelection(updateScope, currentFile?.path)) }, forceRefresh).plan

    internal fun showDetails(title: String, message: String) {
        if (disposed || project.isDisposed) return
        val safeMessage = DiagnosticSanitizer.sanitize(message)
        scope.launch { showDialog { BulkUpdateMessageDialog(project, title, safeMessage) } }
    }

    internal fun openDeclaration(edit: VersionEdit) {
        scope.launch(Dispatchers.IO) {
            val target = readAction {
                edit.element?.let { element -> element.containingFile?.virtualFile?.let { file ->
                    file to (edit.documentChange?.range?.startOffset ?: element.textOffset)
                } }
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (!disposed && !project.isDisposed && target.first.isValid)
                    com.intellij.openapi.fileEditor.OpenFileDescriptor(project, target.first, target.second).navigate(true)
            }
        }
    }

    internal fun openReviewSource(source: VersionReviewSource) {
        scope.launch(Dispatchers.IO) {
            val file = source.findFile()
            withContext(Dispatchers.EDT) {
                if (disposed || project.isDisposed) return@withContext
                if (file == null || !file.isValid || file.isDirectory) {
                    showDetails("Source unavailable", "The review source is no longer available. Refresh the review to locate its current declaration.")
                } else {
                    val length = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.textLength ?: 0
                    com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, source.navigationOffset(length)).navigate(true)
                }
            }
        }
    }

    internal fun previewEdit(edit: VersionEdit) {
        scope.launch(Dispatchers.IO) {
            val preview = readAction { edit.preview() }
            withContext(Dispatchers.EDT) {
                if (disposed || project.isDisposed) return@withContext
                if (preview == null) {
                    showDialog { BulkUpdateMessageDialog(project, "Refresh the review", "The declaration changed or is unavailable. Refresh the review before opening its diff.") }
                } else {
                    val factory = com.intellij.diff.DiffContentFactory.getInstance()
                    val request = com.intellij.diff.requests.SimpleDiffRequest(
                        edit.location, factory.create(project, preview.before, preview.file.fileType),
                        factory.create(project, preview.after, preview.file.fileType), "Current", "Proposed")
                    request.putUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY, true)
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
                }
            }
        }
    }

    internal suspend fun preparePreview(mode: UpdateMode, updateScope: UpdateScope, currentFile: VirtualFile?,
                                        adapters: List<BuildSystemAdapter>, forceRefresh: Boolean): PreparedVersionPreview {
        check(adapters.isNotEmpty()) { "Open a supported build file to update its versions" }
        val service = project.service<VersionCheckService>()
        val results = mutableListOf<Pair<BuildSnapshot, VersionResultCache.CachedResult>>()
        val coverage = linkedMapOf<Pair<String, DeclarationId>, DeclarationCoverage>()
        val expectedCoverage = linkedSetOf<Pair<String, DeclarationId>>()
        var coverageKnown = true
        val ignoredPolicy = project.service<VersionCheckerSettings>().state.ignoredVersions
        var reused = 0
        val plans = adapters.map { adapter ->
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(mode in adapter.capabilities.updateModes) { "${adapter.displayName} does not support ${mode.label}" }
            if (forceRefresh) service.invalidateForPreview(adapter, if (updateScope == UpdateScope.CURRENT_FILE) currentFile else null)
            val snapshots = adapter.discover(project, BuildSelection(updateScope, currentFile?.path))
            check(snapshots.isNotEmpty()) { "No supported build files found for ${adapter.displayName}" }
            val unchecked = snapshots.filter { service.cached(it, mode)?.successful != true }
            val reports = adapter.withScan(project, unchecked, mode) {
                snapshots.associateWith { snapshot ->
                    if (service.cached(snapshot, mode)?.successful == true) reused++
                    val report = service.checkNow(adapter, snapshot, mode, reuseCached = true)
                    val result = service.cachedResult(snapshot, mode) ?: error("Repository results expired. Refresh version checks to retry.")
                    check(result.report === report) { "Version results changed during preparation. Refresh the preview." }
                    results += snapshot to result
                    report.withoutIgnored(adapter.id, project.service<VersionCheckerSettings>().state).also { filtered ->
                        val physicalIds = snapshot.declarations.associate { it.id to (it.sourceId ?: it.id) }
                        fun key(id: DeclarationId) = adapter.id to (physicalIds[id] ?: id)
                        expectedCoverage += snapshot.declarationIds().map(::key)
                        if (filtered.coverage == null) coverageKnown = false else filtered.coverage.forEach { (id, outcome) ->
                            val key = key(id)
                            val previous = coverage[key]
                            fun rank(outcome: DeclarationOutcome) = when (outcome) {
                                DeclarationOutcome.FAILED -> 5
                                DeclarationOutcome.MANUAL_REVIEW -> 4
                                DeclarationOutcome.CHECKED -> 3
                                DeclarationOutcome.IGNORED -> 2
                                DeclarationOutcome.SKIPPED -> 1
                            }
                            if (previous == null || rank(outcome.outcome) > rank(previous.outcome)) coverage[key] = outcome
                        }
                    }
                }
            }
            CheckPerformance.measure(CheckPerformance.Stage.PREVIEW_PLAN, reports.size) { adapter.prepareUpdates(project, reports) }
        }
        val plan = BulkUpdatePlan.combine(plans).guardedBy {
            project.service<VersionCheckerSettings>().state.enabled &&
                project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy &&
                results.all { (snapshot, result) -> service.isCachedResultCurrent(snapshot, mode, result) }
        }
        return PreparedVersionPreview(plan, results.minOf { it.second.checkedAtNanos }, reused, results.size,
            if (coverageKnown) CoverageSummary.from(coverage, expectedCoverage) else null)
    }

}

private class BulkUpdateMessageDialog(project: Project, title: String, private val message: String) : DialogWrapper(project) {
    init {
        this.title = title
        init()
    }
    override fun createActions() = arrayOf(okAction)
    override fun createCenterPanel(): JComponent = JBScrollPane(JBTextArea(message).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
    }).apply { preferredSize = Dimension(620, 260) }
}

