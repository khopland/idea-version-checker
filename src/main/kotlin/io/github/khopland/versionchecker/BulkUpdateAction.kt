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

abstract class BulkUpdateAction(private val mode: UpdateMode,
                                private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT) : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            val adapters = BuildSystemAdapter.matching(project, BuildSelection(scope, currentBuildFile(event)?.path))
            adapters.isNotEmpty() && adapters.all { mode in it.capabilities.updateModes && !it.isOffline(project) }
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = currentBuildFile(event)
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<BulkUpdateService>().preview(mode, scope, current)
    }
}
class PatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH)
class MinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR)
class MajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR)
class CurrentFilePatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, UpdateScope.CURRENT_FILE)
class CurrentFileMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, UpdateScope.CURRENT_FILE)
class CurrentFileMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, UpdateScope.CURRENT_FILE)
class ReviewCurrentFileUpdatesAction : BulkUpdateAction(UpdateMode.PATCH, UpdateScope.CURRENT_FILE)
