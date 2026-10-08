package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/** Reuse build inputs across all files in a linked build, without caching repository results. */
@Service(Service.Level.PROJECT)
internal class GradleProjectCache(private val project: Project) : Disposable {
    private data class Inputs(val directories: List<String>, val files: List<VirtualFile>, var hashes: Map<String, String>? = null)
    private data class Digest(val fileStamp: Long, val documentStamp: Long?, val value: String)
    private val inputs = mutableMapOf<String, Inputs>()
    private val digests = mutableMapOf<VirtualFile, Digest>()
    private val documentChanges = AtomicLong()
    private var structureGeneration: List<Long> = emptyList()
    private var contentGeneration: List<Long> = emptyList()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { documentChanges.incrementAndGet() }
        }, this)
    }

    // The platform disposes this service on project close or plugin unload, removing its listener.
    override fun dispose() = Unit

    private fun invalidateIfChanged() {
        val vfs = VirtualFileManager.getInstance()
        val structure = listOf(vfs.structureModificationCount, ProjectRootModificationTracker.getInstance(project).modificationCount)
        if (structure != structureGeneration) {
            structureGeneration = structure
            inputs.clear()
            digests.clear()
        }
        val content = listOf(vfs.modificationCount, documentChanges.get())
        if (content != contentGeneration) {
            contentGeneration = content
            inputs.values.forEach { it.hashes = null }
        }
    }

    @Synchronized
    fun files(root: String, directories: List<String>, compute: () -> List<VirtualFile>): List<VirtualFile> {
        invalidateIfChanged()
        val cached = inputs[root]
        if (cached != null && cached.directories == directories) return cached.files
        return compute().also { inputs[root] = Inputs(directories.toList(), it) }
    }

    @Synchronized
    fun hashes(root: String, files: List<VirtualFile>): Map<String, String> {
        invalidateIfChanged()
        val cached = inputs[root]?.takeIf { it.files === files }
        return cached?.hashes ?: files.associate { file -> file.path to digest(file) }.also { cached?.hashes = it }
    }

    private fun digest(file: VirtualFile): String {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)?.takeIf { documents.isFileModified(file) }
        val fileStamp = file.modificationStamp
        val documentStamp = document?.modificationStamp
        val cached = digests[file]
        if (cached != null && cached.fileStamp == fileStamp && cached.documentStamp == documentStamp) return cached.value
        // Read saved bytes once. Unsaved text is tracked separately so uncommitted edits reject stale fixes.
        val disk = hash(file.contentsToByteArray())
        val value = if (document == null) disk else hash("$disk|${hash(document.text.toByteArray())}".toByteArray())
        digests[file] = Digest(fileStamp, documentStamp, value)
        return value
    }

    companion object {
        fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
