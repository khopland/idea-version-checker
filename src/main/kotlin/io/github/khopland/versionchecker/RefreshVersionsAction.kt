package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.khopland.versionchecker.core.*

open class RefreshVersionsAction(private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            BuildSystemAdapter.matching(project, BuildSelection(scope, currentBuildFile(event)?.path)).isNotEmpty()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = if (scope == UpdateScope.CURRENT_FILE) currentBuildFile(event) ?: return else null
        FileDocumentManager.getInstance().saveAllDocuments()
        BuildSystemAdapter.matching(project, BuildSelection(scope, current?.path)).forEach { adapter ->
            project.service<VersionCheckService>().refresh(adapter.id, current)
        }
    }
}
class CurrentFileRefreshVersionsAction : RefreshVersionsAction(UpdateScope.CURRENT_FILE)
