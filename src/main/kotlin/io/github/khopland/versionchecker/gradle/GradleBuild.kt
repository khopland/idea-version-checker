package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.FileStamps
import io.github.khopland.versionchecker.repo.MavenRepo
import java.lang.ref.SoftReference
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
    /** Equal for two contexts that were made from the same build and property files, so what was collected with one fits the other. */
    val stamp: Any? = null,
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
    /** The files in the Gradle user home when this environment was made. */
    private val stamp = stamp()

    /** Whether the files in the Gradle user home are still the ones this environment read. What was made with it is out of date otherwise. */
    fun isCurrent() = stamp == stamp()

    private fun userFiles(): List<Path> {
        val home = userHome ?: return emptyList()
        val inDir = runCatching { Files.list(home.resolve("init.d")).use { files -> files.filter { it.fileName.toString().let { n -> n.endsWith(".gradle") || n.endsWith(".gradle.kts") } }.sorted().toList() } }
            .getOrDefault(emptyList())
        return listOf("gradle.properties", "init.gradle", "init.gradle.kts").map(home::resolve) + inDir
    }

    private fun stamp() = userFiles().map { it to runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }

    private val userProperties by lazy {
        userHome?.let { UserFiles.text(it.resolve("gradle.properties")) }?.let(GradleProperties::parse).orEmpty().associate { it.name to it.value }
    }

    /** Init scripts often declare a company repository for every build. */
    val initScripts: List<String> by lazy { userFiles().filter { it.fileName.toString() != "gradle.properties" }.mapNotNull(UserFiles::text) }

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

    private class Stamped<T>(val stamp: Long, val value: T)

    /** A script's text is kept twice by its parse, so a build with many scripts gives it back when memory is short. */
    private val SCRIPT = Key.create<Stamped<SoftReference<ScriptText>>>("version-checker.gradle-script")
    private val PROPERTIES = Key.create<Stamped<Map<String, GradleVariable>>>("version-checker.gradle-properties")

    /** A file and the stamp of its text. */
    private data class FileStamp(val file: VirtualFile, val stamp: Long) {
        constructor(file: VirtualFile) : this(file, FileStamps.document(file))
    }

    /** The files a [Build] parses, so an edit to one of them, or another file taking its place, discards the build. */
    private data class BuildStamp(val settings: FileStamp?, val root: FileStamp?, val properties: FileStamp?)

    /** What a [GradleContext] was made from: the build, and the `gradle.properties` files between the script and the root. */
    private data class ContextStamp(val build: Build, val properties: List<FileStamp>)

    private class CachedBuild(val environment: GradleEnvironment, val stamp: BuildStamp, val build: Build)

    private val BUILD = Key.create<CachedBuild>("version-checker.gradle-build")

    fun root(file: VirtualFile): VirtualFile? {
        val dir = file.parent ?: return null
        return generateSequence(dir) { it.parent }.firstOrNull { d -> settingsNames.any { d.findChild(it) != null } } ?: dir
    }

    /** The parse of [file], kept while its text keeps its stamp. */
    fun script(file: VirtualFile): ScriptText {
        val stamp = FileStamps.document(file)
        file.getUserData(SCRIPT)?.takeIf { it.stamp == stamp }?.value?.get()?.let { return it }
        val script = ScriptText(text(file))
        file.putUserData(SCRIPT, Stamped(stamp, SoftReference(script)))
        return script
    }

    /** The entries of a `gradle.properties` file by name, kept while its text keeps its stamp. */
    fun properties(file: VirtualFile): Map<String, GradleVariable> {
        val stamp = FileStamps.document(file)
        file.getUserData(PROPERTIES)?.takeIf { it.stamp == stamp }?.let { return it.value }
        val properties = GradleProperties.parse(text(file)).associate { it.name to GradleVariable(it.name, it.value, file, it.range) }
        file.putUserData(PROPERTIES, Stamped(stamp, properties))
        return properties
    }

    fun forScript(file: VirtualFile, environment: GradleEnvironment): GradleContext {
        val build = build(file, environment) ?: return GradleContext()
        val self = build.declared(file)
        val isSettings = file.name in settingsNames
        val settings = if (isSettings) self else build.settings
        val isRoot = file == build.rootScript
        val root = if (isRoot || isSettings) DeclaredRepos.NONE else build.rootRepos
        val settingsRepos = settings[RepoScope.SETTINGS] + build.init[RepoScope.SETTINGS]
        val project = self[RepoScope.PROJECT, RepoScope.SHARED] + root[RepoScope.SHARED] + build.init[RepoScope.PROJECT, RepoScope.SHARED]
        val libraries = when {
            isSettings -> settingsRepos
            settings.preferSettings -> settingsRepos.ifEmpty { project }
            else -> project.ifEmpty { settingsRepos }
        }
        val rootExtras = if (isRoot || isSettings) emptyMap() else build.rootExtras
        val propertyFiles = build.propertyFiles(file.parent)
        val properties = propertyFiles.map(::properties)
        return GradleContext(
            libraries.distinctBy { it.base }.ifEmpty { null },
            build.plugins(settings).ifEmpty { null },
            self[RepoScope.BUILDSCRIPT].distinctBy { it.base }.ifEmpty { null },
            { name -> rootExtras[name] ?: properties.firstNotNullOfOrNull { it[name] } },
            ContextStamp(build, propertyFiles.map(::FileStamp)))
    }

    fun forCatalog(file: VirtualFile, environment: GradleEnvironment): GradleContext {
        val build = build(file, environment) ?: return GradleContext()
        val settingsRepos = build.settings[RepoScope.SETTINGS] + build.init[RepoScope.SETTINGS]
        val project = build.rootRepos[RepoScope.PROJECT, RepoScope.SHARED] + build.init[RepoScope.PROJECT, RepoScope.SHARED]
        // The catalog serves every project, so the repositories for the whole build come first.
        val libraries = settingsRepos.ifEmpty { project }
        return GradleContext(libraries.distinctBy { it.base }.ifEmpty { null }, build.plugins(build.settings).ifEmpty { null },
            stamp = ContextStamp(build, emptyList()))
    }

    /**
     * The build that [file] belongs to, reused while its settings script, root build script and root
     * `gradle.properties` keep their stamps. It is tied to one [GradleEnvironment]: the user's properties and init
     * scripts come from there, and another provider can have another environment.
     */
    private fun build(file: VirtualFile, environment: GradleEnvironment): Build? {
        val root = root(file) ?: return null
        // A catalog in `gradle/` belongs to the build in the directory above.
        val dir = if (file.name.endsWith(".versions.toml") && file.parent == root && root.name == "gradle") root.parent ?: root else root
        val stamp = BuildStamp(
            settings = settingsNames.firstNotNullOfOrNull(dir::findChild)?.let(::FileStamp),
            root = buildNames.firstNotNullOfOrNull(dir::findChild)?.let(::FileStamp),
            properties = dir.findChild("gradle.properties")?.let(::FileStamp))
        dir.getUserData(BUILD)?.takeIf { it.environment === environment && it.stamp == stamp }?.let { return it.build }
        return Build(dir, environment).also { dir.putUserData(BUILD, CachedBuild(environment, stamp, it)) }
    }

    private class Build(private val dir: VirtualFile, private val environment: GradleEnvironment) {
        private val parsed = ConcurrentHashMap<VirtualFile, Stamped<DeclaredRepos>>()

        val rootProperties by lazy { dir.findChild("gradle.properties")?.let(::properties).orEmpty().mapValues { it.value.value } }

        /** Gradle properties for credentials and URLs: the environment and user home first, then the build. */
        fun property(name: String) = environment.property(name) ?: rootProperties[name]

        /** The repositories [file] declares, parsed once while its text keeps its stamp. */
        fun declared(file: VirtualFile): DeclaredRepos {
            val stamp = FileStamps.document(file)
            parsed[file]?.takeIf { it.stamp == stamp }?.let { return it.value }
            return parse(script(file)).also { parsed[file] = Stamped(stamp, it) }
        }

        fun declared(script: ScriptText): DeclaredRepos = parse(script)

        private fun parse(script: ScriptText) = GradleRepositories.parse(script, GradleValues(script, ::property, environment.env))

        private fun scriptIn(names: List<String>) = names.firstNotNullOfOrNull { dir.findChild(it) }

        val settings by lazy { scriptIn(settingsNames)?.let(::declared) ?: DeclaredRepos.NONE }
        val rootScript by lazy { scriptIn(buildNames) }
        val rootRepos by lazy { rootScript?.let(::declared) ?: DeclaredRepos.NONE }
        val rootExtras by lazy { rootScript?.let { GradleVariables.definitions(script(it), it, extraOnly = true) }.orEmpty() }
        val init by lazy {
            val parsed = environment.initScripts.map { declared(ScriptText(it)) }
            DeclaredRepos(parsed.flatMap { it.repos }, false)
        }

        fun plugins(settings: DeclaredRepos) = (settings[RepoScope.PLUGINS] + init[RepoScope.PLUGINS]).distinctBy { it.base }

        /** `gradle.properties` from the script's directory up to the root, nearest first. */
        fun propertyFiles(from: VirtualFile?): List<VirtualFile> =
            generateSequence(from) { if (it == dir) null else it.parent }.mapNotNull { it.findChild("gradle.properties") }.toList()
    }

    fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: LoadTextUtil.loadText(file).toString()
}
