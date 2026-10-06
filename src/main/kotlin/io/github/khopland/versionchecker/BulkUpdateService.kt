package io.github.khopland.versionchecker

import com.intellij.codeInsight.FileModificationService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.notification.NotificationAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.khopland.versionchecker.core.*
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class BulkUpdateService(private val project: Project, private val scope: CoroutineScope) {
    private val running = AtomicBoolean()
    private val log = Logger.getInstance(BulkUpdateService::class.java)
    fun preview(mode: UpdateMode, updateScope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                currentFile: VirtualFile? = null) {
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
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (plan.changes.isEmpty()) {
                        Messages.showInfoMessage(project, "No automatic updates available for ${mode.label}." +
                            plan.skipped.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\nNeeds review:\n").orEmpty(), "Version Checker")
                    } else if (BulkUpdateDialog(project, mode, scopeLabel, buildSystems, plan).showAndGet()) {
                        val targets = plan.changes.mapNotNull { it.element }
                        if (!FileModificationService.getInstance().preparePsiElementsForWrite(targets)) return@invokeLater
                        if (!plan.apply(project)) {
                            Messages.showWarningDialog(project, "A build file or configuration changed after the preview. Run the update check again.", "Version Checker")
                            return@invokeLater
                        }
                        FileDocumentManager.getInstance().saveAllDocuments()
                        if (plan.followUp.isNotEmpty()) {
                            NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
                                .createNotification("Versions updated", plan.followUp.joinToString("\n"), NotificationType.INFORMATION)
                                .notify(project)
                        }
                        adapters.forEach { adapter ->
                            project.service<VersionCheckService>().refresh(adapter.id, if (updateScope == UpdateScope.CURRENT_FILE) currentFile else null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("$buildSystems bulk update check failed (${mode.label})", failure)
                if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
                    .createNotification("$buildSystems version update check failed",
                        "${failure.javaClass.simpleName}. No versions were changed. Use Show details for the cause.", NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Show details") {
                        Messages.showErrorDialog(project, failure.stackTraceToString(), "$buildSystems version update check failed")
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
            val snapshots = adapter.discover(project, BuildSelection(updateScope, currentFile?.path))
            check(snapshots.isNotEmpty()) { "No supported build files found for ${adapter.displayName}" }
            val reports = snapshots.associateWith { service.checkNow(adapter, it, mode) }
            adapter.prepareUpdates(project, reports)
        }
        return BulkUpdatePlan.combine(plans)
    }

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
