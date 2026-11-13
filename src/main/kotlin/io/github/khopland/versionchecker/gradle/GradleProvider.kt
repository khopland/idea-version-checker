package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.Declaration
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.FileStamps
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo

/**
 * Simple Gradle support. Build scripts and `libs.versions.toml` are read as text, so only literal versions and
 * versions in simple variables are found. Libraries and plugins come from the repositories the build declares, or
 * from Maven Central, Google and the Gradle Plugin Portal when it declares none.
 */
class GradleProvider internal constructor(private val newEnvironment: () -> GradleEnvironment) : DeclarationProvider {
    @Suppress("unused") // Created by the platform.
    constructor() : this(GradleEnvironment::system)

    @Volatile
    private var current: GradleEnvironment? = null

    /**
     * One environment for every script while the files in the Gradle user home stay the same, so the user's
     * `gradle.properties` and init scripts are not parsed again for every script on every highlighting pass.
     * `GradleBuildFiles` ties its cached builds to the instance, so a new one discards them.
     */
    private fun environment(): GradleEnvironment = current?.takeIf { it.isCurrent() } ?: newEnvironment().also { current = it }

    private class Collected(val stamp: Stamp, val declarations: List<Declaration>) {
        /** The file's text, and what its [GradleContext] gives it. */
        data class Stamp(val text: Long, val context: Any?)
    }

    override val fileNames = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "libs.versions.toml")

    override fun accepts(file: PsiFile) = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts") || file.name.endsWith(".versions.toml")

    /** The files in the Gradle user home, which have repositories and credentials for every build. */
    override fun stamp(file: PsiFile): Any = environment()

    override fun collect(file: PsiFile): List<Declaration> = file.virtualFile?.let { collect(it, environment()) }.orEmpty()

    private fun collect(file: VirtualFile, environment: GradleEnvironment): List<Declaration> {
        val catalog = file.name.endsWith(".toml")
        val context = if (catalog) GradleBuildFiles.forCatalog(file, environment) else GradleBuildFiles.forScript(file, environment)
        // `related()` collects every script of the build for each annotated one, so a script is scanned once per change.
        val stamp = Collected.Stamp(FileStamps.document(file), context.stamp)
        file.getUserData(COLLECTED)?.takeIf { it.stamp == stamp }?.let { return it.declarations }
        val declarations = if (catalog) GradleCatalog.collect(GradleBuildFiles.text(file), file, context)
            else GradleScripts.collect(GradleBuildFiles.script(file), file, context)
        file.putUserData(COLLECTED, Collected(stamp, declarations))
        return declarations
    }

    /** Other scripts of the build that use the same root project property or `gradle.properties` entry. */
    override fun related(file: PsiFile, declarations: List<Declaration>): List<Declaration> {
        val self = file.virtualFile ?: return emptyList()
        val shared = declarations.mapNotNull { it.edit }
            .filter { it.label != null && (it.file != self || it.file.parent == GradleBuildFiles.root(self)) }
            .mapTo(hashSetOf()) { it.key }
        if (shared.isEmpty()) return emptyList()
        val root = GradleBuildFiles.root(self) ?: return emptyList()
        val environment = environment()
        return CatalogSources.scripts(root)
            .filter { it != self && it.isValid }
            .flatMap {
                ProgressManager.checkCanceled()
                collect(it, environment)
            }
            .filter { it.edit?.key in shared }
    }

    companion object {
        private val COLLECTED = Key.create<Collected>("version-checker.gradle-declarations")
        private val googleGroups = listOf("androidx.", "com.android.", "com.google.android.", "com.google.firebase", "com.google.gms", "com.google.mlkit")

        /** [repos] are the ones the build declares, or null for Maven Central, or Google for Android groups. */
        fun library(group: String, artifact: String, repos: List<MavenRepo>? = null): Lookup = MavenLookup(group, artifact,
            repos ?: listOf(if (googleGroups.any { group.startsWith(it) || "$group." == it }) MavenRepo.GOOGLE else MavenRepo.CENTRAL))

        /** Every plugin has a marker artifact `<id>:<id>.gradle.plugin`, on the Plugin Portal unless [repos] are declared. */
        fun plugin(id: String, repos: List<MavenRepo>? = null): Lookup = MavenLookup(id, "$id.gradle.plugin", repos ?: listOf(MavenRepo.GRADLE_PLUGINS))

        fun literal(version: String) = version.isNotEmpty() && version[0].isDigit() && version.none { it in "$[]()*+, " }

        /** Libraries are Maven packages. Plugins pass a null [ecosystem]: vulnerability databases do not know plugin marker artifacts. */
        fun declaration(name: String, version: String, range: TextRange, lookup: Lookup, file: VirtualFile, label: String? = null,
                        editRange: TextRange = range, editFile: VirtualFile = file, ecosystem: Ecosystem? = Ecosystem.MAVEN) =
            Declaration(name, range, version, lookup, Edit(editFile, editRange, version, label), ecosystem = ecosystem)
    }
}

/**
 * Versions in build scripts: `"group:artifact:version"` strings and `id("x") version "v"`. The version may be a variable,
 * as in `"group:artifact:$x"`, `${property("x")}` or `version kotlinVersion`, set in the script, as an extra
 * property of the root project or in `gradle.properties`. The fix then changes it where it is set.
 */
internal object GradleScripts {
    private const val Q = """["']"""
    private const val REFERENCE = """\$(\w+)|\$\{\s*([\w.]+)\s*}|\$\{\s*(?:(?:project|rootProject)\.)?(?:property|findProperty|(?:extra|ext)\s*\[)\s*\(?\s*["'](\w+)["']\s*[)\]]\s*}"""
    private val coordinate = Regex("""$Q([\w.\-]+):([\w.\-]+):([^"':@$\s]+)$Q""")
    private val coordinateReference = Regex("""$Q([\w.\-]+):([\w.\-]+):($REFERENCE)$Q""")
    private const val PLUGIN_VERSION = """\s*version\s*\(?\s*(?:$Q([^"'$\s]+)$Q|$Q($REFERENCE)$Q|([A-Za-z_]\w*)\b(?![.(]))"""
    private val pluginId = Regex("""\bid\s*\(?\s*$Q([\w.\-]+)$Q\s*\)?$PLUGIN_VERSION""")
    private val kotlinPlugin = Regex("""\bkotlin\s*\(\s*$Q([\w.\-]+)$Q\s*\)$PLUGIN_VERSION""")
    private val qualified = Regex("""^(?:(?:project|rootProject)\.)?(?:(?:ext|extra)\.)?(\w+)$""")

    fun collect(text: String, file: VirtualFile, context: GradleContext = GradleContext()) = collect(ScriptText(text), file, context)

    fun collect(script: ScriptText, file: VirtualFile, context: GradleContext): List<Declaration> {
        val code = script.code
        val result = mutableListOf<Declaration>()
        val own by lazy { GradleVariables.definitions(script, file) }
        fun range(match: MatchResult, group: Int) = match.groups[group]!!.range.let { TextRange(it.first, it.last + 1) }
        fun variable(match: MatchResult, first: Int): GradleVariable? {
            val name = match.groups[first]?.value ?: match.groups[first + 2]?.value
                ?: match.groups[first + 1]?.value?.let { qualified.matchEntire(it)?.groupValues?.get(1) } ?: return null
            return own[name] ?: context.variable(name)
        }
        /** A literal version in [literal], or a variable reference whose groups start at [reference]. */
        fun add(name: String, match: MatchResult, literal: Int?, reference: Int?, lookup: () -> Lookup, bare: Int? = null,
                ecosystem: Ecosystem? = Ecosystem.MAVEN) {
            literal?.let { match.groups[it] }?.let { version ->
                if (GradleProvider.literal(version.value))
                    result += GradleProvider.declaration(name, version.value, range(match, literal), lookup(), file, ecosystem = ecosystem)
                return
            }
            val (group, variable) = when {
                reference != null && match.groups[reference] != null -> reference to variable(match, reference + 1)
                bare != null && match.groups[bare] != null -> bare to match.groups[bare]!!.value.let { own[it] ?: context.variable(it) }
                else -> return
            }
            if (variable == null || !GradleProvider.literal(variable.value)) return
            result += GradleProvider.declaration(name, variable.value, range(match, group), lookup(), file, variable.name, variable.range, variable.file, ecosystem)
        }
        fun libraries(offset: Int) = if (script.inside(offset, "buildscript")) context.buildscript else context.libraries
        coordinate.findAll(code).forEach { match ->
            val (group, artifact) = match.destructured
            add("$group:$artifact", match, 3, null, { GradleProvider.library(group, artifact, libraries(match.range.first)) })
        }
        coordinateReference.findAll(code).forEach { match ->
            val (group, artifact) = match.destructured
            add("$group:$artifact", match, null, 3, { GradleProvider.library(group, artifact, libraries(match.range.first)) })
        }
        pluginId.findAll(code).forEach { match ->
            val id = match.groupValues[1]
            add("plugin $id", match, 2, 3, { GradleProvider.plugin(id, context.plugins) }, bare = 7, ecosystem = null)
        }
        kotlinPlugin.findAll(code).forEach { match ->
            val id = "org.jetbrains.kotlin.${match.groupValues[1]}"
            add("plugin $id", match, 2, 3, { GradleProvider.plugin(id, context.plugins) }, bare = 7, ecosystem = null)
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

    fun collect(text: String, file: VirtualFile, context: GradleContext = GradleContext()): List<Declaration> {
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
        fun add(name: String, lookup: Lookup, line: Line, ecosystem: Ecosystem? = Ecosystem.MAVEN) {
            val reference = value(line, field("version.ref")) ?: value(line, Regex("""version\s*=\s*\{\s*ref\s*=\s*"([^"]*)""""))
            if (reference != null) {
                val target = versions[reference.text] ?: return
                if (GradleProvider.literal(target.text))
                    result += GradleProvider.declaration(name, target.text, reference.range, lookup, file, reference.text, target.range, ecosystem = ecosystem)
                return
            }
            val version = value(line, field("version")) ?: return
            if (GradleProvider.literal(version.text)) result += GradleProvider.declaration(name, version.text, version.range, lookup, file, ecosystem = ecosystem)
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
                                GradleProvider.library(shorthand[0], shorthand[1], context.libraries), file)
                        }
                        continue
                    }
                    val module = value(line, field("module"))?.text?.split(':')?.takeIf { it.size == 2 }
                        ?: listOfNotNull(value(line, field("group"))?.text, value(line, field("name"))?.text).takeIf { it.size == 2 } ?: continue
                    add("${module[0]}:${module[1]}", GradleProvider.library(module[0], module[1], context.libraries), line)
                }
                "plugins" -> {
                    val shorthand = quoted.matchEntire(raw.value)?.groupValues?.get(1)?.split(':')
                    if (shorthand != null) {
                        if (shorthand.size == 2 && GradleProvider.literal(shorthand[1])) {
                            val start = line.start + raw.range.first + 1 + shorthand[0].length + 1
                            result += GradleProvider.declaration("plugin ${shorthand[0]}", shorthand[1], TextRange.from(start, shorthand[1].length),
                                GradleProvider.plugin(shorthand[0], context.plugins), file, ecosystem = null)
                        }
                        continue
                    }
                    val id = value(line, field("id"))?.text ?: continue
                    add("plugin $id", GradleProvider.plugin(id, context.plugins), line, null)
                }
            }
        }
        return result
    }
}
