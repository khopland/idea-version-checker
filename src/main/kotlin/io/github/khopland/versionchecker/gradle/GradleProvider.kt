package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo

/**
 * Simple Gradle support. Build scripts and `libs.versions.toml` are read as text, so only literal
 * versions in the common notations are found. Libraries come from Maven Central or Google, and plugins
 * from the Gradle Plugin Portal.
 */
class GradleProvider : DeclarationProvider {
    override val fileNames = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "libs.versions.toml")

    override fun accepts(file: PsiFile) = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts") || file.name.endsWith(".versions.toml")

    override fun collect(file: PsiFile): List<Declaration> {
        val vfile = file.virtualFile ?: return emptyList()
        return if (file.name.endsWith(".toml")) GradleCatalog.collect(file.text, vfile) else GradleScripts.collect(file.text, vfile)
    }

    companion object {
        private val googleGroups = listOf("androidx.", "com.android.", "com.google.android.", "com.google.firebase", "com.google.gms", "com.google.mlkit")

        fun library(group: String, artifact: String): Lookup = MavenLookup(group, artifact,
            listOf(if (googleGroups.any { group.startsWith(it) || "$group." == it }) MavenRepo.GOOGLE else MavenRepo.CENTRAL))

        /** The Plugin Portal publishes a marker artifact `<id>:<id>.gradle.plugin` for every plugin. */
        fun plugin(id: String): Lookup = MavenLookup(id, "$id.gradle.plugin", listOf(MavenRepo.GRADLE_PLUGINS))

        fun literal(version: String) = version.isNotEmpty() && version[0].isDigit() && version.none { it in "$[]()*+, " }

        fun declaration(name: String, version: String, range: TextRange, lookup: Lookup, file: VirtualFile, label: String? = null,
                        editRange: TextRange = range) =
            Declaration(name, range, version, lookup, Edit(file, editRange, version, label))
    }
}

internal object GradleScripts {
    private const val Q = """["']"""
    private val coordinate = Regex("""$Q([\w.\-]+):([\w.\-]+):([^"':@$\s]+)$Q""")
    private val pluginId = Regex("""\bid\s*\(?\s*$Q([\w.\-]+)$Q\s*\)?\s*version\s*\(?\s*$Q([^"'$\s]+)$Q""")
    private val kotlinPlugin = Regex("""\bkotlin\s*\(\s*$Q([\w.\-]+)$Q\s*\)\s*version\s*\(?\s*$Q([^"'$\s]+)$Q""")

    fun collect(text: String, file: VirtualFile): List<Declaration> {
        val result = mutableListOf<Declaration>()
        fun commented(offset: Int): Boolean {
            val line = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset).trimStart()
            return line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
        }
        fun range(match: MatchResult, group: Int) = match.groups[group]!!.range.let { TextRange(it.first, it.last + 1) }
        coordinate.findAll(text).forEach { match ->
            val (group, artifact, version) = match.destructured
            if (!commented(match.range.first) && GradleProvider.literal(version))
                result += GradleProvider.declaration("$group:$artifact", version, range(match, 3), GradleProvider.library(group, artifact), file)
        }
        pluginId.findAll(text).forEach { match ->
            val (id, version) = match.destructured
            if (!commented(match.range.first) && GradleProvider.literal(version))
                result += GradleProvider.declaration("plugin $id", version, range(match, 2), GradleProvider.plugin(id), file)
        }
        kotlinPlugin.findAll(text).forEach { match ->
            val (name, version) = match.destructured
            val id = "org.jetbrains.kotlin.$name"
            if (!commented(match.range.first) && GradleProvider.literal(version))
                result += GradleProvider.declaration("plugin $id", version, range(match, 2), GradleProvider.plugin(id), file)
        }
        return result.sortedBy { it.range.startOffset }
    }
}

/** `[versions]`, `[libraries]` and `[plugins]` in a version catalog, one entry per line. */
internal object GradleCatalog {
    private val table = Regex("""^\s*\[([\w.\-]+)]\s*(?:#.*)?$""")
    private val entry = Regex("""^\s*([\w.\-]+)\s*=\s*(.+?)\s*(?:#.*)?$""")
    private val quoted = Regex("""^"([^"]*)"$""")
    private fun field(name: String) = Regex("""(?:^|[{,\s])${Regex.escape(name)}\s*=\s*"([^"]*)"""")

    private class Line(val start: Int, val text: String)
    private class Value(val text: String, val range: TextRange)

    fun collect(text: String, file: VirtualFile): List<Declaration> {
        val lines = mutableListOf<Pair<String, Line>>()
        var section = ""
        var offset = 0
        for (line in text.split('\n')) {
            table.matchEntire(line)?.let { section = it.groupValues[1] } ?: lines.add(section to Line(offset, line))
            offset += line.length + 1
        }
        fun value(line: Line, pattern: Regex): Value? = pattern.find(line.text)?.groups?.get(1)?.let {
            Value(it.value, TextRange(line.start + it.range.first, line.start + it.range.last + 1))
        }
        val versions = lines.filter { it.first == "versions" }.mapNotNull { (_, line) ->
            val match = entry.matchEntire(line.text) ?: return@mapNotNull null
            val raw = match.groups[2]!!
            val literal = quoted.matchEntire(raw.value) ?: return@mapNotNull null
            match.groupValues[1] to Value(literal.groupValues[1], TextRange.from(line.start + raw.range.first + 1, literal.groupValues[1].length))
        }.toMap()

        val result = mutableListOf<Declaration>()
        fun add(name: String, lookup: Lookup, line: Line) {
            val reference = value(line, field("version.ref")) ?: value(line, Regex("""version\s*=\s*\{\s*ref\s*=\s*"([^"]*)""""))
            if (reference != null) {
                val target = versions[reference.text] ?: return
                if (GradleProvider.literal(target.text))
                    result += GradleProvider.declaration(name, target.text, reference.range, lookup, file, reference.text, target.range)
                return
            }
            val version = value(line, field("version")) ?: return
            if (GradleProvider.literal(version.text)) result += GradleProvider.declaration(name, version.text, version.range, lookup, file)
        }
        for ((section, line) in lines) {
            val match = entry.matchEntire(line.text) ?: continue
            if (line.text.trimStart().startsWith("#")) continue
            val raw = match.groups[2]!!
            when (section) {
                "libraries" -> {
                    val shorthand = quoted.matchEntire(raw.value)?.groupValues?.get(1)?.split(':')
                    if (shorthand != null) {
                        if (shorthand.size == 3 && GradleProvider.literal(shorthand[2])) {
                            val start = line.start + raw.range.first + 1 + shorthand[0].length + shorthand[1].length + 2
                            result += GradleProvider.declaration("${shorthand[0]}:${shorthand[1]}", shorthand[2], TextRange.from(start, shorthand[2].length),
                                GradleProvider.library(shorthand[0], shorthand[1]), file)
                        }
                        continue
                    }
                    val module = value(line, field("module"))?.text?.split(':')?.takeIf { it.size == 2 }
                        ?: listOfNotNull(value(line, field("group"))?.text, value(line, field("name"))?.text).takeIf { it.size == 2 } ?: continue
                    add("${module[0]}:${module[1]}", GradleProvider.library(module[0], module[1]), line)
                }
                "plugins" -> {
                    val shorthand = quoted.matchEntire(raw.value)?.groupValues?.get(1)?.split(':')
                    if (shorthand != null) {
                        if (shorthand.size == 2 && GradleProvider.literal(shorthand[1])) {
                            val start = line.start + raw.range.first + 1 + shorthand[0].length + 1
                            result += GradleProvider.declaration("plugin ${shorthand[0]}", shorthand[1], TextRange.from(start, shorthand[1].length),
                                GradleProvider.plugin(shorthand[0]), file)
                        }
                        continue
                    }
                    val id = value(line, field("id"))?.text ?: continue
                    add("plugin $id", GradleProvider.plugin(id), line)
                }
            }
        }
        return result
    }
}
