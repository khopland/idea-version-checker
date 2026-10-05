package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

class RefreshVersionsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let {
            MavenProjectsManager.getInstance(it).hasProjects()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<VersionCheckService>().refresh()
    }
}
