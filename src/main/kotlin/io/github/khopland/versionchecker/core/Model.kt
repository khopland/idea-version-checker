package io.github.khopland.versionchecker.core

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

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
    /** New coordinates when the package has moved. */
    val moved: Moved? = null,
) {
    suspend fun publishedAt(version: String): Long? = publishedAt?.invoke(version)
}

/** The coordinates a package moved to, with the [newest] release there when known, and the publisher's [message]. */
data class Moved(val to: String, val newest: String? = null, val message: String? = null)

/** Fetches [Releases]. Lookups with the same [key] share one request and one cache entry. */
interface Lookup {
    val key: String
    suspend fun fetch(): Releases

    /** Whether [releaseNotes] can find a page, so the editor offers the action. */
    val hasReleaseNotes: Boolean get() = false

    /** A page with the changes in [version], such as the project's GitHub releases. Only called when the user asks. */
    suspend fun releaseNotes(version: String): String? = null
}

/** Text to replace when the user updates a declaration. [label] names what changes, for example a property. */
class Edit(val file: VirtualFile, val range: TextRange, val expected: String, val label: String? = null,
           val format: (String) -> String = { it }) {
    val key get() = "${file.path}@${range.startOffset}"
}

/**
 * One version in a build file.
 * [range] is where the warning shows, in the file that was scanned. A null [edit] means the plugin
 * can check the version but not change it, and [note] says why. [ecosystem] is where vulnerability databases
 * list the package, or null when they do not, as for Gradle plugins and wrappers.
 */
class Declaration(
    val name: String,
    val range: TextRange,
    val current: String,
    val lookup: Lookup,
    val edit: Edit?,
    val note: String? = null,
    val ecosystem: Ecosystem? = null,
) {
    val pkg get() = ecosystem?.let { Pkg(it, name, current) }
}

/**
 * A package that a declared dependency brings in, at the version the build resolves. [via] names the declared dependency,
 * [range] is where it is declared, and [path] lists the packages between it and [pkg]. [lookup] finds the package's
 * releases, for a safe version to pin.
 */
class Transitive(val via: String, val range: TextRange, val pkg: Pkg, val path: List<String> = emptyList(), val lookup: Lookup? = null)

/** Finds declarations in the build files of one ecosystem. */
interface DeclarationProvider {
    /** File names to scan for **Update All Files**. */
    val fileNames: Collection<String>

    /** The files **Update All Files** scans. Runs in a read action. */
    fun buildFiles(project: Project, scope: GlobalSearchScope): Collection<VirtualFile> =
        fileNames.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }

    fun accepts(file: PsiFile): Boolean

    /** Runs in a read action. Must not do I/O. */
    fun collect(file: PsiFile): List<Declaration>

    /**
     * Declarations in other files that share an edit with [declarations], such as other modules that use
     * a parent POM property. They take part in the agreement on a shared version but get no warning here.
     */
    fun related(file: PsiFile, declarations: List<Declaration>): List<Declaration> = emptyList()

    /**
     * The packages that the dependencies declared in [file] bring in, as the IDE or a lockfile resolved them. Runs in a
     * read action. May read files through the VFS, but must not use the network.
     */
    fun transitive(file: PsiFile): List<Transitive> = emptyList()

    /** A fix that makes the build resolve [transitive] at [version] instead, or null when [file] cannot say so. Runs in a read action. */
    fun pin(file: PsiFile, transitive: Transitive, version: String): IntentionAction? = null

    companion object {
        val EP = ExtensionPointName<DeclarationProvider>("io.github.khopland.version-checker.declarationProvider")

        fun forFile(file: PsiFile): DeclarationProvider? = EP.extensionList.firstOrNull { it.accepts(file) }
    }
}

sealed interface Outcome {
    data class Updates(val targets: Targets, val deprecated: String?, val moved: Moved? = null) : Outcome
    data class Failed(val message: String) : Outcome
}
