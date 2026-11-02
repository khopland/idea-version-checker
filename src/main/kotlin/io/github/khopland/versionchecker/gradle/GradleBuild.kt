package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.repo.MavenRepo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A version that a script names instead of writing it, and where it is set. */
internal class GradleVariable(val name: String, val value: String, val file: VirtualFile, val range: TextRange)

/**
 * What one build file needs from the rest of its build. A null repository list means none is declared, so libraries
 * come from Maven Central or Google, and plugins from the Gradle Plugin Portal.
 */
internal class GradleContext(
    val libraries: List<MavenRepo>? = null,
    val plugins: List<MavenRepo>? = null,
    /** Repositories for `classpath` dependencies in `buildscript { }`. */
    val buildscript: List<MavenRepo>? = null,
    /** A variable the script does not set itself: an extra property of the root project, or a Gradle property. */
    val variable: (String) -> GradleVariable? = { null },
)

/** `key=value` lines of a `.properties` file, with the range of each value. */
internal object GradleProperties {
    class Entry(val name: String, val value: String, val range: TextRange)

    private val line = Regex("""^[ \t]*([^#!\s=:][^=:\s]*)[ \t]*[=:][ \t]*(.*?)[ \t]*$""")

    fun parse(text: CharSequence): List<Entry> {
        val result = mutableListOf<Entry>()
        var offset = 0
        for (text in text.split('\n')) {
            val content = text.removeSuffix("\r")
            line.matchEntire(content)?.let { match ->
                val value = match.groups[2]!!
                result += Entry(match.groupValues[1], value.value, TextRange(offset + value.range.first, offset + value.range.first + value.value.length))
            }
            offset += text.length + 1
        }
        return result
    }
}

/**
 * Where a build script sets a version itself: `val x = "1.0"` and `def x = '1.0'`, which only the script sees, and the
 * extra properties `ext.x = '1.0'`, `ext { x = '1.0' }`, `extra["x"] = "1.0"` and `val x by extra("1.0")`, which
 * subprojects see when the root script sets them.
 */
internal object GradleVariables {
    private const val VALUE = """["']([^"'$\n]*)["']"""
    private val local = Regex("""\b(?:val|var|def|String)\s+(\w+)\s*(?::\s*String\s*)?=\s*$VALUE""")
    private val extra = listOf(
        Regex("""\b(?:(?:project|rootProject)\.)?ext\.(\w+)\s*=\s*$VALUE"""),
        Regex("""\b(?:(?:project|rootProject)\.)?(?:extra|ext)\s*\[\s*["'](\w+)["']\s*]\s*=\s*$VALUE"""),
        Regex("""\b(?:(?:project|rootProject)\.)?(?:extra|ext)\.set\s*\(\s*["'](\w+)["']\s*,\s*$VALUE"""),
        Regex("""\bval\s+(\w+)\s*(?::\s*String\s*)?by\s+(?:(?:project|rootProject)\.)?extra\s*\(\s*$VALUE\s*\)"""),
    )
    private val extBlockEntry = Regex("""(?m)^\s*(\w+)\s*=\s*$VALUE""")
    private val extBlockSet = Regex("""\bset\s*\(\s*["'](\w+)["']\s*,\s*$VALUE""")

    fun definitions(script: ScriptText, file: VirtualFile, extraOnly: Boolean = false): Map<String, GradleVariable> {
        val found = mutableListOf<Pair<Int, GradleVariable>>()
        fun add(match: MatchResult, base: Int = 0) {
            val value = match.groups[2]!!
            found += match.range.first + base to GradleVariable(match.groupValues[1], value.value, file,
                TextRange(base + value.range.first, base + value.range.last + 1))
        }
        if (!extraOnly) local.findAll(script.code).forEach { add(it) }
        extra.forEach { pattern -> pattern.findAll(script.code).forEach { add(it) } }
        for (block in script.blocks) {
            if (block.name != "ext") continue
            val own = script.own(block)
            extBlockEntry.findAll(own).forEach { add(it, block.open + 1) }
            extBlockSet.findAll(own).forEach { add(it, block.open + 1) }
        }
        val result = linkedMapOf<String, GradleVariable>()
        found.sortedBy { it.first }.forEach { (_, variable) -> result.putIfAbsent(variable.name, variable) }
        return result
    }
}

/**
 * Gradle properties and init scripts from outside the build: `ORG_GRADLE_PROJECT_<name>` environment variables,
 * `gradle.properties` and `init.gradle(.kts)` and `init.d` in the Gradle user home.
 */
internal class GradleEnvironment(val env: (String) -> String?, private val userHome: Path?) {
    private val userProperties by lazy {
        userHome?.let { UserFiles.text(it.resolve("gradle.properties")) }?.let(GradleProperties::parse).orEmpty().associate { it.name to it.value }
    }

    /** Init scripts often declare a company repository for every build. */
    val initScripts: List<String> by lazy {
        val home = userHome ?: return@lazy emptyList()
        val named = listOf("init.gradle", "init.gradle.kts").map(home::resolve)
        val dir = home.resolve("init.d")
        val inDir = runCatching { Files.list(dir).use { files -> files.filter { it.fileName.toString().let { n -> n.endsWith(".gradle") || n.endsWith(".gradle.kts") } }.sorted().toList() } }
            .getOrDefault(emptyList())
        (named + inDir).mapNotNull(UserFiles::text)
    }

    /** A property from outside the build, which wins over the build's own `gradle.properties`. */
    fun property(name: String): String? = env("ORG_GRADLE_PROJECT_$name") ?: userProperties[name]

    companion object {
        fun system(): GradleEnvironment {
            val home = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: System.getProperty("user.home")?.let { Path.of(it, ".gradle") }
            return GradleEnvironment(System::getenv, home)
        }
    }

    /** Files outside the project, cached until they change. */
    private object UserFiles {
        private val cache = ConcurrentHashMap<Path, Pair<Long, String>>()

        fun text(path: Path): String? {
            val stamp = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull() ?: return null
            cache[path]?.takeIf { it.first == stamp }?.let { return it.second }
            val text = runCatching { Files.readString(path) }.getOrNull() ?: return null
            cache[path] = stamp to text
            return text
        }
    }
}

/**
 * Finds the rest of the build for one build file: the settings script and root build script in the directory that has
 * the settings script, `gradle.properties` files, and init scripts. Repositories follow Gradle's rules: project
 * repositories win unless `repositoriesMode` prefers the settings, and plugins only use `pluginManagement` when it
 * declares repositories.
 */
internal object GradleBuildFiles {
    private val settingsNames = listOf("settings.gradle.kts", "settings.gradle")
    private val buildNames = listOf("build.gradle.kts", "build.gradle")

    fun root(file: VirtualFile): VirtualFile? {
        val dir = file.parent ?: return null
        return generateSequence(dir) { it.parent }.firstOrNull { d -> settingsNames.any { d.findChild(it) != null } } ?: dir
    }

    fun forScript(file: VirtualFile, script: ScriptText, environment: GradleEnvironment): GradleContext {
        val build = Build.of(file, environment) ?: return GradleContext()
        val self = build.declared(script)
        val isSettings = file.name in settingsNames
        val settings = if (isSettings) self else build.settings
        val root = if (file == build.rootScript?.first || isSettings) DeclaredRepos.NONE else build.rootRepos
        val settingsRepos = settings[RepoScope.SETTINGS] + build.init[RepoScope.SETTINGS]
        val project = self[RepoScope.PROJECT, RepoScope.SHARED] + root[RepoScope.SHARED] + build.init[RepoScope.PROJECT, RepoScope.SHARED]
        val libraries = when {
            isSettings -> settingsRepos
            settings.preferSettings -> settingsRepos.ifEmpty { project }
            else -> project.ifEmpty { settingsRepos }
        }
        val rootExtras = if (isSettings || file == build.rootScript?.first) emptyMap()
            else build.rootScript?.let { (vfile, text) -> GradleVariables.definitions(text, vfile, extraOnly = true) }.orEmpty()
        val properties = build.propertyFiles(file.parent)
        return GradleContext(
            libraries.distinctBy { it.base }.ifEmpty { null },
            build.plugins(settings).ifEmpty { null },
            self[RepoScope.BUILDSCRIPT].distinctBy { it.base }.ifEmpty { null },
        ) { name -> rootExtras[name] ?: properties.firstNotNullOfOrNull { it[name] } }
    }

    fun forCatalog(file: VirtualFile, environment: GradleEnvironment): GradleContext {
        val build = Build.of(file, environment) ?: return GradleContext()
        val settingsRepos = build.settings[RepoScope.SETTINGS] + build.init[RepoScope.SETTINGS]
        val project = build.rootRepos[RepoScope.PROJECT, RepoScope.SHARED] + build.init[RepoScope.PROJECT, RepoScope.SHARED]
        // The catalog serves every project, so the repositories for the whole build come first.
        val libraries = settingsRepos.ifEmpty { project }
        return GradleContext(libraries.distinctBy { it.base }.ifEmpty { null }, build.plugins(build.settings).ifEmpty { null })
    }

    private class Build(val dir: VirtualFile, val environment: GradleEnvironment) {
        val rootProperties by lazy { dir.findChild("gradle.properties")?.let { GradleProperties.parse(text(it)) }.orEmpty().associate { it.name to it.value } }

        /** Gradle properties for credentials and URLs: the environment and user home first, then the build. */
        fun property(name: String) = environment.property(name) ?: rootProperties[name]

        fun declared(script: ScriptText) = GradleRepositories.parse(script, GradleValues(script, ::property, environment.env))

        private fun scriptIn(names: List<String>) = names.firstNotNullOfOrNull { dir.findChild(it) }?.let { it to ScriptText(text(it)) }

        val settings by lazy { scriptIn(settingsNames)?.let { declared(it.second) } ?: DeclaredRepos.NONE }
        val rootScript by lazy { scriptIn(buildNames) }
        val rootRepos by lazy { rootScript?.let { declared(it.second) } ?: DeclaredRepos.NONE }
        val init by lazy {
            val parsed = environment.initScripts.map { declared(ScriptText(it)) }
            DeclaredRepos(parsed.flatMap { it.repos }, false)
        }

        fun plugins(settings: DeclaredRepos) = (settings[RepoScope.PLUGINS] + init[RepoScope.PLUGINS]).distinctBy { it.base }

        /** `gradle.properties` from the script's directory up to the root, nearest first. */
        fun propertyFiles(from: VirtualFile?): List<Map<String, GradleVariable>> =
            generateSequence(from) { if (it == dir) null else it.parent }
                .mapNotNull { it.findChild("gradle.properties") }
                .map { file -> GradleProperties.parse(text(file)).associate { it.name to GradleVariable(it.name, it.value, file, it.range) } }
                .toList()

        companion object {
            fun of(file: VirtualFile, environment: GradleEnvironment): Build? {
                val root = root(file) ?: return null
                // A catalog in `gradle/` belongs to the build in the directory above.
                val dir = if (file.name.endsWith(".versions.toml") && file.parent == root && root.name == "gradle") root.parent ?: root else root
                return Build(dir, environment)
            }
        }
    }

    fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: LoadTextUtil.loadText(file).toString()
}
