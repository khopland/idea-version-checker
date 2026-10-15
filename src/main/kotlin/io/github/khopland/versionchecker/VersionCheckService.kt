package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Shared background coordinator. Only adapters know how a build resolves or declares versions. */
@Service(Service.Level.PROJECT)
class VersionCheckService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val executor = CheckExecutor(project, scope, ::statusChanged)
    private val inspectionInputs = InspectionInputCache(project, scope, { path ->
        if (!disposed && !project.isDisposed) {
            project.service<FileProblemRefresh>().request(path)
            statusChanged(path)
        }
    })
    private val refreshMutex = Mutex()
    private val manualRefreshes = AtomicInteger()
    @Volatile private var selectedPaths = emptySet<String>()
    @Volatile private var disposed = false
    private val log = Logger.getInstance(VersionCheckService::class.java)

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { captureSelection() }
        })
        scope.launch(Dispatchers.EDT) { if (!disposed && !project.isDisposed) captureSelection() }
    }

    private fun captureSelection() {
        selectedPaths = FileEditorManager.getInstance(project).selectedFiles.map { it.path }.toSet()
        executor.select(selectedPaths)
        if (CheckPerformance.enabled()) project.service<DiagnosticVisibilityTrace>().activated(selectedPaths)
        else project.getServiceIfCreated(DiagnosticVisibilityTrace::class.java)?.activated(emptySet())
    }

    override fun dispose() { disposed = true; inspectionInputs.dispose(); executor.dispose() }

    internal fun pause() = executor.pause()

    internal fun inspectionSnapshot(adapter: BuildSystemAdapter, file: VirtualFile): BuildSnapshot? =
        inspectionInputs.snapshot(adapter, file)

    internal fun queuedChecks(adapterId: String) = executor.queuedChecks(adapterId)

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
        startRefresh(adapters, currentFile)

    internal fun refreshSelection(currentFile: VirtualFile?): Job = scope.launch(Dispatchers.IO) {
        val selection = BuildSelection(if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE,
            currentFile?.path)
        val adapters = readAction { BuildSystemAdapter.matching(project, selection) }
        startRefresh(adapters, currentFile).join()
    }

    internal fun retryFailed(targets: Set<VersionCheckTarget>): Job {
        val files = targets.groupBy { it.adapterId }.mapValues { (_, targets) ->
            if (targets.any { it.sourceFile == null }) null else targets.mapNotNull { it.sourceFile }.toSet()
        }
        return startRefresh(files.keys.mapNotNull(BuildSystemAdapter::find), null, files)
    }

    private fun startRefresh(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile?,
                             retryFiles: Map<String, Set<String>?>? = null): Job {
        // Invalidate immediately, before an in-flight check can publish a pre-refresh result.
        adapters.forEach { adapter ->
            adapter.invalidateMetadata(project)
            val files = retryFiles?.get(adapter.id)
            if (files == null) invalidate(adapter.id, currentFile)
            else files.forEach { invalidateSource(adapter.id, it); statusChanged(it) }
        }
        manualRefreshes.incrementAndGet()
        val job = scope.launch(Dispatchers.IO) {
            if (currentFile == null && retryFiles == null) refreshMutex.withLock { refreshNow(adapters, null) }
            else refreshNow(adapters, currentFile, retryFiles = retryFiles)
        }
        job.invokeOnCompletion { manualRefreshes.decrementAndGet() }
        return job
    }

    /** A timer must not invalidate work that an editor inspection or manual refresh is already doing. */
    internal suspend fun refreshScheduled() {
        if (!refreshMutex.tryLock()) return
        try {
            if (project.isDisposed || manualRefreshes.get() != 0 || executor.isBusy || DumbService.isDumb(project) ||
                !project.service<VersionCheckerSettings>().state.enabled) return
            val adapters = readAction { BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
                .filterNot { it.isOffline(project) }
            refreshNow(adapters, null, scheduled = true)
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun invalidate(adapterId: String, currentFile: VirtualFile?) {
        inspectionInputs.invalidate(adapterId, currentFile?.path)
        // A provider refresh must preserve cached results for other build systems.
        executor.invalidate(adapterId, currentFile?.path)
        statusChanged(currentFile?.path)
    }

    /** Drop one provider's results without forcing new repository metadata, for example after a selection setting change. */
    internal fun invalidateResults(adapterId: String) = invalidate(adapterId, null)

    internal fun invalidateForPreview(adapter: BuildSystemAdapter, currentFile: VirtualFile?) {
        adapter.invalidateMetadata(project)
        invalidate(adapter.id, currentFile)
    }

    internal fun recheckAfterEdits(adapters: List<BuildSystemAdapter>, selection: BuildSelection): Job = scope.launch(Dispatchers.IO) {
        val failures = VersionCheckFailures()
        try {
            for (adapter in adapters) {
                if (adapter.isOffline(project)) continue
                try {
                    val snapshots = adapter.discover(project, selection)
                    readAction { snapshots.forEach { updates(adapter, it) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("${adapter.displayName} version discovery failed after edits", failure)
                    failures.add(VersionCheckTarget(adapter.id, selection.currentFile), adapter.displayName,
                        "Versions were updated, but build files could not be rediscovered.", failure)
                }
            }
        } finally {
            if (!disposed && !project.isDisposed && !failures.isEmpty) project.service<VersionCheckFeedback>().report(failures)
        }
    }

    private fun invalidateSource(adapterId: String, path: String) = executor.invalidate(adapterId, path)

    private suspend fun refreshNow(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile?, scheduled: Boolean = false,
                                   retryFiles: Map<String, Set<String>?>? = null) {
        if (adapters.isEmpty() || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled) return
        val scopeLabel = when {
            retryFiles != null -> "Failed Checks"
            currentFile == null -> "Whole Project"
            else -> "Current File"
        }
        val affected = mutableSetOf<String>()
        val failures = VersionCheckFailures()
        currentFile?.let { affected += it.path }
        try {
            withBackgroundProgress(project, "Checking dependency versions: $scopeLabel", cancellable = true) {
                val selection = BuildSelection(
                    scope = if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE,
                    currentFile = currentFile?.path
                )
                val unsaved = if (scheduled) readAction {
                    val documents = FileDocumentManager.getInstance()
                    documents.unsavedDocuments.mapNotNull { documents.getFile(it)?.path }.toSet()
                } else emptySet()
                val scans = adapters.flatMap { adapter ->
                    try {
                        if (!scheduled) {
                            val requested = retryFiles?.get(adapter.id)
                            if (requested == null) saveBuildInputs(project, selection, listOf(adapter))
                            else for (path in requested) {
                                saveBuildInputs(project, BuildSelection(UpdateScope.CURRENT_FILE, path), listOf(adapter))
                            }
                        }
                        val discovered = discoverRefreshSnapshots(adapter, selection, retryFiles?.get(adapter.id), failures)
                        affected += discovered.map { it.sourceFile }
                        if (adapter.isOffline(project)) {
                            failures.offline(adapter.displayName)
                            return@flatMap emptyList()
                        }
                        val eligible = if (scheduled) discovered.filter { snapshot ->
                            !readAction { adapter.hasUnsavedResolutionInputs(project, snapshot, unsaved) }
                        } else discovered
                        eligible.map { adapter to it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cancelled: ProcessCanceledException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        log.warn("${adapter.displayName} version discovery failed", failure)
                        val targets = retryFiles?.get(adapter.id)?.map { VersionCheckTarget(adapter.id, it) }
                            ?: listOf(VersionCheckTarget(adapter.id, currentFile?.path))
                        targets.forEach { failures.add(it, adapter.displayName, "Could not discover build files.", failure) }
                        emptyList()
                    }
                }.sortedByDescending { it.second.sourceFile in selectedPaths }
                if (scans.isEmpty()) return@withBackgroundProgress
                if (scheduled) scans.map { it.first }.distinctBy { it.id }.forEach { it.invalidateMetadata(project) }
                withBuildScans(project, scans, UpdateMode.MAJOR) {
                    reportProgressScope(scans.size) { reporter ->
                        for ((adapter, snapshot) in scans) {
                            reporter.itemStep("${adapter.displayName}: ${snapshot.sourceFile.substringAfterLast('/')}") {
                                if (scheduled) invalidateSource(adapter.id, snapshot.sourceFile)
                                if (executor.scanRefresh(adapter, snapshot, interactive = currentFile != null || retryFiles != null, failures = failures))
                                    affected -= snapshot.sourceFile
                            }
                        }
                    }
                }
            }
        } finally {
            if (!disposed && !project.isDisposed) {
                if (!failures.isEmpty) project.service<VersionCheckFeedback>().report(failures)
                affected.forEach { project.service<FileProblemRefresh>().request(it) }
            }
        }
    }

    private suspend fun discoverRefreshSnapshots(
        adapter: BuildSystemAdapter,
        selection: BuildSelection,
        requestedFiles: Set<String>?,
        failures: VersionCheckFailures
    ): List<BuildSnapshot> {
        val snapshots = adapter.discover(project, selection)
            .filter { requestedFiles == null || it.sourceFile in requestedFiles }
        val missingFiles = requestedFiles?.minus(snapshots.map { it.sourceFile }.toSet()).orEmpty()
        for (path in missingFiles) {
            failures.add(
                VersionCheckTarget(adapter.id, path),
                adapter.displayName,
                "Build file is no longer available or supported. Import or reload the build project before retrying."
            )
        }
        return snapshots
    }

}
