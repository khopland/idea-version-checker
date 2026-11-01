package io.github.khopland.versionchecker.core

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile

/** Alt+Enter: write one new version. */
class UpdateVersionFix(private val edit: Edit, private val version: String, private val kind: UpdateKind) : IntentionAction, PriorityAction {
    override fun getText() = "Update ${edit.label?.let { "$it " } ?: ""}to $version (${kind.label})"
    override fun getFamilyName() = "Update version"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = edit.file.isValid
    override fun startInWriteAction() = false
    override fun getPriority() = when (kind) {
        UpdateKind.PATCH -> PriorityAction.Priority.TOP
        UpdateKind.MINOR -> PriorityAction.Priority.HIGH
        UpdateKind.MAJOR -> PriorityAction.Priority.NORMAL
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        Edits.apply(project, listOf(Change(edit, edit.format(version))), text)
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val source = if (edit.file == file.virtualFile) file.text else return IntentionPreviewInfo.EMPTY
        if (edit.range.endOffset > source.length || edit.range.substring(source) != edit.expected) return IntentionPreviewInfo.EMPTY
        val start = source.lastIndexOf('\n', edit.range.startOffset - 1) + 1
        val end = source.indexOf('\n', edit.range.endOffset).let { if (it < 0) source.length else it }
        val after = source.substring(start, edit.range.startOffset) + edit.format(version) + source.substring(edit.range.endOffset, end)
        return IntentionPreviewInfo.CustomDiff(file.fileType, source.substring(start, end).trim(), after.trim())
    }
}

/** Alt+Enter: update every version in this file, after the user picks a mode. */
class UpdateFileFix : IntentionAction, LowPriorityAction {
    override fun getText() = "Update all versions in this file…"
    override fun getFamilyName() = text
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = file?.virtualFile != null
    override fun startInWriteAction() = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile) = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = file?.virtualFile ?: return
        val modes = UpdateKind.entries.associateBy { "${it.title} updates" }
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(modes.keys.toList())
            .setTitle("Update All Versions in ${target.name}")
            .setItemChosenCallback { Updater.getInstance(project).update(listOf(target), modes.getValue(it)) }
            .createPopup()
        if (editor != null) popup.showInBestPositionFor(editor) else popup.showCenteredInCurrentWindow(project)
    }
}
