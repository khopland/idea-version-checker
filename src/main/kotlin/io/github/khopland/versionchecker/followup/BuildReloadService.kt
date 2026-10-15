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
        scope.launch(Dispatchers.IO) {
            try {
                check(TrustedProjects.isProjectTrusted(project)) { "Project is not trusted" }
                val adapter = BuildSystemAdapter.find(adapterId) ?: return@launch
                withBackgroundProgress(project, "Saving and reloading ${adapter.displayName} projects", cancellable = true) {
                    saveBuildInputs(project, BuildSelection(UpdateScope.WHOLE_PROJECT), listOf(adapter))
                    withContext(Dispatchers.EDT) {
                        if (!disposed && !project.isDisposed && TrustedProjects.isProjectTrusted(project)) {
                            check(adapter.reloadProject(project)) { "Provider reload is unavailable" }
                            result("${adapter.displayName} reload requested. Check the IDE build synchronization result.", false)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation never implies a successful reload.
            } catch (_: ProcessCanceledException) {
                // Native IDE cancellation is not a failed reload.
            } catch (_: Exception) {
                result("Could not request build reload. Trust the project, save its build files and use the IDE build tool window to reload.", true)
            } finally {
                active.remove(adapterId)
            }
        }
    }

    private fun result(message: String, failed: Boolean) {
        if (!disposed && !project.isDisposed) publish(NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification("Build reload", message,
                if (failed) NotificationType.WARNING else NotificationType.INFORMATION))
    }
}
