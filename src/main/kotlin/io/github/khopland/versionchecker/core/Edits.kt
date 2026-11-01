package io.github.khopland.versionchecker.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiDocumentManager

/** A new text for one [Edit]. */
data class Change(val edit: Edit, val text: String)

object Edits {
    /**
     * Applies [changes] as one undoable command, across files. A change is skipped when its text
     * no longer reads as expected. Returns the applied changes. Call on the EDT.
     */
    fun apply(project: Project, changes: Collection<Change>, name: String): List<Change> {
        val unique = changes.distinctBy { it.edit.key }
        val files = unique.map { it.edit.file }.distinct()
        if (files.isEmpty() || ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files).hasReadonlyFiles()) return emptyList()
        val applied = mutableListOf<Change>()
        WriteCommandAction.writeCommandAction(project).withName(name).withGlobalUndo().run<RuntimeException> {
            unique.groupBy { it.edit.file }.forEach { (file, fileChanges) ->
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
                for (change in fileChanges.sortedByDescending { it.edit.range.startOffset }) {
                    val range = change.edit.range
                    if (range.endOffset > document.textLength || document.getText(range) != change.edit.expected) continue
                    document.replaceString(range.startOffset, range.endOffset, change.text)
                    applied += change
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        return applied
    }
}
