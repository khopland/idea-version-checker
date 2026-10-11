package io.github.khopland.versionchecker

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

/** Read-only source preview; constructing it never applies an edit or changes a document. */
internal data class VersionEditPreview(val file: VirtualFile, val offset: Int, val before: String, val after: String) {
    companion object {
        fun capture(edit: VersionEdit, range: TextRange, replacement: String): VersionEditPreview? {
            val element = edit.element ?: return null
            val file = element.containingFile ?: return null
            val virtual = file.virtualFile ?: return null
            val manager = PsiDocumentManager.getInstance(file.project)
            val document = manager.getDocument(file) ?: return null
            if (!manager.isCommitted(document) || !edit.isValid()) return null
            val before = document.text
            if (range.startOffset < 0 || range.endOffset > before.length) return null
            return VersionEditPreview(virtual, range.startOffset, before, before.replaceRange(range.startOffset, range.endOffset, replacement))
        }
    }
}
