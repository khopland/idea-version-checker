package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

open class RefreshVersionsAction(private val scope: MavenUpdateScope = MavenUpdateScope.WHOLE_PROJECT) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let {
            selectMavenProjects(MavenProjectsManager.getInstance(it), scope, currentMavenPom(event)).isNotEmpty()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val currentPom = if (scope == MavenUpdateScope.CURRENT_POM) currentMavenPom(event) ?: return else null
        project.service<VersionCheckService>().refresh(currentPom)
    }
}

class CurrentPomRefreshVersionsAction : RefreshVersionsAction(MavenUpdateScope.CURRENT_POM)
