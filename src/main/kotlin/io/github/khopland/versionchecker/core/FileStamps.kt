package io.github.khopland.versionchecker.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicLong

/** Stamps for caches derived from files. */
internal object FileStamps {
    private val epoch = AtomicLong()

    /** [file]'s text stamp: the unsaved document when it is open, otherwise the file on disk. */
    fun document(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    /**
     * Changes when the user asks to check again. A cache that reads something it cannot stamp, such as a file outside
     * the project, has it in its stamp, so **Check Now** always reads everything again.
     */
    fun epoch(): Long = epoch.get()

    /** Makes every cache with [epoch] in its stamp out of date. */
    fun invalidate() {
        epoch.incrementAndGet()
    }
}
