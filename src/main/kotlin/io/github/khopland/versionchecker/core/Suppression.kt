package io.github.khopland.versionchecker.core

import com.intellij.codeInsight.daemon.impl.actions.AbstractBatchSuppressByNoInspectionCommentFix
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Suppress comments keep a version on purpose, without a settings page: `<!--suppress OutdatedDependencyVersion -->`
 * in a POM, `//noinspection OutdatedDependencyVersion` in a Gradle script, `#noinspection` in a version catalog.
 * A comment where the version is set, such as a property or a catalog version, keeps every declaration that uses it.
 */
internal object Suppression {
    private val lineComment = Regex("""^\s*(?://|#)\s*noinspection\s+([\w-]+(?:\s*,\s*[\w-]+)*)""")

    /** The [declarations] in [file] that a suppress comment for [tool] keeps. Runs in a read action. */
    fun suppressed(file: PsiFile, declarations: List<Declaration>, tool: LocalInspectionTool): Set<Declaration> =
        declarations.filterTo(HashSet()) { isSuppressed(file, it, tool) }

    private fun isSuppressed(file: PsiFile, declaration: Declaration, tool: LocalInspectionTool): Boolean {
        if (suppressedAt(file, declaration.range.startOffset, tool)) return true
        val edit = declaration.edit ?: return false
        val target = if (edit.file == file.virtualFile) file else PsiManager.getInstance(file.project).findFile(edit.file) ?: return false
        return suppressedAt(target, edit.range.startOffset, tool)
    }

    private fun suppressedAt(file: PsiFile, offset: Int, tool: LocalInspectionTool): Boolean {
        val element = file.findElementAt(offset) ?: return false
        // The platform handles XML, Groovy, Kotlin `@Suppress` and properties; TOML and Kotlin line comments need the text check.
        return tool.isSuppressedFor(element) || commentAbove(file.viewProvider.contents, offset, tool.shortName)
    }

    /** A `//noinspection` or `#noinspection` comment for [id] on the line above [offset]. */
    fun commentAbove(text: CharSequence, offset: Int, id: String): Boolean {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        if (lineStart == 0) return false
        val previous = text.subSequence(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)
        val ids = lineComment.find(previous)?.groupValues?.get(1) ?: return false
        return ids.split(',').map { it.trim() }.any { it == id || it == "ALL" }
    }
}

/** `#noinspection` in a version catalog, with an Alt+Enter action. The TOML plugin has no suppressor of its own. */
class LineCommentSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String) =
        Suppression.commentAbove(element.containingFile.viewProvider.contents, element.textRange.startOffset, toolId)

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = arrayOf(SuppressForLineFix(toolId))

    private class SuppressForLineFix(id: String) : AbstractBatchSuppressByNoInspectionCommentFix(id, false) {
        init {
            setText("Suppress for line")
        }

        /** The outermost element that starts on the line, so the comment goes above the whole entry. */
        override fun getContainer(context: PsiElement?): PsiElement? {
            var element = context ?: return null
            val text = element.containingFile.viewProvider.contents
            fun lineStart(element: PsiElement) = text.lastIndexOf('\n', element.textRange.startOffset - 1) + 1
            val line = lineStart(element)
            while (true) {
                val parent = element.parent ?: break
                if (parent is PsiFile || lineStart(parent) != line) break
                element = parent
            }
            return element
        }

        /** A whole line above the entry. A comment element could end up on the entry's line and comment it out. */
        override fun createSuppression(project: Project, element: PsiElement, container: PsiElement) {
            val document = container.containingFile.viewProvider.document ?: return
            val start = container.textRange.startOffset
            val lineStart = document.getLineStartOffset(document.getLineNumber(start))
            val indent = document.charsSequence.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }
            document.insertString(lineStart, "$indent#noinspection $myID\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}
