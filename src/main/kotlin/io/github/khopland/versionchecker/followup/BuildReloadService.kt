package io.github.khopland.versionchecker.followup

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.platform.ide.progress.withBackgroundProgress
import io.github.khopland.versionchecker.core.BuildSelection
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateScope
import io.github.khopland.versionchecker.saveBuildInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Optional provider classes remain behind their adapter, including when a plugin is disabled. */
@Service(Service.Level.PROJECT)
internal class BuildReloadService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val notifications = linkedSetOf<Notification>()
    @Volatile private var disposed = false

    fun publish(notification: Notification) {
        synchronized(notifications) {
            if (disposed || project.isDisposed) { notification.expire(); return }
            while (notifications.size >= 32) notifications.first().let { notifications.remove(it); it.expire() }
            notifications.add(notification)
            notification.whenExpired { synchronized(notifications) { notifications.remove(notification) } }
        }
        notification.notify(project)
    }

    override fun dispose() {
        disposed = true
        val retained = synchronized(notifications) { notifications.toList().also { notifications.clear() } }
        retained.forEach(Notification::expire)
    }

    fun reload(adapterId: String) {
        if (disposed || project.isDisposed || !active.add(adapterId)) return
        var provider = adapterId
        var requestAttempted = false
        val job = scope.launch(Dispatchers.IO) {
            try {
                check(TrustedProjects.isProjectTrusted(project)) { "Project is not trusted" }
                val adapter = BuildSystemAdapter.find(adapterId)
                if (adapter == null) {
                    record(BuildReloadOutcome(BuildReloadOutcome.Phase.UNAVAILABLE, provider))
                    return@launch
                }
                provider = adapter.displayName
                withBackgroundProgress(project, "Saving $provider build files", cancellable = true) {
                    saveBuildInputs(project, BuildSelection(UpdateScope.WHOLE_PROJECT), listOf(adapter))
                }
                withBackgroundProgress(project, "Requesting $provider reload", cancellable = true) {
                    if (disposed || project.isDisposed) return@withBackgroundProgress
                    check(TrustedProjects.isProjectTrusted(project)) { "Project trust changed" }
                    // A cancelling background hook may already have requested native import.
                    requestAttempted = true
                    val backgroundResult = adapter.reloadProjectInBackground(project)
                    if (backgroundResult != null) {
                        val phase = if (backgroundResult) BuildReloadOutcome.Phase.COMPLETED
                            else BuildReloadOutcome.Phase.UNAVAILABLE
                        record(BuildReloadOutcome(phase, provider, requestAttempted))
                        return@withBackgroundProgress
                    }
                    requestAttempted = false
                    withContext(Dispatchers.EDT) {
                        if (disposed || project.isDisposed) return@withContext
                        check(TrustedProjects.isProjectTrusted(project)) { "Project trust changed" }
                        requestAttempted = true
                        val phase = if (adapter.reloadProject(project)) BuildReloadOutcome.Phase.REQUESTED
                            else BuildReloadOutcome.Phase.UNAVAILABLE
                        record(BuildReloadOutcome(phase, provider, requestAttempted))
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    record(BuildReloadOutcome(BuildReloadOutcome.Phase.CANCELLED, provider, requestAttempted))
                }
            } catch (_: ProcessCanceledException) {
                withContext(NonCancellable) {
                    record(BuildReloadOutcome(BuildReloadOutcome.Phase.CANCELLED, provider, requestAttempted))
                }
            } catch (_: Exception) {
                record(BuildReloadOutcome(BuildReloadOutcome.Phase.FAILED, provider, requestAttempted))
            }
        }
        // Also release the action if its scope was already cancelled before the coroutine started.
        job.invokeOnCompletion { active.remove(adapterId) }
    }

    private fun record(outcome: BuildReloadOutcome) {
        if (disposed || project.isDisposed) return
        publish(NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("Build reload", outcome.message,
                if (outcome.warning) NotificationType.WARNING else NotificationType.INFORMATION))
    }
}
