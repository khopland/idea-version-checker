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
                                private val scope: UpdateScope = UpdateScope.WHOLE_PROJECT,
                                private val adapterId: String = "maven") : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val adapter = BuildSystemAdapter.find(adapterId)
        event.presentation.isEnabledAndVisible = event.project?.let { project ->
            adapter != null && mode in adapter.capabilities.updateModes && !adapter.isOffline(project) &&
                adapter.supports(project, BuildSelection(scope, currentBuildFile(event)?.path))
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val current = currentBuildFile(event)
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<BulkUpdateService>().preview(mode, scope, current, adapterId)
    }
}
open class PatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH)
open class MinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR)
open class MajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR)
open class CurrentPomPatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, UpdateScope.CURRENT_FILE)
open class CurrentPomMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, UpdateScope.CURRENT_FILE)
open class CurrentPomMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, UpdateScope.CURRENT_FILE)
// Keep existing shortcut IDs usable; these aliases now run the combined update workflow.
class PluginPatchUpdateAction : PatchUpdateAction()
class PluginMinorUpdateAction : MinorUpdateAction()
class PluginMajorUpdateAction : MajorUpdateAction()
class CurrentPomPluginPatchUpdateAction : CurrentPomPatchUpdateAction()
class CurrentPomPluginMinorUpdateAction : CurrentPomMinorUpdateAction()
class CurrentPomPluginMajorUpdateAction : CurrentPomMajorUpdateAction()
