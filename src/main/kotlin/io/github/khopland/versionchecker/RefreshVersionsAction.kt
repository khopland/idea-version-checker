package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.khopland.versionchecker.core.*

open class RefreshVersionsAction(private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        if (scope == UpdateScope.CURRENT_FILE) {
            event.presentation.text = if (event.place == ActionPlaces.EDITOR_POPUP) "Refresh Dependency Versions" else "Refresh Current File"
        }
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            project.service<VersionCheckerSettings>().state.enabled &&
                BuildSystemAdapter.matching(project, BuildSelection(scope, currentBuildFile(event)?.path)).isNotEmpty()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = if (scope == UpdateScope.CURRENT_FILE) currentBuildFile(event) ?: return else null
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<VersionCheckService>().refresh(
            BuildSystemAdapter.matching(project, BuildSelection(scope, current?.path)), current)
    }
}
class CurrentFileRefreshVersionsAction : RefreshVersionsAction(UpdateScope.CURRENT_FILE)
