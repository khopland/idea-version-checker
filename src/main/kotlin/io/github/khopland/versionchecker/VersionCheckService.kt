package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import java.util.concurrent.ConcurrentHashMap

/** Shared background coordinator. Only adapters know how a build resolves or declares versions. */
@Service(Service.Level.PROJECT)
class VersionCheckService(private val project: Project, private val scope: CoroutineScope) {
    private data class ScanToken(val sourceFile: String, val fingerprint: BuildFingerprint, val declarations: List<VersionDeclaration>, val revision: VersionResultCache.Revision)
    private val cache = VersionResultCache()
    private val pending = ConcurrentHashMap<BuildContextId, ScanToken>()
    private val scanMutexes = ConcurrentHashMap<String, Mutex>()
    private val refreshMutex = Mutex()
    private val log = Logger.getInstance(VersionCheckService::class.java)

    internal fun cached(snapshot: BuildSnapshot): UpdateReport? = cache.get(snapshot)

    internal fun updates(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        if (!project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project)) return null
        val entry = cache.get(snapshot)
        if (entry == null) schedule(adapter, snapshot)
        return entry
    }

    internal suspend fun checkNow(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport =
        scanMutexes.computeIfAbsent(adapter.id) { Mutex() }.withLock {
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(readAction { adapter.isCurrent(project, snapshot) }) { "Build files or settings changed. Run the check again." }
            val result = adapter.check(project, snapshot, mode)
            check(result.successful) { result.failure.orEmpty() }
            result
        }

    fun refresh(adapterId: String, currentFile: VirtualFile? = null) {
        val adapter = BuildSystemAdapter.find(adapterId) ?: return
        refresh(listOf(adapter), currentFile)
    }

    internal fun refresh(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile? = null): Job {
        // Invalidate immediately, before an in-flight check can publish a pre-refresh result.
        adapters.forEach { invalidate(it.id, currentFile) }
        return scope.launch(Dispatchers.IO) {
            refreshMutex.withLock { refreshNow(adapters, currentFile) }
        }
    }

    /** A timer must not invalidate work that an editor inspection or manual refresh is already doing. */
    internal suspend fun refreshScheduled() {
        if (!refreshMutex.tryLock()) return
        try {
            if (project.isDisposed || pending.isNotEmpty() || scanMutexes.values.any { it.isLocked } || DumbService.isDumb(project) ||
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
            pending.keys.filter { it.adapterId == adapterId }.forEach { pending.remove(it) }
        } else {
            invalidateSource(adapterId, currentFile.path)
        }
    }

    private fun invalidateSource(adapterId: String, path: String) {
        cache.invalidateSource(adapterId, path)
        pending.entries.filter { it.key.adapterId == adapterId && it.value.sourceFile == path }
            .forEach { (context, token) -> pending.remove(context, token) }
    }

    private suspend fun refreshNow(adapters: List<BuildSystemAdapter>, currentFile: VirtualFile?, scheduled: Boolean = false) {
        if (adapters.isEmpty() || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled) return
        val scopeLabel = if (currentFile == null) "Whole Project" else "Current File"
        withBackgroundProgress(project, "Checking dependency versions: $scopeLabel", cancellable = true) {
            val selection = BuildSelection(if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE, currentFile?.path)
            val unsaved = if (scheduled) readAction {
                val documents = FileDocumentManager.getInstance()
                documents.unsavedDocuments.mapNotNull { documents.getFile(it)?.path }.toSet()
            } else emptySet()
            val scans = adapters.flatMap { adapter ->
                if (adapter.isOffline(project)) {
                    notify("${adapter.displayName} is offline. Disable Work offline to check remote versions.", NotificationType.INFORMATION)
                    emptyList()
                } else try {
                    adapter.discover(project, selection)
                        .filter { snapshot -> snapshot.fingerprint.files.keys.none { it in unsaved } }
                        .map { adapter to it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("${adapter.displayName} version discovery failed", failure)
                    notify("Could not discover ${adapter.displayName} build files. Refresh version checks to retry.", NotificationType.WARNING)
                    emptyList()
                }
            }
            if (scans.isEmpty()) return@withBackgroundProgress
            reportProgressScope(scans.size) { reporter ->
                for ((adapter, snapshot) in scans) {
                    reporter.itemStep("${adapter.displayName}: ${snapshot.sourceFile.substringAfterLast('/')}") {
                        if (scheduled) invalidateSource(adapter.id, snapshot.sourceFile)
                        val token = token(snapshot)
                        pending.putIfAbsent(snapshot.context, token)
                        scan(adapter, snapshot, token)
                    }
                }
            }
        }
        restartInspections()
    }

    private fun token(snapshot: BuildSnapshot) = ScanToken(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, cache.begin(snapshot))

    private fun schedule(adapter: BuildSystemAdapter, snapshot: BuildSnapshot) {
        val token = token(snapshot)
        var claimed = false
        pending.compute(snapshot.context) { _, active ->
            if (active == token) active else { claimed = true; token }
        }
        if (!claimed) return
        scope.launch(Dispatchers.IO) {
            scan(adapter, snapshot, token, showProgress = true)
        }
    }

    private suspend fun scan(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, token: ScanToken, showProgress: Boolean = false) {
        var published = false
        try {
            scanMutexes.computeIfAbsent(adapter.id) { Mutex() }.withLock {
                if (project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project) ||
                    cache.revision(snapshot.context) != token.revision || cache.get(snapshot) != null) return@withLock
                if (!readAction { adapter.isCurrent(project, snapshot) }) return@withLock
                val report = if (showProgress) {
                    withBackgroundProgress(project, "Checking ${adapter.displayName} dependency versions", cancellable = true) {
                        adapter.check(project, snapshot, UpdateMode.MAJOR)
                    }
                } else adapter.check(project, snapshot, UpdateMode.MAJOR)
                if (readAction { !project.isDisposed && adapter.isCurrent(project, snapshot) } && cache.put(snapshot, token.revision, report)) {
                    published = true
                    if (!report.successful) notify("Could not check ${adapter.displayName} versions: ${report.failure}", NotificationType.WARNING)
                }
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
            pending.remove(snapshot.context, token)
            if (published) restartInspections()
        }
    }

    private fun restartInspections() {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) refreshEditorProblems(project, this) }
    }

    private fun notify(message: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("Version Checker", message, type).notify(project)
    }
}
