package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import io.github.khopland.versionchecker.core.*

open class RefreshVersionsAction(private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            project.service<VersionCheckerSettings>().state.enabled &&
                BuildSystemAdapter.matching(project, BuildSelection(scope, currentBuildFile(event)?.path)).isNotEmpty()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = if (scope == UpdateScope.CURRENT_FILE) currentBuildFile(event) ?: return else null
        project.service<VersionCheckService>().refreshSelection(current)
    }
}
class CurrentFileRefreshVersionsAction : RefreshVersionsAction(UpdateScope.CURRENT_FILE)
