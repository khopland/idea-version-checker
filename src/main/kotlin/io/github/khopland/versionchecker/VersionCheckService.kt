package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Project service facade and lifecycle bridge for checks, refreshes, and inspection inputs. */
@Service(Service.Level.PROJECT)
class VersionCheckService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val executor = CheckExecutor(project, scope, ::statusChanged)
    private val inspectionInputs = InspectionInputCache(project, scope, { path ->
        if (!disposed && !project.isDisposed) {
            project.service<FileProblemRefresh>().request(path)
            statusChanged(path)
        }
    })
    private val refreshes = VersionRefreshCoordinator(project, scope, executor, ::invalidate, ::statusChanged) {
        !disposed && !project.isDisposed
    }
    @Volatile private var disposed = false

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { captureSelection() }
        })
        scope.launch(Dispatchers.EDT) { if (!disposed && !project.isDisposed) captureSelection() }
    }

    private fun captureSelection() {
        val selectedPaths = FileEditorManager.getInstance(project).selectedFiles.map { it.path }.toSet()
        executor.select(selectedPaths)
        refreshes.select(selectedPaths)
        if (CheckPerformance.enabled()) project.service<DiagnosticVisibilityTrace>().activated(selectedPaths)
        else project.getServiceIfCreated(DiagnosticVisibilityTrace::class.java)?.activated(emptySet())
    }

    override fun dispose() { disposed = true; inspectionInputs.dispose(); executor.dispose() }

    internal fun pause() = executor.pause()

    internal fun inspectionSnapshot(adapter: BuildSystemAdapter, file: VirtualFile): BuildSnapshot? =
        inspectionInputs.snapshot(adapter, file)

    internal fun queuedChecks(adapterId: String) = executor.queuedChecks(adapterId)

    /** Passive presentation state, including refresh discovery before executor work is queued. */
    internal val hasActiveChecks: Boolean
        get() = !disposed && (refreshes.hasManualRefreshes || executor.isBusy)

    internal fun observations() = executor.observations()

    internal fun cached(snapshot: BuildSnapshot, mode: UpdateMode = UpdateMode.MAJOR): UpdateReport? = executor.cached(snapshot, mode)
    internal fun cachedResult(snapshot: BuildSnapshot, mode: UpdateMode) = executor.cachedResult(snapshot, mode)
    internal fun isCachedResultCurrent(snapshot: BuildSnapshot, mode: UpdateMode, result: VersionResultCache.CachedResult) =
        executor.isCachedResultCurrent(snapshot, mode, result)

    /** Passive, called under a background read action. Retained hints never mean a completed check. */
    internal fun status(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): VersionCheckStatus = executor.status(adapter, snapshot)

    internal fun statusChanged(sourceFile: String? = null) {
        if (!disposed && !project.isDisposed) project.messageBus.syncPublisher(VersionCheckStatusListener.TOPIC).changed(sourceFile)
    }

    internal fun updates(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? = executor.updates(adapter, snapshot)

    internal suspend fun checkNow(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                  reuseCached: Boolean = false): UpdateReport = executor.checkNow(adapter, snapshot, mode, reuseCached)

    fun refresh(adapterId: String, currentFile: VirtualFile? = null) {
        val adapter = BuildSystemAdapter.find(adapterId) ?: return
        refresh(listOf(adapter), currentFile)
    }

    internal fun refresh(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile? = null): Job =
        refreshes.start(adapters, VersionRefreshRequest.Manual(currentFile?.path))

    internal fun refreshSelection(currentFile: VirtualFile?): Job = scope.launch(Dispatchers.IO) {
        val request = VersionRefreshRequest.Manual(currentFile?.path)
        val adapters = readAction { BuildSystemAdapter.matching(project, request.selection) }
        refreshes.start(adapters, request).join()
    }

    internal fun retryFailed(targets: Set<VersionCheckTarget>): Job {
        val files = targets.groupBy { it.adapterId }.mapValues { (_, targets) ->
            if (targets.any { it.sourceFile == null }) null else targets.mapNotNull { it.sourceFile }.toSet()
        }
        return refreshes.start(files.keys.mapNotNull(BuildSystemAdapter::find), VersionRefreshRequest.Retry(files))
    }

    /** A timer must not invalidate editor inspections or manual refreshes already in progress. */
    internal suspend fun refreshScheduled() = refreshes.refreshScheduled()

    private fun invalidate(adapterId: String, sourceFile: String?) {
        inspectionInputs.invalidate(adapterId, sourceFile)
        // A provider refresh must preserve cached results for other build systems.
        executor.invalidate(adapterId, sourceFile)
        statusChanged(sourceFile)
    }

    /** Drop one provider's results without forcing new repository metadata, for example after a selection setting change. */
    internal fun invalidateResults(adapterId: String) = invalidate(adapterId, null)

    internal fun invalidateForPreview(adapter: BuildSystemAdapter, currentFile: VirtualFile?) {
        adapter.invalidateMetadata(project)
        invalidate(adapter.id, currentFile?.path)
    }

    internal fun recheckAfterEdits(adapters: List<BuildSystemAdapter>, selection: BuildSelection): Job =
        refreshes.recheckAfterEdits(adapters, selection)
}
