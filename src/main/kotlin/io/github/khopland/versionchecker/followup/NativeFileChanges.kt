package io.github.khopland.versionchecker.followup

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Historical observations only. File writers cannot be attributed from before/after snapshots. */
internal data class NativeFileChange(val path: String, val before: String?, val after: String?)
internal data class NativeFileChanges(
    val files: List<NativeFileChange> = emptyList(),
    val omitted: List<String> = emptyList(),
    val captureUnavailable: Boolean = false,
    val editorChangesObserved: Boolean = false,
    val processTerminationKnown: Boolean = true,
) {
    val characters: Long get() = files.sumOf { (it.before?.length ?: 0).toLong() + (it.after?.length ?: 0) }
    fun description(): String = buildString {
        append("${files.size} changed native files captured; ${omitted.size} files omitted.")
        if (captureUnavailable) append(" Historical native snapshots are unavailable or were evicted.")
        if (omitted.isNotEmpty()) append('\n').append(omitted.joinToString("\n"))
        if (editorChangesObserved) append("\nUnsaved editor changes overlapped the command; physical snapshots do not include unsaved text.")
        if (!processTerminationKnown) append("\nProcess termination was not confirmed; later lockfile writes may be missing.")
        append("\nThese are observed physical snapshots, not changes attributed exclusively to npm. Concurrent external edits may be included. ")
        append("Declaration undo does not undo native writes. Dependency versions and installed files were not verified.")
    }
}

/** At most two output files and 1 MiB of UTF-8 bytes per phase; oversized files are never copied in full. */
internal class NativeFileChangeCapture private constructor(
    private val before: Map<Path, Observed>, private val limits: Limits,
) {
    internal data class Limits(val files: Int = 2, val bytesPerPhase: Int = 1_048_576, val characters: Int = 1_000_000) {
        init { require(files > 0 && bytesPerPhase > 0 && characters > 0) }
    }
    private data class Observed(val text: String? = null, val omission: String? = null)

    fun finish(checkCancellation: () -> Unit = {}): NativeFileChanges {
        val after = observe(before.keys, limits, checkCancellation)
        val changes = mutableListOf<NativeFileChange>()
        val omitted = mutableListOf<String>()
        var characters = 0L
        for ((path, old) in before) {
            val current = after.getValue(path)
            val omission = old.omission ?: current.omission
            if (omission != null) { omitted += "$path: $omission"; continue }
            if (old.text == current.text) continue
            val length = (old.text?.length ?: 0).toLong() + (current.text?.length ?: 0)
            if (characters + length > limits.characters) { omitted += "$path: snapshot text budget exceeded"; continue }
            changes += NativeFileChange(path.toString(), old.text, current.text)
            characters += length
        }
        return NativeFileChanges(changes, omitted)
    }

    companion object {
        fun before(paths: Collection<Path>, limits: Limits = Limits(), expectedHashes: Map<Path, String?>? = null,
                   checkCancellation: () -> Unit = {}): NativeFileChangeCapture {
            val observed = observe(paths.distinct(), limits, checkCancellation).mapValues { (path, value) ->
                if (expectedHashes == null || value.omission != null) value else {
                    checkCancellation()
                    val hash = value.text?.let { text -> java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))) }
                    if (hash == expectedHashes[path]) value else Observed(omission = "file changed while preparing snapshots")
                }
            }
            return NativeFileChangeCapture(observed, limits)
        }

        private fun observe(paths: Collection<Path>, limits: Limits, checkCancellation: () -> Unit): Map<Path, Observed> {
            var budget = limits.bytesPerPhase
            return paths.mapIndexed { index, path ->
                checkCancellation()
                var existed = false
                val value = if (index >= limits.files) Observed(omission = "snapshot file limit exceeded") else try {
                    val initial = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    existed = true
                    if (!initial.isRegularFile) Observed(omission = "not a regular physical file")
                    else if (initial.size() > budget) Observed(omission = "snapshot byte budget exceeded")
                    else {
                        val bytes = Files.newInputStream(path).use { input ->
                            val result = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (result.size() <= budget) {
                                checkCancellation()
                                val count = input.read(chunk, 0, minOf(chunk.size, budget + 1 - result.size()))
                                if (count < 0) break
                                result.write(chunk, 0, count)
                            }
                            result.toByteArray()
                        }
                        val final = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (bytes.size > budget) Observed(omission = "snapshot byte budget exceeded")
                        else if (initial.size() != final.size() || initial.lastModifiedTime() != final.lastModifiedTime() ||
                            initial.fileKey() != final.fileKey()) Observed(omission = "file changed during capture")
                        else {
                            budget -= bytes.size
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                            Observed(decoder.decode(ByteBuffer.wrap(bytes)).toString())
                        }
                    }
                } catch (_: NoSuchFileException) { if (existed) Observed(omission = "file changed during capture") else Observed() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cancelled: ProcessCanceledException) { throw cancelled }
                catch (_: Exception) { Observed(omission = "could not read a stable UTF-8 snapshot") }
                path to value
            }.toMap()
        }
    }
}
