package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Shared background coordinator. Only adapters know how a build resolves or declares versions. */
@Service(Service.Level.PROJECT)
class VersionCheckService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private data class ScanToken(val sourceFile: String, val fingerprint: BuildFingerprint, val declarations: List<VersionDeclaration>, val revision: VersionResultCache.Revision)
    private data class PendingScan(val token: ScanToken, val job: Job? = null)
    private val cache = VersionResultCache()
    private val pending = ConcurrentHashMap<BuildContextId, PendingScan>()
    private val scanQueues = ConcurrentHashMap<String, CheckQueue>()
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
    }

    override fun dispose() { disposed = true }

    private fun queue(adapterId: String) = scanQueues.computeIfAbsent(adapterId) { CheckQueue { path -> path in selectedPaths } }
    internal fun queuedChecks(adapterId: String) = scanQueues[adapterId]?.waitingCount ?: 0

    internal fun cached(snapshot: BuildSnapshot, mode: UpdateMode = UpdateMode.MAJOR): UpdateReport? = cache.get(snapshot, mode)
    internal fun cachedResult(snapshot: BuildSnapshot, mode: UpdateMode) = cache.getResult(snapshot, mode)
    internal fun isCachedResultCurrent(snapshot: BuildSnapshot, mode: UpdateMode, result: VersionResultCache.CachedResult) =
        cache.isCurrent(snapshot.context, mode, result)

    internal fun updates(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        if (!project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project)) return null
        val entry = cache.get(snapshot)
        if (entry == null && adapter.canCheckInBackground(project, snapshot)) schedule(adapter, snapshot)
        return entry ?: cache.inspectionProgress(adapter, snapshot) ?: cache.retainedInspectionReport(adapter, snapshot)
    }

    internal suspend fun checkNow(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                  reuseCached: Boolean = false): UpdateReport {
        if (reuseCached) {
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(readAction { adapter.isCurrent(project, snapshot) }) { "Build files or settings changed. Run the check again." }
            cache.get(snapshot, mode)?.takeIf { it.successful }?.let { return it }
        }
        val queued = System.nanoTime()
        return queue(adapter.id).withSlot(snapshot.sourceFile, interactive = true) {
            CheckPerformance.record(CheckPerformance.Stage.CHECK_QUEUE, queued)
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(readAction { adapter.isCurrent(project, snapshot) }) { "Build files or settings changed. Run the check again." }
            if (reuseCached) cache.get(snapshot, mode)?.takeIf { it.successful }?.let { return@withSlot it }
            val revision = cache.begin(snapshot)
            runCheck(adapter, snapshot, mode, revision) { result ->
                check(readAction { adapter.isCurrent(project, snapshot) }) { "Build files or settings changed during the check. Run it again." }
                check(cache.revision(snapshot.context) == revision) { "Version checks were refreshed during this check. Run it again." }
                check(cache.put(snapshot, revision, result, mode)) { "Repository results expired during this check. Run it again." }
                if (mode == UpdateMode.MAJOR && !project.isDisposed) project.service<FileProblemRefresh>().request(snapshot.sourceFile)
                if (!result.successful) throw result.failureCause ?: IllegalStateException(result.failure.orEmpty())
                result
            }
        }
    }

    private suspend fun <T> runCheck(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                    revision: VersionResultCache.Revision, finish: suspend (UpdateReport) -> T): T {
        val owner = Any()
        val started = System.nanoTime()
        val first = AtomicBoolean()
        try {
            val report = CheckPerformance.measure(CheckPerformance.Stage.CHECK) {
                if (mode != UpdateMode.MAJOR || !adapter.capabilities.incrementalInspections)
                    return@measure adapter.check(project, snapshot, mode)
                adapter.checkIncrementally(project, snapshot, mode) { update ->
                    val valid = readAction {
                        !disposed && !project.isDisposed && project.service<VersionCheckerSettings>().state.enabled &&
                            !adapter.isOffline(project) && adapter.isCurrent(project, snapshot)
                    }
                    if (valid && cache.putProgress(adapter, snapshot, revision, owner, update)) {
                        val useful = update.report.candidates.size + update.report.notices.size
                        if (useful > 0 && first.compareAndSet(false, true))
                            CheckPerformance.record(CheckPerformance.Stage.FIRST_INSPECTION_RESULT, started, useful)
                        project.service<FileProblemRefresh>().request(snapshot.sourceFile)
                    }
                }
            }
            return finish(report)
        } finally {
            if (cache.clearProgress(snapshot.context, owner) && !disposed && !project.isDisposed)
                project.service<FileProblemRefresh>().request(snapshot.sourceFile)
        }
    }

    fun refresh(adapterId: String, currentFile: VirtualFile? = null) {
        val adapter = BuildSystemAdapter.find(adapterId) ?: return
        refresh(listOf(adapter), currentFile)
    }

    internal fun refresh(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile? = null): Job {
        // Invalidate immediately, before an in-flight check can publish a pre-refresh result.
        adapters.forEach { it.invalidateMetadata(project); invalidate(it.id, currentFile) }
        manualRefreshes.incrementAndGet()
        val job = scope.launch(Dispatchers.IO) {
            if (currentFile == null) refreshMutex.withLock { refreshNow(adapters, null) }
            else refreshNow(adapters, currentFile)
        }
        job.invokeOnCompletion { manualRefreshes.decrementAndGet() }
        return job
    }

    /** A timer must not invalidate work that an editor inspection or manual refresh is already doing. */
    internal suspend fun refreshScheduled() {
        if (!refreshMutex.tryLock()) return
        try {
            if (project.isDisposed || manualRefreshes.get() != 0 || pending.isNotEmpty() || scanQueues.values.any { it.isBusy } || DumbService.isDumb(project) ||
                !project.service<VersionCheckerSettings>().state.enabled) return
            val adapters = readAction { BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
                .filterNot { it.isOffline(project) }
            refreshNow(adapters, null, scheduled = true)
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun invalidate(adapterId: String, currentFile: VirtualFile?) {
        if (currentFile == null) {
            // A provider refresh must preserve cached results for other build systems.
            cache.invalidateAdapter(adapterId)
            pending.keys.filter { it.adapterId == adapterId }.forEach { pending.remove(it)?.job?.cancel() }
        } else {
            invalidateSource(adapterId, currentFile.path)
        }
    }

    internal fun invalidateForPreview(adapter: BuildSystemAdapter, currentFile: VirtualFile?) {
        adapter.invalidateMetadata(project)
        invalidate(adapter.id, currentFile)
    }

    internal fun recheckAfterEdits(adapters: List<BuildSystemAdapter>, selection: BuildSelection): Job = scope.launch(Dispatchers.IO) {
        for (adapter in adapters) {
            if (adapter.isOffline(project)) continue
            try {
                val snapshots = adapter.discover(project, selection)
                readAction { snapshots.forEach { updates(adapter, it) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("${adapter.displayName} version discovery failed after edits", failure)
                notify("Versions were updated, but ${adapter.displayName} could not recheck them. Refresh version checks to retry.", NotificationType.WARNING)
            }
        }
    }

    private fun invalidateSource(adapterId: String, path: String) {
        cache.invalidateSource(adapterId, path)
        pending.entries.filter { it.key.adapterId == adapterId && it.value.token.sourceFile == path }
            .forEach { (context, scan) -> if (pending.remove(context, scan)) scan.job?.cancel() }
    }

    private suspend fun refreshNow(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile?, scheduled: Boolean = false) {
        if (adapters.isEmpty() || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled) return
        val scopeLabel = if (currentFile == null) "Whole Project" else "Current File"
        val affected = mutableSetOf<String>()
        currentFile?.let { affected += it.path }
        withBackgroundProgress(project, "Checking dependency versions: $scopeLabel", cancellable = true) {
            val selection = BuildSelection(if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE, currentFile?.path)
            val unsaved = if (scheduled) readAction {
                val documents = FileDocumentManager.getInstance()
                documents.unsavedDocuments.mapNotNull { documents.getFile(it)?.path }.toSet()
            } else emptySet()
            val scans = adapters.flatMap { adapter ->
                try {
                    val discovered = adapter.discover(project, selection)
                    affected += discovered.map { it.sourceFile }
                    if (adapter.isOffline(project)) {
                        notify("${adapter.displayName} is offline. Disable Work offline to check remote versions.", NotificationType.INFORMATION)
                        emptyList()
                    } else discovered
                        .filter { snapshot -> !scheduled || !readAction { adapter.hasUnsavedResolutionInputs(project, snapshot, unsaved) } }
                        .map { adapter to it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("${adapter.displayName} version discovery failed", failure)
                    notify("Could not discover ${adapter.displayName} build files. Refresh version checks to retry.", NotificationType.WARNING)
                    emptyList()
                }
            }.sortedByDescending { it.second.sourceFile in selectedPaths }
            if (scans.isEmpty()) return@withBackgroundProgress
            if (scheduled) scans.map { it.first }.distinctBy { it.id }.forEach { it.invalidateMetadata(project) }
            reportProgressScope(scans.size) { reporter ->
                for ((adapter, snapshot) in scans) {
                    reporter.itemStep("${adapter.displayName}: ${snapshot.sourceFile.substringAfterLast('/')}") {
                        if (scheduled) invalidateSource(adapter.id, snapshot.sourceFile)
                        val token = token(snapshot)
                        pending.putIfAbsent(snapshot.context, PendingScan(token))
                        if (scan(adapter, snapshot, token, interactive = currentFile != null)) affected -= snapshot.sourceFile
                    }
                }
            }
        }
        affected.forEach { project.service<FileProblemRefresh>().request(it) }
    }

    private fun token(snapshot: BuildSnapshot) = ScanToken(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, cache.begin(snapshot))

    private fun schedule(adapter: BuildSystemAdapter, snapshot: BuildSnapshot) {
        val token = token(snapshot)
        var job: Job? = null
        var superseded: Job? = null
        pending.compute(snapshot.context) { _, active ->
            if (active?.token == token) active else {
                superseded = active?.job
                val scheduled = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    scan(adapter, snapshot, token, showProgress = true)
                }
                job = scheduled
                PendingScan(token, scheduled)
            }
        }
        superseded?.cancel()
        job?.let { scheduled ->
            // A lazy job cancelled before it starts never enters scan's finally block.
            scheduled.invokeOnCompletion {
                pending.computeIfPresent(snapshot.context) { _, active -> if (active.job === scheduled) null else active }
            }
            scheduled.start()
        }
    }

    private suspend fun scan(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, token: ScanToken, showProgress: Boolean = false,
                             interactive: Boolean = false): Boolean {
        var published = false
        val queued = System.nanoTime()
        try {
            queue(adapter.id).withSlot(snapshot.sourceFile, interactive) {
                CheckPerformance.record(CheckPerformance.Stage.CHECK_QUEUE, queued)
                if (project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project) ||
                    cache.revision(snapshot.context) != token.revision || cache.get(snapshot) != null) return@withSlot
                if (!readAction { adapter.canCheckInBackground(project, snapshot) && adapter.isCurrent(project, snapshot) }) return@withSlot
                val finish: suspend (UpdateReport) -> Unit = { report ->
                    if (readAction { !project.isDisposed && adapter.isCurrent(project, snapshot) } && cache.put(snapshot, token.revision, report)) {
                        published = true
                        if (!report.successful) notify("Could not check ${adapter.displayName} versions: ${report.failure}", NotificationType.WARNING)
                    }
                }
                if (showProgress) {
                    withBackgroundProgress(project, "Checking ${adapter.displayName} dependency versions", cancellable = true) {
                        runCheck(adapter, snapshot, UpdateMode.MAJOR, token.revision, finish)
                    }
                } else runCheck(adapter, snapshot, UpdateMode.MAJOR, token.revision, finish)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("${adapter.displayName} version check failed for ${snapshot.sourceFile}", failure)
            if (cache.put(snapshot, token.revision, UpdateReport(failure = failure.message ?: failure.javaClass.simpleName))) {
                published = true
                notify("Could not check ${adapter.displayName} versions for ${snapshot.sourceFile}. Check repository settings, then refresh version checks to retry.", NotificationType.WARNING)
            }
        } finally {
            pending.computeIfPresent(snapshot.context) { _, active -> if (active.token == token) null else active }
            if (published && !project.isDisposed) project.service<FileProblemRefresh>().request(snapshot.sourceFile)
        }
        return published
    }

    private fun notify(message: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("Version Checker", message, type).notify(project)
    }
}
