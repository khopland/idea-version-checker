package io.github.khopland.versionchecker.npm

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.atomic.AtomicLong

/** Share discovery and manifest hashing between inspections, scans and write-time validation. */
@Service(Service.Level.PROJECT)
internal class NpmProjectCache(private val project: Project) {
    private val documentChanges = AtomicLong()
    private var generation: List<Long> = emptyList()
    private var files: List<VirtualFile>? = null
    private val owners = mutableMapOf<VirtualFile, NpmBuildSystemAdapter.Workspace?>()
    private val workspaces = mutableMapOf<VirtualFile, NpmBuildSystemAdapter.Workspace>()
    private val digests = mutableMapOf<VirtualFile, String>()
    private val workspaceDigests = mutableMapOf<VirtualFile, Map<String, String>>()

    init {
        // PSI's counter alone misses uncommitted document edits, which must invalidate fixes too.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { documentChanges.incrementAndGet() }
        }, project)
    }

    private fun invalidateIfChanged() {
        val current = listOf(documentChanges.get(), PsiModificationTracker.getInstance(project).modificationCount,
            VirtualFileManager.getInstance().modificationCount,
            ProjectRootModificationTracker.getInstance(project).modificationCount,
            if (DumbService.isDumb(project)) 1L else 0L)
        if (generation == current) return
        generation = current
        files = null
        owners.clear()
        workspaces.clear()
        digests.clear()
        workspaceDigests.clear()
    }

    @Synchronized
    fun files(compute: () -> List<VirtualFile>): List<VirtualFile> {
        invalidateIfChanged()
        return files ?: compute().also { files = it }
    }

    @Synchronized
    fun workspace(file: VirtualFile, compute: () -> NpmBuildSystemAdapter.Workspace): NpmBuildSystemAdapter.Workspace {
        invalidateIfChanged()
        return workspaces.getOrPut(file, compute)
    }

    @Synchronized
    fun owner(directory: VirtualFile, compute: () -> NpmBuildSystemAdapter.Workspace?): NpmBuildSystemAdapter.Workspace? {
        invalidateIfChanged()
        if (!owners.containsKey(directory)) owners[directory] = compute()
        return owners[directory]
    }

    @Synchronized
    fun digest(file: VirtualFile, compute: () -> String): String {
        invalidateIfChanged()
        return digests.getOrPut(file, compute)
    }

    @Synchronized
    fun manifestDigests(workspace: NpmBuildSystemAdapter.Workspace, compute: () -> Map<String, String>): Map<String, String> {
        invalidateIfChanged()
        return workspaceDigests.getOrPut(workspace.root, compute)
    }
}
