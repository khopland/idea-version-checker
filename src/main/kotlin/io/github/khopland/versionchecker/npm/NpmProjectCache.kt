package io.github.khopland.versionchecker.npm

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/** Share discovery and manifest hashing between inspections, scans and write-time validation. */
@Service(Service.Level.PROJECT)
internal class NpmProjectCache(private val project: Project) : Disposable {
    private data class Digest(val fileStamp: Long, val documentStamp: Long?, val value: String)
    private val manifestChanges = AtomicLong()
    private val structureChanges = AtomicLong()
    private var structureGeneration: List<Long> = emptyList()
    private var contentGeneration = -1L
    private var files: List<VirtualFile>? = null
    private val owners = mutableMapOf<VirtualFile, NpmBuildSystemAdapter.Workspace?>()
    private val workspaces = mutableMapOf<VirtualFile, NpmBuildSystemAdapter.Workspace>()
    private val digests = mutableMapOf<VirtualFile, Digest>()
    private val workspaceDigests = mutableMapOf<VirtualFile, Map<String, String>>()

    init {
        // PSI's counter alone misses uncommitted document edits, which must invalidate fixes too.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document)?.name == "package.json") manifestChanges.incrementAndGet()
            }
        }, this)
        // PSI edits can precede document synchronization. Only manifest PSI affects ownership.
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            private fun changed(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile?.name == "package.json") manifestChanges.incrementAndGet()
            }
            override fun childAdded(event: PsiTreeChangeEvent) = changed(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = changed(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = changed(event)
            override fun childMoved(event: PsiTreeChangeEvent) = changed(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = changed(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = changed(event)
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any(::changesStructure)) structureChanges.incrementAndGet()
                if (events.any { it.file?.name == "package.json" }) manifestChanges.incrementAndGet()
            }
        })
    }

    // The platform removes document/PSI/VFS listeners on project close or plugin unload.
    override fun dispose() = Unit

    private fun invalidateIfChanged() {
        val current = listOf(structureChanges.get(),
            ProjectRootModificationTracker.getInstance(project).modificationCount,
            if (DumbService.isDumb(project)) 1L else 0L)
        if (structureGeneration != current) {
            structureGeneration = current
            files = null
            owners.clear()
            workspaces.clear()
            digests.clear()
            workspaceDigests.clear()
        }
        val content = manifestChanges.get()
        if (contentGeneration != content) {
            contentGeneration = content
            // A member's name or the root's workspace patterns can change without a VFS event.
            owners.clear()
            workspaces.clear()
            workspaceDigests.clear()
        }
    }

    private fun changesStructure(event: VFileEvent): Boolean = when (event) {
        is VFileCreateEvent -> event.isDirectory || event.childName == "package.json"
        is VFileCopyEvent -> event.file.isDirectory || event.newChildName == "package.json"
        is VFileMoveEvent, is VFileDeleteEvent -> event.file?.let { it.isDirectory || it.name == "package.json" } == true
        is VFilePropertyChangeEvent -> event.file.isDirectory || event.propertyName == VirtualFile.PROP_NAME &&
            (event.oldValue == "package.json" || event.newValue == "package.json")
        else -> false
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
    fun digest(file: VirtualFile): String {
        invalidateIfChanged()
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)?.takeIf { documents.isDocumentUnsaved(it) }
        val fileStamp = file.modificationStamp
        val documentStamp = document?.modificationStamp
        val cached = digests[file]
        if (cached != null && cached.fileStamp == fileStamp && cached.documentStamp == documentStamp) return cached.value
        // Saved documents can lag external edits. Track saved bytes and unsaved text separately.
        val disk = hash(file.contentsToByteArray())
        val value = if (document == null) disk else hash("$disk|${hash(document.text.toByteArray())}".toByteArray())
        digests[file] = Digest(fileStamp, documentStamp, value)
        return value
    }

    @Synchronized
    fun manifestDigests(workspace: NpmBuildSystemAdapter.Workspace, compute: () -> Map<String, String>): Map<String, String> {
        invalidateIfChanged()
        return workspaceDigests.getOrPut(workspace.root, compute)
    }

    companion object {
        fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
