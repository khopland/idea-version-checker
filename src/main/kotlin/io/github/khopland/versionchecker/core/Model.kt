package io.github.khopland.versionchecker.core

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** The published versions of one package in one place. */
class Releases(
    val versions: List<String>,
    /** Registry deprecation messages by version. */
    val deprecated: Map<String, String> = emptyMap(),
    /** When set, every listed version was published at or before this time (epoch milliseconds). */
    val allPublishedBefore: Long? = null,
    /** Publication time of one version, or null when the repository cannot tell. */
    private val publishedAt: (suspend (String) -> Long?)? = null,
    /** The repository already hides new releases, for example a Nexus quarantine on npm, so the minimum release age does not apply. */
    val ignoresMinimumAge: Boolean = false,
) {
    suspend fun publishedAt(version: String): Long? = publishedAt?.invoke(version)
}

/** Fetches [Releases]. Lookups with the same [key] share one request and one cache entry. */
interface Lookup {
    val key: String
    suspend fun fetch(): Releases
}

/** Text to replace when the user updates a declaration. [label] names what changes, for example a property. */
class Edit(val file: VirtualFile, val range: TextRange, val expected: String, val label: String? = null,
           val format: (String) -> String = { it }) {
    val key get() = "${file.path}@${range.startOffset}"
}

/**
 * One version in a build file.
 * [range] is where the warning shows, in the file that was scanned. A null [edit] means the plugin
 * can check the version but not change it, and [note] says why.
 */
class Declaration(
    val name: String,
    val range: TextRange,
    val current: String,
    val lookup: Lookup,
    val edit: Edit?,
    val note: String? = null,
)

/** Finds declarations in the build files of one ecosystem. */
interface DeclarationProvider {
    /** File names to scan for **Update All Files**. */
    val fileNames: Collection<String>

    fun accepts(file: PsiFile): Boolean

    /** Runs in a read action. Must not do I/O. */
    fun collect(file: PsiFile): List<Declaration>

    /**
     * Declarations in other files that share an edit with [declarations], such as other modules that use
     * a parent POM property. They take part in the agreement on a shared version but get no warning here.
     */
    fun related(file: PsiFile, declarations: List<Declaration>): List<Declaration> = emptyList()

    companion object {
        val EP = ExtensionPointName<DeclarationProvider>("io.github.khopland.version-checker.declarationProvider")

        fun forFile(file: PsiFile): DeclarationProvider? = EP.extensionList.firstOrNull { it.accepts(file) }
    }
}

sealed interface Outcome {
    data class Updates(val targets: Targets, val deprecated: String?) : Outcome
    data class Failed(val message: String) : Outcome
}
