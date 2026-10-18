package io.github.khopland.versionchecker.core

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Physical source files must agree with the IDE's saved baseline before an edit. */
internal suspend fun prepareSavedBuildInputValidation(project: Project, snapshots: Collection<BuildSnapshot>): CapturedEditInputs =
    prepareSavedBuildInputValidation(project, snapshots.flatMap { it.fingerprint.files.keys + it.sourceFile }.toSet())

// Clean documents are softly cached by the platform. Keep the captured document
// alive until this short-lived guard is checked, so eviction is not mistaken for an edit.
private data class SavedInput(val file: VirtualFile, val stamp: Long, val document: Document?, val documentStamp: Long?,
                              val cleanText: String?, val charset: java.nio.charset.Charset)

internal suspend fun prepareSavedBuildInputValidation(project: Project, paths: Set<String>,
                                                     readBytes: (Path) -> ByteArray = Files::readAllBytes): CapturedEditInputs {
    val inputs = readAction {
        if (project.isDisposed) emptyList() else paths.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
            .filter { !it.isDirectory && it.fileSystem.protocol == "file" }.distinct().map { file ->
                val documents = FileDocumentManager.getInstance()
                val document = documents.getCachedDocument(file)
                SavedInput(file, file.modificationStamp, document, document?.modificationStamp,
                    document?.takeUnless(documents::isDocumentUnsaved)?.text, file.charset)
            }
    }
    val matching = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        inputs.all { input ->
            context.ensureActive()
            try {
                // VFS bytes and the physical read both stay outside read locks/EDT.
                val saved = input.file.contentsToByteArray()
                val physical = readBytes(Path.of(input.file.path))
                context.ensureActive()
                saved.contentEquals(physical) && (input.cleanText == null ||
                    String(physical, input.charset).removePrefix("\uFEFF")
                        .replace("\r\n", "\n").replace('\r', '\n') == input.cleanText)
            } catch (_: IOException) { false }
        }
    }
    return CapturedEditInputs {
        matching && !project.isDisposed && inputs.all { input ->
            input.file.isValid && input.file.modificationStamp == input.stamp &&
                (input.document == null || (FileDocumentManager.getInstance().getCachedDocument(input.file) === input.document &&
                    input.document.modificationStamp == input.documentStamp))
        }
    }
}
