package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.*

internal fun currentBuildFile(event: AnActionEvent): VirtualFile? =
    event.getData(CommonDataKeys.EDITOR)?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        ?: event.getData(CommonDataKeys.PSI_FILE)?.virtualFile
        ?: event.getData(CommonDataKeys.VIRTUAL_FILE)

/** Opens the review for the current build file, or the whole project when no supported build file is selected. */
class ReviewUpdatesAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            reviewScope(project, currentBuildFile(event)) != null
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = currentBuildFile(event)
        val scope = reviewScope(project, current) ?: return
        CheckPerformance.locally(CheckPerformance.start(CheckPerformance.Stage.PREVIEW_INVOKED)) {
            project.service<BulkUpdateService>().preview(UpdateMode.MAJOR, scope, current)
        }
    }

    private fun reviewScope(project: com.intellij.openapi.project.Project, current: VirtualFile?): UpdateScope? =
        listOf(UpdateScope.CURRENT_FILE, UpdateScope.WHOLE_PROJECT).firstOrNull { scope ->
            BuildSystemAdapter.matching(project, BuildSelection(scope, current?.path)).any { !it.isOffline(project) }
        }
}
