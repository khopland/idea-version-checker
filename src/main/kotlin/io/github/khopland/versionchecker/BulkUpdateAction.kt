package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

abstract class BulkUpdateAction(private val mode: UpdateMode) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let {
            val manager = MavenProjectsManager.getInstance(it)
            manager.hasProjects() && !manager.generalSettings.isWorkOffline
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<BulkUpdateService>().preview(mode)
    }
}
class PatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH)
class MinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR)
class MajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR)
