package io.github.khopland.versionchecker.gradle

import com.intellij.codeInspection.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.FileStamps

/** Finds `[versions]`, `[libraries]` and `[plugins]` entries in `gradle/libs.versions.toml` that nothing uses. No network access. */
class UnusedCatalogEntryInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vfile = file.virtualFile ?: return null
        if (vfile.name != "libs.versions.toml" || vfile.parent?.name != "gradle") return null
        val sources = CatalogSources.find(vfile.parent.parent ?: return null) ?: return null
        return CatalogEntries.unused(file.text, sources).mapNotNull { entry ->
            val start = file.findElementAt(entry.aliasRange.startOffset) ?: return@mapNotNull null
            val end = file.findElementAt(entry.aliasRange.endOffset - 1) ?: return@mapNotNull null
            val kind = when (entry.section) { "versions" -> "Version"; "libraries" -> "Library"; else -> "Plugin" }
            manager.createProblemDescriptor(start, end, "$kind ${entry.alias} is not used", ProblemHighlightType.LIKE_UNUSED_SYMBOL, isOnTheFly,
                RemoveCatalogEntryFix(entry.section, entry.alias))
        }.toTypedArray()
    }
}

/** Deletes the entry's lines. */
internal class RemoveCatalogEntryFix(private val section: String, private val alias: String) : LocalQuickFix {
    override fun getName() = "Remove unused entry"
    override fun getFamilyName() = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val document = descriptor.psiElement.containingFile.viewProvider.document ?: return
        val entry = CatalogEntries.parse(document.charsSequence).find { it.section == section && it.alias == alias } ?: return
        document.deleteString(entry.lines.startOffset, entry.lines.endOffset)
    }
}

internal object CatalogEntries {
    /** One `alias = value` entry. [lines] covers its whole lines, with the line break, also for a value over several lines. */
    class Entry(val section: String, val alias: String, val aliasRange: TextRange, val value: String, val lines: TextRange)

    private val table = Regex("""^\s*\[([\w.\-]+)]\s*(?:#.*)?$""")
    private val key = Regex("""^\s*([\w.\-]+)\s*=""")
    private val reference = Regex("""\bref\s*=\s*"([^"]+)"""")
    private val quoted = Regex("""["']([\w.\-]+)["']""")
    private val accessor = Regex("""\blibs\.([\w.]+)""")

    fun parse(text: CharSequence): List<Entry> {
        val entries = mutableListOf<Entry>()
        var section = ""
        var offset = 0
        var open: Triple<String, TextRange, Int>? = null
        var depth = 0
        for (line in text.split('\n')) {
            val end = minOf(offset + line.length + 1, text.length)
            if (open == null) {
                val match = key.find(line)
                when {
                    table.matches(line) -> section = table.matchEntire(line)!!.groupValues[1]
                    match != null -> {
                        val group = match.groups[1]!!
                        open = Triple(group.value, TextRange(offset + group.range.first, offset + group.range.last + 1), offset)
                    }
                }
            }
            if (open != null) {
                depth += depth(line)
                if (depth <= 0) {
                    entries += Entry(section, open.first, open.second, text.substring(open.third, end), TextRange(open.third, end))
                    open = null
                    depth = 0
                }
            }
            offset += line.length + 1
        }
        return entries
    }

    /** How many brackets and braces the line leaves open, outside strings and comments. */
    private fun depth(line: String): Int {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> if (c == '\\' && quote == '"') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' -> return depth
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
            i++
        }
        return depth
    }

    /** What one source file can use of a catalog: the words it has in quotes, and its `libs.a.b` accessors, split at the dots. */
    class Uses(val strings: Set<String>, val chains: List<List<String>>)

    fun uses(source: CharSequence) = Uses(quoted.findAll(source).mapTo(HashSet()) { it.groupValues[1] },
        accessor.findAll(source).map { it.groupValues[1].split('.') }.toList())

    fun unused(catalog: CharSequence, sources: List<String>): List<Entry> = unused(catalog, sources.map(::uses))

    /**
     * Entries that neither the catalog nor any of the [sources] use. A use is an accessor such as `libs.junit.api`
     * for the alias `junit-api`, or the alias in quotes, as in `libs.findLibrary("junit-api")`.
     */
    @JvmName("unusedIn")
    fun unused(catalog: CharSequence, sources: Collection<Uses>): List<Entry> {
        val entries = parse(catalog)
        val versions = entries.filter { it.section == "versions" }
        val libraries = entries.filter { it.section == "libraries" }
        val plugins = entries.filter { it.section == "plugins" }
        val referenced = (libraries + plugins).flatMapTo(HashSet()) { entry -> reference.findAll(entry.value).map { it.groupValues[1] } }
        val bundled = entries.filter { it.section == "bundles" }.flatMapTo(HashSet()) { entry ->
            quoted.findAll(entry.value.substringAfter('=')).map { it.groupValues[1] }
        }
        val strings = sources.flatMapTo(HashSet()) { it.strings }
        val chains = sources.flatMap { it.chains }
        // The longest alias that the chain starts with: `libs.junit.api.get()` uses `junit-api`, not `junit`.
        fun used(candidates: List<Entry>, chains: List<List<String>>): Set<Entry> {
            val segments = candidates.associateWith { it.alias.split('-', '_', '.') }
            return chains.mapNotNullTo(HashSet()) { chain ->
                segments.filter { (_, alias) -> alias.size <= chain.size && chain.subList(0, alias.size) == alias }.maxByOrNull { it.value.size }?.key
            }
        }
        val namespaces = setOf("versions", "plugins", "bundles")
        val usedVersions = used(versions, chains.filter { it.first() == "versions" }.map { it.drop(1) })
        val usedLibraries = used(libraries, chains.filter { it.first() !in namespaces })
        val usedPlugins = used(plugins, chains.filter { it.first() == "plugins" }.map { it.drop(1) })
        return versions.filter { it !in usedVersions && it.alias !in referenced && it.alias !in strings } +
            libraries.filter { it !in usedLibraries && it.alias !in bundled && it.alias !in strings } +
            plugins.filter { it !in usedPlugins && it.alias !in strings }
    }
}

internal object CatalogSources {
    private val createdCatalog = Regex("""\bcreate\s*\(\s*["'](\w+)["']""")

    private class Listing(val stamp: Long, val files: List<VirtualFile>)

    private val SCRIPTS = Key.create<Listing>("version-checker.catalog-scripts")
    private val CODE = Key.create<Listing>("version-checker.catalog-code")
    private val USES = Key.create<Pair<Long, CatalogEntries.Uses>>("version-checker.catalog-uses")
    private val codeExtensions = setOf("kt", "kts", "java", "groovy")

    /**
     * The build scripts under [root], kept while the file tree keeps its shape. `GradleProvider.related()` reads them
     * for every annotated script, so walking the whole build again when nothing was added or removed is wasted work.
     */
    fun scripts(root: VirtualFile): List<VirtualFile> = listing(root, SCRIPTS, sources = false) { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") }

    private fun listing(dir: VirtualFile, key: Key<Listing>, sources: Boolean, accept: (VirtualFile) -> Boolean): List<VirtualFile> {
        val stamp = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        dir.getUserData(key)?.takeIf { it.stamp == stamp }?.let { return it.files }
        return files(dir, sources, accept).also { dir.putUserData(key, Listing(stamp, it)) }
    }

    /** What [file] uses, scanned once per change of its text: the catalog is checked on every pass, and the build's sources rarely change. */
    private fun uses(file: VirtualFile): CatalogEntries.Uses {
        val stamp = FileStamps.document(file)
        file.getUserData(USES)?.takeIf { it.first == stamp }?.let { return it.second }
        return CatalogEntries.uses(text(file)).also { file.putUserData(USES, stamp to it) }
    }

    /**
     * What every file in the build at [root] that can use its default catalog `libs` uses: the build scripts, and the
     * code of `buildSrc` and of included builds that import the catalog. Null when the build sets up other catalogs,
     * because an entry could be used under another name.
     */
    fun find(root: VirtualFile): List<CatalogEntries.Uses>? {
        val scripts = scripts(root).filter { it.isValid }
        if (scripts.isEmpty()) return null
        val settings = scripts.filter { it.name.startsWith("settings.gradle") }.associateWith(::text)
        if (settings.values.any { "defaultLibrariesExtensionName" in it || ("versionCatalogs" in it && createdCatalog.findAll(it).any { m -> m.groupValues[1] != "libs" }) })
            return null
        val codeRoots = listOfNotNull(root.findChild("buildSrc")) + settings.filter { (file, text) -> file.parent != root && "libs.versions.toml" in text }.keys.map { it.parent }
        val code = codeRoots.distinct().flatMap { dir -> listing(dir, CODE, sources = true) { it.extension in codeExtensions } }.filter { it.isValid }
        return (scripts + code).distinct().map(::uses)
    }

    /**
     * Files under [dir], from the file system rather than the index, so the check also works before the build is imported.
     * Build output and hidden directories are skipped, and source directories unless [sources] is set.
     */
    fun files(dir: VirtualFile, sources: Boolean, accept: (VirtualFile) -> Boolean): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Any>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file == dir || !(file.name.startsWith('.') || file.name in skipped || (file.name == "src" && !sources))
                if (accept(file)) result += file
                return true
            }
        })
        return result
    }

    private val skipped = setOf("build", "out", "node_modules")

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: LoadTextUtil.loadText(file).toString()
}
