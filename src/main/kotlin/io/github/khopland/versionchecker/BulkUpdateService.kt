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
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class BulkUpdateService(private val project: Project, private val scope: CoroutineScope) {
    private val running = AtomicBoolean()
    private val log = Logger.getInstance(BulkUpdateService::class.java)
    fun preview(mode: UpdateMode, updateScope: MavenUpdateScope = MavenUpdateScope.WHOLE_PROJECT,
                artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY, currentPom: VirtualFile? = null) {
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val plan = withBackgroundProgress(project, "Checking Maven ${artifactKind.label}: ${updateScope.label} — ${mode.label}", cancellable = true) {
                    createPlan(mode, updateScope, artifactKind, currentPom)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (plan.changes.isEmpty()) {
                        Messages.showInfoMessage(project, "No automatic updates available for ${mode.label}." +
                            plan.skipped.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\nNeeds review:\n").orEmpty(), "Maven Version Checker")
                    } else if (BulkUpdateDialog(project, mode, updateScope, artifactKind, plan).showAndGet()) {
                        val targets = plan.changes.mapNotNull { it.pointer.element }
                        if (!FileModificationService.getInstance().preparePsiElementsForWrite(targets)) return@invokeLater
                        if (!plan.apply(project)) {
                            Messages.showWarningDialog(project, "A version changed after the preview. Run the update check again.", "Maven Version Checker")
                            return@invokeLater
                        }
                        FileDocumentManager.getInstance().saveAllDocuments()
                        project.service<VersionCheckService>().refresh(if (updateScope == MavenUpdateScope.CURRENT_POM) currentPom else null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Maven bulk update check failed (${mode.label})", failure)
                if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Maven Version Checker")
                    .createNotification("Maven ${artifactKind.label} update check failed",
                        "${failure.javaClass.simpleName}. No versions were changed. Use Show details for the cause.", NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Show details") {
                        Messages.showErrorDialog(project, failure.stackTraceToString(), "Maven ${artifactKind.label} update check failed")
                    }).notify(project)
            } finally {
                running.set(false)
            }
        }
    }

    internal suspend fun createPlan(mode: UpdateMode, updateScope: MavenUpdateScope = MavenUpdateScope.WHOLE_PROJECT,
                                   artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY,
                                   currentPom: VirtualFile? = null): BulkUpdatePlan {
        val manager = MavenProjectsManager.getInstance(project)
        val projects = readAction { selectMavenProjects(manager, updateScope, currentPom) }
        check(projects.isNotEmpty()) { "Open an imported Maven POM to update its versions" }
        val service = project.service<VersionCheckService>()
        val reports = projects.associateWith { service.checkNow(it, mode, artifactKind) }
        val relocations = projects.associateWith { service.readRelocations(it) }
        return readAction {
            val files = reports.mapNotNull { (mavenProject, updates) ->
                val file = PsiManager.getInstance(project).findFile(mavenProject.file) ?: return@mapNotNull null
                val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@mapNotNull null
                file to MavenDependencyAnalysis(model, mavenProject, updates,
                    project.service<VersionCheckerSettings>().state, relocations.getValue(mavenProject))
            }.toMap()
            // Check shared properties against every imported POM, even in Current POM mode.
            val usageFiles = manager.nonIgnoredProjects.mapNotNull { PsiManager.getInstance(project).findFile(it.file) }
            BulkUpdatePlan.create(files, artifactKind, usageFiles)
        }
    }
}

private class BulkUpdateDialog(project: Project, mode: UpdateMode, updateScope: MavenUpdateScope,
                               artifactKind: MavenArtifactKind, private val plan: BulkUpdatePlan) : DialogWrapper(project) {
    init {
        title = "Update Maven ${artifactKind.label} — ${updateScope.label} — ${mode.label}"
        setOKButtonText("Update ${plan.changes.size} version declarations")
        init()
    }
    override fun createCenterPanel(): JComponent {
        val preview = plan.changes.joinToString("\n\n") { "${it.location}\n${it.expected} → ${it.latest}" } +
            plan.skipped.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\nSkipped — needs review:\n").orEmpty()
        return JBScrollPane(JBTextArea(preview).apply { isEditable = false; lineWrap = true; wrapStyleWord = true })
            .apply { preferredSize = Dimension(760, 440) }
    }
}
