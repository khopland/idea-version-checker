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
import io.github.khopland.versionchecker.core.FileDigestCache
import io.github.khopland.versionchecker.core.sha256
import java.util.concurrent.atomic.AtomicLong

/** Reuse build inputs across all files in a linked build, without caching repository results. */
@Service(Service.Level.PROJECT)
internal class GradleProjectCache(private val project: Project) : Disposable {
    private data class Inputs(val directories: GradleInputDirectories, val inventory: GradleInputInventory, var hashes: Map<String, String>? = null,
                              var inspectionHashes: Map<String, String>? = null)
    private data class InspectionDigest(val fileStamp: Long, val documentStamp: Long?, val value: String)
    private val inputs = mutableMapOf<String, Inputs>()
    private val digests = FileDigestCache()
    private val inspectionDigests = mutableMapOf<VirtualFile, InspectionDigest>()
    private val documentChanges = AtomicLong()
    private val metadataChanges = AtomicLong()
    val metadataGeneration: Long get() = metadataChanges.get()
    fun invalidateMetadata() { metadataChanges.incrementAndGet() }
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
            inspectionDigests.clear()
        }
        val content = listOf(vfs.modificationCount, documentChanges.get())
        if (content != contentGeneration) {
            contentGeneration = content
            inputs.values.forEach { it.hashes = null; it.inspectionHashes = null }
        }
    }

    @Synchronized
    fun inventory(root: String, directories: GradleInputDirectories, compute: () -> GradleInputInventory): GradleInputInventory {
        invalidateIfChanged()
        val cached = inputs[root]
        if (cached != null && cached.directories == directories) return cached.inventory
        return compute().also { inputs[root] = Inputs(directories, it) }
    }

    @Synchronized
    fun hashes(root: String, files: List<VirtualFile>): Map<String, String> {
        invalidateIfChanged()
        val cached = inputs[root]?.takeIf { it.inventory.files === files }
        return cached?.hashes ?: files.associate { file -> file.path to digests.get(file).current }.also { cached?.hashes = it }
    }

    /** The saved VFS baseline is separate from legitimate unsaved editor changes. */
    @Synchronized
    fun savedHashes(root: String, files: List<VirtualFile>): Map<VirtualFile, String> {
        hashes(root, files)
        return files.associateWith { digests.get(it).saved }
    }

    @Synchronized
    fun inspectionHashes(root: String, files: List<VirtualFile>): Map<String, String> {
        invalidateIfChanged()
        val cached = inputs[root]?.takeIf { it.inventory.files === files }
        return cached?.inspectionHashes ?: files.associate { file ->
            val editable = file.name in setOf("build.gradle", "build.gradle.kts") ||
                file.name == "libs.versions.toml" && file.parent.name == "gradle"
            file.path to if (editable) inspectionDigest(file) else digests.get(file).current
        }.also { cached?.inspectionHashes = it }
    }

    private fun inspectionDigest(file: VirtualFile): String {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)?.takeIf { documents.isFileModified(file) }
        val fileStamp = file.modificationStamp
        val documentStamp = document?.modificationStamp
        val cached = inspectionDigests[file]
        if (cached != null && cached.fileStamp == fileStamp && cached.documentStamp == documentStamp) return cached.value
        fun normalized(text: String): String {
            val ranges = GradleDeclarations.parse(file.path, text).filter { it.reason == null }.mapNotNull { it.range }
                .distinct().sortedByDescending { it.startOffset }
            return StringBuilder(text).apply {
                ranges.forEach { replace(it.startOffset, it.endOffset, "__version__") }
            }.toString()
        }
        // Ignore only supported version values. Repository blocks, calls, catalog consumers and
        // every other input still invalidate retained warnings, including unsaved configuration.
        val saved = normalized(String(file.contentsToByteArray(), Charsets.UTF_8))
        val edited = document?.let { normalized(it.text) } ?: saved
        val value = sha256((if (saved == edited) saved else "$saved|$edited").toByteArray())
        inspectionDigests[file] = InspectionDigest(fileStamp, documentStamp, value)
        return value
    }
}
