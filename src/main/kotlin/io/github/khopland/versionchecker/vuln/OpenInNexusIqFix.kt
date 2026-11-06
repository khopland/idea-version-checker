package io.github.khopland.versionchecker.vuln

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Pkg

/**
 * Alt+Enter: open the application's newest scan report in Nexus IQ, where the user can review and waive the violations of
 * [pkg]. The report is looked up only when the user picks this.
 */
class OpenInNexusIqFix(private val source: NexusIqSource, private val pkg: Pkg) : IntentionAction, LowPriorityAction {
    override fun getText() = "Open ${source.applicationId} in Nexus IQ to waive"
    override fun getFamilyName() = "Open in Nexus IQ"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
    override fun startInWriteAction() = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile) = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val result = runWithModalProgressBlocking(project, "Finding the Nexus IQ report") { runCatching { source.reportUrl() } }
        val url = result.getOrNull()
        if (url != null) return BrowserUtil.browse(url)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
        val notification = result.exceptionOrNull()?.let { error ->
            group.createNotification("Could not open ${source.applicationId} in Nexus IQ", error.message ?: error.javaClass.simpleName, NotificationType.ERROR)
        } ?: group.createNotification("${source.applicationId} has no scan report in Nexus IQ yet",
            "Scan the application to waive ${pkg.name} in its report.", NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimpleExpiring("Open Nexus IQ") { BrowserUtil.browse(source.server) }).notify(project)
    }
}
