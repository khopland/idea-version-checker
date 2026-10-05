package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.khopland.versionchecker.core.*

open class RefreshVersionsAction(private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                                 private val adapterId: String = "maven") : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val adapter = BuildSystemAdapter.find(adapterId)
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            adapter?.supports(project, BuildSelection(scope, currentBuildFile(event)?.path)) == true
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = if (scope == UpdateScope.CURRENT_FILE) currentBuildFile(event) ?: return else null
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<VersionCheckService>().refresh(adapterId, current)
    }
}
class CurrentPomRefreshVersionsAction : RefreshVersionsAction(UpdateScope.CURRENT_FILE)
