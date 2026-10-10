package io.github.khopland.versionchecker

import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.core.VersionCheckFailureAdvice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

internal data class VersionCheckTarget(val adapterId: String, val sourceFile: String? = null)

/** Retry identities stay complete; displayed native details are bounded and contain no live exceptions. */
internal class VersionCheckFailures {
    private val failed = linkedMapOf<VersionCheckTarget, String>()
    private val samples = linkedMapOf<String, String>()
    private val offline = linkedSetOf<String>()
    val targets: Set<VersionCheckTarget> get() = synchronized(this) { failed.keys.toSet() }
    val isEmpty: Boolean get() = failed.isEmpty() && offline.isEmpty()
    val hasFailures: Boolean get() = failed.isNotEmpty()

    @Synchronized fun add(target: VersionCheckTarget, provider: String, message: String, cause: Exception? = null) {
        failed[target] = provider
        val key = "$provider: ${cause?.javaClass?.simpleName.orEmpty()}: ${message.take(1_000)}"
        if (key in samples || samples.size >= 20) return
        samples[key] = buildString {
            append(provider).append(": ").append(target.sourceFile ?: "Build-file discovery").append('\n')
            append(message.take(4_000))
            cause?.let { append('\n').append(it.stackTraceToString().take(12_000)) }
        }
    }

    fun offline(provider: String) { offline += provider }
    @Synchronized fun remove(target: VersionCheckTarget) { failed.remove(target) }

    val content: String get() = buildString {
        if (failed.isNotEmpty()) {
            val files = failed.keys.count { it.sourceFile != null }
            append(if (files > 0) "Could not check versions in $files build ${if (files == 1) "file" else "files"}. "
                else "Could not discover build files. ")
            append("Successful checks remain available. Check repository settings, then retry failed checks.")
        }
        if (offline.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append("Offline: ").append(offline.joinToString()).append(". Disable Work offline to check remote versions.")
        }
    }

    val details: String get() = buildString {
        failed.entries.groupBy { it.value }.forEach { (provider, entries) ->
            append(provider).append(": ").append(entries.size).append(" failed check(s)\n")
        }
        append("\nRepresentative causes (up to 20):\n\n").append(samples.values.joinToString("\n\n"))
        if (offline.isNotEmpty()) append("\n\nOffline: ").append(offline.joinToString())
    }
}

/** Own actionable notifications so project disposal and plugin unload release their callbacks. */
@Service(Service.Level.PROJECT)
internal class VersionCheckFeedback(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private data class Automatic(val failures: VersionCheckFailures, val notification: Notification)
    private val automatic = mutableMapOf<String, Automatic>() // All mutations on EDT.
    private val stale = mutableMapOf<VersionCheckTarget, Notification>()
    private val active = mutableSetOf<Notification>()
    @Volatile private var disposed = false

    fun report(failures: VersionCheckFailures) {
        if (failures.isEmpty) return
        scope.launch(Dispatchers.EDT) { if (!disposed && !project.isDisposed) publish(failures) }
    }

    fun previewFailure(providers: String, failure: Exception, retry: () -> Unit) {
        val advice = (failure as? VersionCheckFailureAdvice)?.recoveryMessage
            ?: "Could not check dependency versions. Check the build tool and repository settings, then retry."
        val details = failure.stackTraceToString()
        scope.launch(Dispatchers.EDT) {
            if (disposed || project.isDisposed) return@launch
            val title = "$providers version update check failed"
            val notification = group().createNotification(title,
                "$advice No versions were changed. Use Show Details for the cause.", NotificationType.WARNING)
                .setRemoveWhenExpired(true)
            notification.addAction(object : NotificationAction("Retry Preview") {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = !disposed && !project.isDisposed && !notification.isExpired &&
                        project.service<VersionCheckerSettings>().state.enabled
                }
                override fun actionPerformed(event: AnActionEvent, notification: Notification) {
                    if (disposed || project.isDisposed || notification.isExpired || !project.service<VersionCheckerSettings>().state.enabled) return
                    retry()
                    notification.expire()
                }
            })
            notification.addAction(NotificationAction.createSimple("Show Details") {
                if (!disposed && !project.isDisposed && !notification.isExpired) project.service<BulkUpdateService>().showDetails(title, details)
            })
            show(notification)
        }
    }

    fun automaticFailure(target: VersionCheckTarget, provider: String, message: String, cause: Exception?) {
        scope.launch(Dispatchers.EDT) {
            if (disposed || project.isDisposed) return@launch
            val previous = automatic[target.adapterId]?.takeUnless { it.notification.isExpired }
            val failures = previous?.failures ?: VersionCheckFailures()
            failures.add(target, provider, message, cause)
            if (previous != null) previous.notification.setContent(failures.content)
            else automatic[target.adapterId] = Automatic(failures, publish(failures))
        }
    }

    fun checked(target: VersionCheckTarget) {
        scope.launch(Dispatchers.EDT) {
            if (disposed) return@launch
            stale.remove(target)?.expire()
            automatic[target.adapterId]?.let { group ->
                group.failures.remove(target)
                if (group.failures.isEmpty) { automatic.remove(target.adapterId); group.notification.expire() }
                else group.notification.setContent(group.failures.content)
            }
        }
    }

    fun staleFix(target: VersionCheckTarget) {
        scope.launch(Dispatchers.EDT) {
            if (disposed || project.isDisposed || stale[target]?.isExpired == false) return@launch
            val notification = group().createNotification("Version update needs a fresh check",
                "Build files, settings or version results changed. No versions were changed. Refresh this file, then choose the update again.",
                NotificationType.INFORMATION).setRemoveWhenExpired(true)
            if (target.sourceFile != null) notification.addAction(retryAction("Refresh This File") { setOf(target) })
            stale[target] = notification
            show(notification)
        }
    }

    private fun publish(failures: VersionCheckFailures): Notification {
        val notification = group().createNotification("Dependency version checks", failures.content,
            if (failures.hasFailures) NotificationType.WARNING else NotificationType.INFORMATION).setRemoveWhenExpired(true)
        if (failures.hasFailures) {
            notification.addAction(retryAction("Retry Failed Checks") { failures.targets })
            notification.addAction(NotificationAction.createSimple("Show Details") {
                if (!disposed && !project.isDisposed) project.service<BulkUpdateService>()
                    .showDetails("Dependency version check failures", failures.details)
            })
        }
        show(notification)
        return notification
    }

    private fun retryAction(label: String, targets: () -> Set<VersionCheckTarget>) = object : NotificationAction(label) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !disposed && !project.isDisposed &&
                project.service<VersionCheckerSettings>().state.enabled && targets().isNotEmpty()
        }
        override fun actionPerformed(event: AnActionEvent, notification: Notification) {
            if (retry(targets())) notification.expire()
        }
    }

    private fun retry(targets: Set<VersionCheckTarget>): Boolean {
        if (disposed || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled || targets.isEmpty()) return false
        targets.groupBy { it.adapterId }.forEach { (id, files) ->
            val adapter = io.github.khopland.versionchecker.core.BuildSystemAdapter.find(id) ?: return@forEach
            files.forEach { target ->
                saveBuildInputs(project, io.github.khopland.versionchecker.core.BuildSelection(
                    if (target.sourceFile == null) io.github.khopland.versionchecker.core.UpdateScope.WHOLE_PROJECT
                    else io.github.khopland.versionchecker.core.UpdateScope.CURRENT_FILE, target.sourceFile), listOf(adapter))
            }
        }
        project.service<VersionCheckService>().retryFailed(targets)
        return true
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
    private fun show(notification: Notification) {
        active += notification
        notification.whenExpired {
            scope.launch(Dispatchers.EDT) {
                active -= notification
                stale.entries.removeIf { it.value === notification }
                automatic.entries.removeIf { it.value.notification === notification }
            }
        }
        notification.notify(project)
        scope.launch { delay(1.minutes); notification.expire() }
    }

    override fun dispose() {
        disposed = true
        active.toList().forEach(Notification::expire)
        active.clear(); automatic.clear(); stale.clear()
    }
}

internal fun notifyStaleVersionFix(project: Project, adapterId: String, descriptor: ProblemDescriptor) {
    if (project.isDisposed) return
    val file = descriptor.psiElement?.containingFile?.virtualFile?.path
    project.service<VersionCheckFeedback>().staleFix(VersionCheckTarget(adapterId, file))
}
