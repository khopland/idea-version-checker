package io.github.khopland.versionchecker.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker

/**
 * Stamps for caches derived from files. The cached document's stamp counts, so unsaved text invalidates a cache, and
 * the project's PSI modification count covers edits to files that a provider reads indirectly, such as a POM's parent.
 */
internal object FileStamps {
    /** [file]'s text stamp: the unsaved document when it is open, otherwise the file on disk. */
    fun document(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    /** A stamp that changes when any PSI in [project] changes, at the cost of one counter read. */
    fun project(project: Project): Long = PsiModificationTracker.getInstance(project).modificationCount
}
