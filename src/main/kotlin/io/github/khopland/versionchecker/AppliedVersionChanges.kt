package io.github.khopland.versionchecker

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException

/** Historical strings only: no live PSI, documents, VFS files or edit authority. */
internal data class AppliedVersionFile(val path: String, val before: String, val after: String)

internal class AppliedVersionChanges(files: List<AppliedVersionFile>, val omittedTargetFiles: Int = 0,
                                     val captureUnavailable: Boolean = false) {
    val files: List<AppliedVersionFile> = java.util.Collections.unmodifiableList(files.toList())
    val textCharacters: Long = files.sumOf { it.before.length.toLong() + it.after.length }
}

/** Short-lived bounded capture inside the write command; only immutable strings escape it. */
internal class AppliedVersionChangeCapture private constructor(
    private val pending: List<Pair<PsiFile, String>>, private val omitted: Int, private val limits: Limits,
) {
    /** Worst-case UTF-16 text payload is 2 MiB; oversized targets are never copied or hashed. */
    internal data class Limits(val files: Int = 50, val characters: Int = 1_000_000) {
        init { require(files > 0 && characters > 0) }
    }

    /** Snapshot presentation after the authorized writes must not misreport an unapplied update. */
    fun finish(): AppliedVersionChanges? = try {
        val files = mutableListOf<AppliedVersionFile>()
        var characters = 0L
        var skipped = omitted
        for ((file, before) in pending) {
            if (!file.isValid) return AppliedVersionChanges(emptyList(), captureUnavailable = true)
            val manager = PsiDocumentManager.getInstance(file.project)
            val document = manager.getDocument(file)
            if (document == null) return AppliedVersionChanges(emptyList(), captureUnavailable = true)
            if (characters + before.length + document.textLength > limits.characters) { skipped++; continue }
            manager.doPostponedOperationsAndUnblockDocument(document)
            manager.commitDocument(document)
            if (characters + before.length + document.textLength > limits.characters) { skipped++; continue }
            val after = document.text
            if (before != after) {
                files += AppliedVersionFile(file.virtualFile.path, before, after)
                characters += before.length.toLong() + after.length
            }
        }
        if (files.isEmpty() && skipped == 0) null else AppliedVersionChanges(files, skipped)
    } catch (failure: RuntimeException) {
        // These are optional presentation operations after the write, including cancellation.
        if (failure !is ProcessCanceledException && failure !is CancellationException)
            Logger.getInstance(AppliedVersionChangeCapture::class.java).warn("Could not capture version update review", failure)
        AppliedVersionChanges(emptyList(), captureUnavailable = true)
    }

    companion object {
        fun before(changes: List<VersionEdit>, limits: Limits = Limits()): AppliedVersionChangeCapture? = try {
            val files = changes.mapNotNull { it.documentChange?.file ?: it.element?.containingFile }
                .distinctBy { it.virtualFile?.path ?: it.name }
            val pending = mutableListOf<Pair<PsiFile, String>>()
            var characters = 0L
            var skipped = 0
            for (file in files) {
                if (!file.isValid || file.virtualFile == null || pending.size >= limits.files ||
                    characters + file.textLength > limits.characters / 2) { skipped++; continue }
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                if (document == null || characters + document.textLength > limits.characters / 2) { skipped++; continue }
                val before = document.text
                pending += file to before
                characters += before.length
            }
            AppliedVersionChangeCapture(pending, skipped, limits)
        } catch (cancelled: ProcessCanceledException) { throw cancelled }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RuntimeException) {
            Logger.getInstance(AppliedVersionChangeCapture::class.java).warn("Could not prepare version update review", failure)
            null
        }
    }
}
