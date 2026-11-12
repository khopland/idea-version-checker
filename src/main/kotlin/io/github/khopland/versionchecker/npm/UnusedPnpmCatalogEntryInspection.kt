package io.github.khopland.versionchecker.npm

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Finds `catalog` and `catalogs` entries in `pnpm-workspace.yaml` that no package.json below it uses as `"catalog:"` or
 * `"catalog:name"`, and no override in the file. No network access.
 */
class UnusedPnpmCatalogEntryInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vfile = file.virtualFile ?: return null
        if (vfile.name != PnpmWorkspace.FILE_NAME) return null
        val root = vfile.parent ?: return null
        val packages = FilenameIndex.getVirtualFilesByName("package.json", GlobalSearchScope.projectScope(file.project))
            .filter { VfsUtilCore.isAncestor(root, it, true) && "/node_modules/" !in it.path }
            .map { FileDocumentManager.getInstance().getCachedDocument(it)?.charsSequence ?: LoadTextUtil.loadText(it) }
        return PnpmWorkspace.unusedCatalogEntries(file.text, packages).mapNotNull { entry ->
            val start = file.findElementAt(entry.keyRange.startOffset) ?: return@mapNotNull null
            val end = file.findElementAt(entry.keyRange.endOffset - 1) ?: return@mapNotNull null
            val where = if (entry.catalog == "default") "" else " from catalog ${entry.catalog}"
            manager.createProblemDescriptor(start, end, "No package uses ${entry.key}$where", ProblemHighlightType.LIKE_UNUSED_SYMBOL, isOnTheFly,
                RemoveEntryFix(entry.section, entry.key))
        }.toTypedArray()
    }

    /** Deletes the entry's line. */
    private class RemoveEntryFix(private val section: String, private val key: String) : LocalQuickFix {
        override fun getName() = "Remove unused entry"
        override fun getFamilyName() = name

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val document = descriptor.psiElement.containingFile.viewProvider.document ?: return
            val entry = PnpmWorkspace.entries(document.text).find { it.catalog != null && it.section == section && it.key == key } ?: return
            document.deleteString(entry.line.startOffset, entry.line.endOffset)
        }
    }
}
