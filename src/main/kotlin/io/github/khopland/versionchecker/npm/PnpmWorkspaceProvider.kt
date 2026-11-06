package io.github.khopland.versionchecker.npm

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
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

    private val overrides = Regex("""(?m)^overrides\s*:""")

    /** Whether [file] has a top-level `overrides` section, which pnpm 10 reads instead of `pnpm.overrides` in `package.json`. */
    fun hasOverrides(file: VirtualFile): Boolean = try {
        overrides.containsMatchIn(VfsUtilCore.loadText(file))
    } catch (_: java.io.IOException) {
        false
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
