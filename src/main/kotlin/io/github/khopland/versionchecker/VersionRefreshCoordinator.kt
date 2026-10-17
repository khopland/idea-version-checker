package io.github.khopland.versionchecker

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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

/** Owns refresh discovery and scheduling; check execution stays in [CheckExecutor]. */
internal class VersionRefreshCoordinator(
    private val project: Project,
    private val scope: CoroutineScope,
    private val executor: CheckExecutor,
    private val invalidate: (String, String?) -> Unit,
    private val statusChanged: (String?) -> Unit,
    private val isAvailable: () -> Boolean
) {
    private val refreshMutex = Mutex()
    private val manualRefreshes = AtomicInteger()
    private val log = Logger.getInstance(VersionRefreshCoordinator::class.java)
    @Volatile private var selectedPaths = emptySet<String>()

    val hasManualRefreshes: Boolean get() = manualRefreshes.get() > 0
    fun select(paths: Set<String>) { selectedPaths = paths }

    fun start(adapters: List<BuildSystemAdapter>, request: VersionRefreshRequest.UserRequested): Job {
        // Invalidate immediately, before an in-flight check can publish a pre-refresh result.
        adapters.forEach { adapter ->
            adapter.invalidateMetadata(project)
            val files = request.requestedFiles(adapter.id)
            if (files == null) invalidate(adapter.id, request.selection.currentFile)
            else files.forEach { invalidateSource(adapter.id, it); statusChanged(it) }
        }
        manualRefreshes.incrementAndGet()
        statusChanged(null)
        val job = scope.launch(Dispatchers.IO) {
            if (request is VersionRefreshRequest.Manual && request.currentFile == null)
                refreshMutex.withLock { refreshNow(adapters, request) }
            else refreshNow(adapters, request)
        }
        job.invokeOnCompletion {
            manualRefreshes.decrementAndGet()
            statusChanged(null)
        }
        return job
    }

    /** A timer must not invalidate work that an editor inspection or manual refresh is already doing. */
    suspend fun refreshScheduled() {
        if (!refreshMutex.tryLock()) return
        try {
            if (!isAvailable() || manualRefreshes.get() != 0 || executor.isBusy || DumbService.isDumb(project) ||
                !project.service<VersionCheckerSettings>().state.enabled) return
            val adapters = readAction { BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
                .filterNot { it.isOffline(project) }
            refreshNow(adapters, VersionRefreshRequest.Scheduled)
        } finally {
            refreshMutex.unlock()
        }
    }

    fun recheckAfterEdits(adapters: List<BuildSystemAdapter>, selection: BuildSelection): Job = scope.launch(Dispatchers.IO) {
        val failures = VersionCheckFailures()
        try {
            for (adapter in adapters) {
                if (adapter.isOffline(project)) continue
                try {
                    val snapshots = adapter.discover(project, selection)
                    readAction { snapshots.forEach { executor.updates(adapter, it) } }
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
            if (isAvailable() && !failures.isEmpty) project.service<VersionCheckFeedback>().report(failures)
        }
    }

    private fun invalidateSource(adapterId: String, path: String) = executor.invalidate(adapterId, path)

    private suspend fun refreshNow(adapters: List<BuildSystemAdapter>, request: VersionRefreshRequest) {
        if (adapters.isEmpty() || !isAvailable() || !project.service<VersionCheckerSettings>().state.enabled) return
        val scheduled = request == VersionRefreshRequest.Scheduled
        val selection = request.selection
        val affected = mutableSetOf<String>()
        val failures = VersionCheckFailures()
        selection.currentFile?.let { affected += it }
        try {
            withBackgroundProgress(project, "Checking dependency versions: ${request.label}", cancellable = true) {
                val unsaved = if (scheduled) readAction {
                    val documents = FileDocumentManager.getInstance()
                    documents.unsavedDocuments.mapNotNull { documents.getFile(it)?.path }.toSet()
                } else emptySet()
                val scans = adapters.flatMap { adapter ->
                    try {
                        if (!scheduled) {
                            val requested = request.requestedFiles(adapter.id)
                            if (requested == null) saveBuildInputs(project, selection, listOf(adapter))
                            else for (path in requested) {
                                saveBuildInputs(project, BuildSelection(UpdateScope.CURRENT_FILE, path), listOf(adapter))
                            }
                        }
                        val discovered = discoverRefreshSnapshots(adapter, selection, request.requestedFiles(adapter.id), failures)
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
                        val targets = request.requestedFiles(adapter.id)?.map { VersionCheckTarget(adapter.id, it) }
                            ?: listOf(VersionCheckTarget(adapter.id, selection.currentFile))
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
                                if (executor.scanRefresh(adapter, snapshot, interactive = request.interactive, failures = failures))
                                    affected -= snapshot.sourceFile
                            }
                        }
                    }
                }
            }
        } finally {
            if (isAvailable()) {
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
