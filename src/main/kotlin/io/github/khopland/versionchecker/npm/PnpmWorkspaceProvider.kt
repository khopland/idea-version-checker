package io.github.khopland.versionchecker.npm

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Edit

/**
 * `pnpm-workspace.yaml`: the default `catalog`, named `catalogs` and `overrides`. Packages use a catalog version with
 * `"catalog:"` in `package.json`, so the version is only written here. The file is read as text, one entry per line.
 */
class PnpmWorkspaceProvider : DeclarationProvider {
    override val fileNames = listOf("pnpm-workspace.yaml")

    override fun accepts(file: PsiFile) = file.name == "pnpm-workspace.yaml"

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        val registries by lazy { NpmPackages.registries(vfile) }
        return PnpmWorkspace.entries(file.text).mapNotNull { entry -> declaration(entry, vfile) { registries } }
    }

    private fun declaration(entry: PnpmWorkspace.Entry, file: VirtualFile, registries: () -> RegistryConfig): Declaration? {
        val name = if (entry.section == "overrides") NpmPackages.target(entry.key) else entry.key
        name ?: return null
        val spec = NpmSpec.parse(entry.value) ?: return null
        val label = if (entry.section == "overrides") "$name (override)" else name
        return Declaration(label, entry.range, spec.version, NpmLookup(name, registries().registry(name)), Edit(file, entry.range, entry.value) { spec.prefix + it },
            ecosystem = Ecosystem.NPM.takeUnless { entry.section == "overrides" })
    }
}

internal object PnpmWorkspace {
    /** One `key: value` line. [section] is `catalog`, `overrides` or the catalog name under `catalogs`. [range] excludes quotes. */
    class Entry(val section: String, val key: String, val value: String, val range: TextRange)

    private val line = Regex("""^( *)(?:"([^"]+)"|'([^']+)'|([^\s:#'"][^:#]*?))\s*:(?:\s+(?:"([^"]*)"|'([^']*)'|([^\s#'"][^#]*?)))?\s*(?:#.*)?$""")

    const val FILE_NAME = "pnpm-workspace.yaml"
    private val overridesHeader = Regex("""^overrides\s*:\s*(?:#.*)?$""")
    private val inlineOverrides = Regex("""(?m)^overrides[ \t]*:[ \t]*[^\s#]""")

    /**
     * Whether `overrides` in [text] can take [key]: neither it nor an override of every copy of [name] is there, and the
     * section is not written inline, as in `overrides: {}`.
     */
    fun canAddOverride(text: String, name: String, key: String): Boolean =
        !inlineOverrides.containsMatchIn(text) && entries(text).none { it.section == "overrides" && (it.key == name || it.key == key) }

    /** Whether [text] has an `overrides` section. */
    fun hasOverrides(text: String) = text.lineSequence().any { overridesHeader.matches(it.removeSuffix("\r")) } || inlineOverrides.containsMatchIn(text)

    /** The text of [file], unsaved edits included, or null when it cannot be read. */
    fun text(file: VirtualFile): String? = FileDocumentManager.getInstance().getDocument(file)?.text

    /**
     * [text] with `'key': 'version'` at the end of its `overrides` section, which is added at the end when missing.
     * Quoted, since a range such as `^1.2.3` is not a plain YAML value everywhere.
     */
    fun withOverride(text: String, key: String, version: String): String {
        val entry = "'$key': '$version'"
        if ("\r\n" in text) return withOverride(text.replace("\r\n", "\n"), key, version).replace("\n", "\r\n")
        val lines = text.split('\n').toMutableList()
        val header = lines.indexOfFirst { overridesHeader.matches(it.removeSuffix("\r")) }
        if (header < 0) {
            val head = text.trimEnd('\n', '\r')
            return (if (head.isEmpty()) "" else "$head\n\n") + "overrides:\n  $entry\n"
        }
        var end = header + 1
        var indent: String? = null
        while (end < lines.size && (lines[end].isBlank() || lines[end].first().isWhitespace())) {
            if (indent == null && lines[end].isNotBlank()) indent = lines[end].takeWhile { it == ' ' || it == '\t' }
            end++
        }
        while (end > header + 1 && lines[end - 1].isBlank()) end--
        lines.add(end, (indent ?: "  ") + entry)
        return lines.joinToString("\n")
    }

    fun entries(text: String): List<Entry> {
        val result = mutableListOf<Entry>()
        // The keys that enclose the current line, with their indentation.
        val stack = ArrayDeque<Pair<Int, String>>()
        var offset = 0
        for (raw in text.split('\n')) {
            val content = raw.removeSuffix("\r")
            val match = line.matchEntire(content)
            if (match != null) {
                val indent = match.groupValues[1].length
                while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
                val key = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]!!.value.trim()
                val value = (5..7).firstNotNullOfOrNull { match.groups[it] }
                if (value == null) stack.addLast(indent to key)
                else {
                    val path = stack.map { it.second }
                    val section = when {
                        path == listOf("catalog") || path == listOf("overrides") -> path[0]
                        path.size == 2 && path[0] == "catalogs" -> path[1]
                        else -> null
                    }
                    if (section != null) result += Entry(section, key, value.value, TextRange(offset + value.range.first, offset + value.range.last + 1))
                }
            }
            offset += raw.length + 1
        }
        return result
    }
}
