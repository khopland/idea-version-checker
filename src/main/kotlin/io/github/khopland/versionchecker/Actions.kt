package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.UpdateKind
import io.github.khopland.versionchecker.core.Updater

/** Update the selected build files, or every build file, to the newest version allowed by [mode]. */
abstract class UpdateAction(private val mode: UpdateKind, private val allFiles: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && (allFiles || selected(e).isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Updater.getInstance(project).update(if (allFiles) null else selected(e), mode)
    }

    private fun selected(e: AnActionEvent): List<VirtualFile> {
        val project = e.project ?: return emptyList()
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return files.filter { file ->
            !file.isDirectory && PsiManager.getInstance(project).findFile(file)?.let(DeclarationProvider::forFile) != null
        }
    }
}

class UpdateFilePatchAction : UpdateAction(UpdateKind.PATCH, false)
class UpdateFileMinorAction : UpdateAction(UpdateKind.MINOR, false)
class UpdateFileMajorAction : UpdateAction(UpdateKind.MAJOR, false)
class UpdateAllPatchAction : UpdateAction(UpdateKind.PATCH, true)
class UpdateAllMinorAction : UpdateAction(UpdateKind.MINOR, true)
class UpdateAllMajorAction : UpdateAction(UpdateKind.MAJOR, true)

/** Read repository metadata again and highlight open build files again. */
class CheckNowAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = refreshAll()
}
