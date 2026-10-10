package io.github.khopland.versionchecker

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.util.DocumentUtil

/** Offsets belong to one immutable capture, never to an intermediate edited document. */
internal class DocumentVersionChange(
    file: PsiFile,
    val range: TextRange,
    val replacement: String,
    private val original: String
) {
    private val pointer = SmartPointerManager.createPointer(file)
    val file: PsiFile? get() = pointer.element
    val expected: String = range.substring(original)

    fun isValid(): Boolean {
        val file = file ?: return false
        return PsiDocumentManager.getInstance(file.project).getDocument(file)?.text == original
    }

    internal class Prepared(private val document: Document, private val manager: PsiDocumentManager,
                            private val edits: List<DocumentVersionChange>) {
        fun apply() {
            // Preserve caret/range markers on unchanged text and defer expensive editor maintenance.
            DocumentUtil.executeInBulk(document) {
                for (edit in edits) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
            }
            manager.commitDocument(document)
        }
    }

    companion object {
        /** Prepare every file before writing any; reject overlaps and inconsistent captures. */
        fun prepare(changes: List<DocumentVersionChange>): List<Prepared>? {
            val byFile = linkedMapOf<PsiFile, MutableList<DocumentVersionChange>>()
            for (change in changes) {
                val file = change.file ?: return null
                byFile.getOrPut(file) { mutableListOf() }.add(change)
            }
            val prepared = ArrayList<Prepared>(byFile.size)
            for ((file, edits) in byFile) {
                val manager = PsiDocumentManager.getInstance(file.project)
                val document = manager.getDocument(file) ?: return null
                val original = edits.first().original
                // One whole-file comparison, rather than one comparison per declaration.
                if (document.text != original || edits.any { it.original != original }) return null
                val ordered = edits.sortedBy { it.range.startOffset }
                var end = 0
                for (edit in ordered) {
                    if (edit.range.startOffset < end || edit.range.endOffset > original.length) return null
                    end = edit.range.endOffset
                }
                // Descending edits preserve original offsets when replacement lengths differ.
                prepared.add(Prepared(document, manager, ordered.asReversed()))
            }
            return prepared
        }
    }
}
