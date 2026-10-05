package io.github.khopland.versionchecker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        // Invalidate immediately, before an in-flight check can publish a pre-refresh result.
        if (currentFile == null) {
            // A provider refresh must preserve cached results for other build systems.
            cache.invalidateAdapter(adapterId)
            pending.keys.filter { it.adapterId == adapterId }.forEach { pending.remove(it) }
        } else {
            cache.invalidateSource(adapterId, currentFile.path)
            pending.entries.filter { it.key.adapterId == adapterId && it.value.sourceFile == currentFile.path }
                .forEach { (context, token) -> pending.remove(context, token) }
        }
        scope.launch(Dispatchers.IO) {
            val selection = BuildSelection(if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE, currentFile?.path)
            val snapshots = adapter.discover(project, selection)
            if (adapter.isOffline(project)) {
                notify("${adapter.displayName} is offline. Disable Work offline to check remote versions.", NotificationType.INFORMATION)
            } else snapshots.forEach { updates(adapter, it) }
            restartInspections()
        }
    }

    private fun schedule(adapter: BuildSystemAdapter, snapshot: BuildSnapshot) {
        val token = ScanToken(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, cache.begin(snapshot))
        var claimed = false
        pending.compute(snapshot.context) { _, active ->
            if (active == token) active else { claimed = true; token }
        }
        if (!claimed) return
        scope.launch(Dispatchers.IO) {
            try {
                scanMutexes.computeIfAbsent(adapter.id) { Mutex() }.withLock {
                    if (project.isDisposed || adapter.isOffline(project) || cache.revision(snapshot.context) != token.revision) return@withLock
                    if (!readAction { adapter.isCurrent(project, snapshot) }) return@withLock
                    val report = adapter.check(project, snapshot, UpdateMode.MAJOR)
                    if (readAction { !project.isDisposed && adapter.isCurrent(project, snapshot) } && cache.put(snapshot, token.revision, report)) {
                        if (!report.successful) notify("Could not check ${adapter.displayName} versions: ${report.failure}", NotificationType.WARNING)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("${adapter.displayName} version check failed for ${snapshot.sourceFile}", failure)
                if (cache.put(snapshot, token.revision, UpdateReport(failure = failure.message ?: failure.javaClass.simpleName))) {
                    notify("Could not check ${adapter.displayName} versions for ${snapshot.sourceFile}. Check repository settings, then refresh version checks to retry.", NotificationType.WARNING)
                }
            } finally {
                pending.remove(snapshot.context, token)
                restartInspections()
            }
        }
    }

    private fun restartInspections() {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) refreshEditorProblems(project, this) }
    }

    private fun notify(message: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Maven Version Checker")
            .createNotification("Version Checker", message, type).notify(project)
    }
}
