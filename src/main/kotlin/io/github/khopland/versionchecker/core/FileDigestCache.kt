package io.github.khopland.versionchecker.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.security.MessageDigest
import java.util.HexFormat

internal fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * Saved VFS bytes and unsaved editor text have independent identities.
 * Providers own inventory invalidation; a file/document stamp change refreshes its digest here.
 * Callers must hold IDE read access while reading documents.
 */
internal class FileDigestCache {
    internal data class Digest(val saved: String, val current: String)
    private data class Entry(val fileStamp: Long, val documentStamp: Long?, val digest: Digest)
    private val entries = mutableMapOf<VirtualFile, Entry>()

    @Synchronized
    fun get(file: VirtualFile): Digest {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)?.takeIf(documents::isDocumentUnsaved)
        val fileStamp = file.modificationStamp
        val documentStamp = document?.modificationStamp
        entries[file]?.takeIf { it.fileStamp == fileStamp && it.documentStamp == documentStamp }
            ?.let { return it.digest }

        // A clean cached document can lag external changes; always hash the saved VFS bytes.
        val saved = sha256(file.contentsToByteArray())
        val current = if (document == null) saved
            else sha256("$saved|${sha256(document.text.toByteArray(Charsets.UTF_8))}".toByteArray(Charsets.UTF_8))
        return Digest(saved, current).also { entries[file] = Entry(fileStamp, documentStamp, it) }
    }

    @Synchronized
    fun clear() = entries.clear()
}
