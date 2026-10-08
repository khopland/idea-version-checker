package io.github.khopland.versionchecker

import com.intellij.codeInsight.FileModificationService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.notification.NotificationAction
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
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
                dialog.disposeIfNeeded()
            }
        }
    }

    fun preview(mode: UpdateMode, updateScope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                currentFile: VirtualFile? = null) {
        if (disposed || !scope.isActive) return
        val adapters = BuildSystemAdapter.matching(project, BuildSelection(updateScope, currentFile?.path))
        if (adapters.isEmpty()) return
        val buildSystems = adapters.joinToString { it.displayName }
        val scopeLabel = updateScope.label
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val plan = withBackgroundProgress(project, "Checking $buildSystems versions: $scopeLabel — ${mode.label}", cancellable = true) {
                    createPlan(mode, updateScope, currentFile, adapters)
                }
                withContext(Dispatchers.EDT) {
                    if (disposed || project.isDisposed) return@withContext
                    if (plan.changes.isEmpty()) {
                        showDialog { BulkUpdateMessageDialog(project, "Version Checker", "No automatic updates available for ${mode.label}." +
                            plan.skipped.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\nNeeds review:\n").orEmpty()) }
                    } else if (showDialog { BulkUpdateDialog(project, mode, scopeLabel, buildSystems, plan) }) {
                        val targets = plan.changes.mapNotNull { it.element }
                        if (!FileModificationService.getInstance().preparePsiElementsForWrite(targets)) return@withContext
                        currentCoroutineContext().ensureActive()
                        if (disposed) return@withContext
                        if (!plan.apply(project)) {
                            showDialog { BulkUpdateMessageDialog(project, "Version Checker", "A build file or configuration changed after the preview. Run the update check again.") }
                            return@withContext
                        }
                        FileDocumentManager.getInstance().saveAllDocuments()
                        notifyVersionUpdates(project, plan.followUp)
                        adapters.forEach { adapter ->
                            project.service<VersionCheckService>().refresh(adapter.id, if (updateScope == UpdateScope.CURRENT_FILE) currentFile else null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("$buildSystems bulk update check failed (${mode.label})", failure)
                if (!disposed && scope.isActive && !project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
                    .createNotification("$buildSystems version update check failed",
                        "${failure.javaClass.simpleName}. No versions were changed. Use Show details for the cause.", NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Show details") {
                        scope.launch {
                            showDialog { BulkUpdateMessageDialog(project, "$buildSystems version update check failed", failure.stackTraceToString()) }
                        }
                    }).notify(project)
            } finally {
                running.set(false)
            }
        }
    }

    internal suspend fun createPlan(mode: UpdateMode, updateScope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                                   currentFile: VirtualFile? = null): BulkUpdatePlan =
        createPlan(mode, updateScope, currentFile,
            readAction { BuildSystemAdapter.matching(project, BuildSelection(updateScope, currentFile?.path)) })

    private suspend fun createPlan(mode: UpdateMode, updateScope: UpdateScope, currentFile: VirtualFile?,
                                   adapters: List<BuildSystemAdapter>): BulkUpdatePlan {
        check(adapters.isNotEmpty()) { "Open a supported build file to update its versions" }
        val service = project.service<VersionCheckService>()
        val plans = adapters.map { adapter ->
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(mode in adapter.capabilities.updateModes) { "${adapter.displayName} does not support ${mode.label}" }
            adapter.invalidateMetadata(project)
            val snapshots = adapter.discover(project, BuildSelection(updateScope, currentFile?.path))
            check(snapshots.isNotEmpty()) { "No supported build files found for ${adapter.displayName}" }
            val reports = snapshots.associateWith { service.checkNow(adapter, it, mode) }
            adapter.prepareUpdates(project, reports)
        }
        return BulkUpdatePlan.combine(plans)
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

private class BulkUpdateDialog(project: Project, mode: UpdateMode, scopeLabel: String,
                               buildSystem: String, private val plan: BulkUpdatePlan) : DialogWrapper(project) {
    init {
        title = "Update $buildSystem versions — $scopeLabel — ${mode.label}"
        setOKButtonText("Update ${plan.changes.size} version declarations")
        init()
    }
    override fun createCenterPanel(): JComponent {
        val reminder = plan.followUp.takeIf { it.isNotEmpty() }?.joinToString("\n\n", postfix = "\n\n").orEmpty()
        val preview = reminder + plan.changes.joinToString("\n\n") { "${it.location}\n${it.expected} → ${it.latest}" } +
            plan.skipped.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\nSkipped — needs review:\n").orEmpty()
        return JBScrollPane(JBTextArea(preview).apply { isEditable = false; lineWrap = true; wrapStyleWord = true })
            .apply { preferredSize = Dimension(760, 440) }
    }
}
