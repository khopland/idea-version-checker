package io.github.khopland.versionchecker.core

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import io.github.khopland.versionchecker.vuln.NexusIqSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * What the annotators ask about one file. They are separate external annotators, so the platform calls each of them for
 * every highlighting pass, and they all want the same declarations, the related ones, the suppress comments and the
 * Nexus IQ application. `related()` can scan the whole build, so collecting it once per pass instead of once per
 * annotator removes the most expensive part of the pass.
 *
 * A snapshot is kept on the [PsiFile] and reused while nothing a provider can read has changed: see [Stamp]. Each part
 * is computed when the first annotator asks for it, in that annotator's read action.
 */
class FileSnapshot private constructor(private val file: PsiFile, private val provider: DeclarationProvider) {
    /** The declarations in the file itself. */
    val declarations: List<Declaration> by lazy { provider.collect(file) }

    /** The declarations in other files that share an edit with [declarations], such as another module's property. */
    val related: List<Declaration> by lazy { provider.related(file, declarations) }

    private val suppressed = ConcurrentHashMap<String, Set<Declaration>>()

    /** The [declarations] that a suppress comment for [tool] keeps. */
    fun suppressed(tool: LocalInspectionTool): Set<Declaration> =
        suppressed.getOrPut(tool.shortName) { Suppression.suppressed(file, declarations, tool) }

    private val hint: ApplicationHint? by lazy { provider.application(file) }

    /** The Nexus IQ application of the file. Null unless the project names a Nexus IQ server, which is asked every time: it is a setting. */
    val application: ApplicationHint? get() = if (NexusIqSettings.getInstance(file.project).serverUrl != null) hint else null

    /** The packages that [declarations] bring in, as the IDE or a lockfile resolved them. */
    val transitive: List<Transitive> by lazy { provider.transitive(file) }

    companion object {
        private val KEY = Key.create<Cached>("version-checker.file-snapshot")

        private class Cached(val stamp: Stamp, val snapshot: FileSnapshot)

        /**
         * The state a snapshot was made from. A provider reads the file, other PSI such as a parent POM, files it
         * loads through the VFS such as a lockfile, and what [DeclarationProvider.stamp] covers. The three counters
         * change far more often than a provider's result does, which only costs a collection that the provider's own
         * caches make cheap. [epoch] is **Check Now**.
         */
        private data class Stamp(val file: Long, val psi: Long, val vfs: Long, val epoch: Long, val provider: Any?)

        /** The snapshot of [file] for [provider], or a new one when something it was made from changed. Runs in a read action. */
        fun of(file: PsiFile, provider: DeclarationProvider): FileSnapshot {
            val stamp = Stamp(file.modificationStamp, PsiModificationTracker.getInstance(file.project).modificationCount,
                VirtualFileManager.getInstance().modificationCount, FileStamps.epoch(), provider.stamp(file))
            file.getUserData(KEY)?.takeIf { it.stamp == stamp }?.let { return it.snapshot }
            // Two annotators often arrive together, and the one that loses would collect everything again.
            synchronized(KEY) {
                file.getUserData(KEY)?.takeIf { it.stamp == stamp }?.let { return it.snapshot }
                return FileSnapshot(file, provider).also { file.putUserData(KEY, Cached(stamp, it)) }
            }
        }
    }
}
