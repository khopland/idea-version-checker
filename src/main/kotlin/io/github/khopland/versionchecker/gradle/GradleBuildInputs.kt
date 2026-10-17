package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.*
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path

/** Gradle ownership, input inventory, and captured validation; no repository queries or version edits. */
internal class GradleBuildInputs(private val externalInputBytes: (Path) -> ByteArray = Files::readAllBytes) {
    private companion object {
        // The Path getter was added in 2026.1; retain compatibility with 2025.3's String getter.
        val gradleHomeGetter = try {
            GradleProjectSettings::class.java.getMethod("getGradleHomePath")
        } catch (_: NoSuchMethodException) {
            GradleProjectSettings::class.java.getMethod("getGradleHome")
        }
    }

    fun supportsFile(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory || !(file.name in setOf("build.gradle", "build.gradle.kts") ||
                file.name == "libs.versions.toml" && file.parent.name == "gradle")) return false
        val root = owner(project, file) ?: return false
        val directories = listOf(root) + GradleSettings.getInstance(project).getLinkedProjectSettings(root)?.modules.orEmpty()
        val boundary = directories.mapNotNull { find(project, it) }
            .filter { VfsUtilCore.isAncestor(it, file, false) }.maxByOrNull { it.path.length } ?: return false
        return generateSequence(file.parent) { it.parent }.takeWhile { it != boundary }.none { it.name in excluded }
    }
    private val excluded = GradleInputInventory.excludedDirectories
    fun roots(project: Project) = GradleSettings.getInstance(project).linkedProjectsSettings.mapNotNull { it.externalProjectPath }.sortedByDescending { it.length }
    fun owner(project: Project, file: VirtualFile) = roots(project).firstOrNull { root ->
        if (file.name.endsWith(".toml")) {
            find(project, root)?.findFileByRelativePath("gradle/libs.versions.toml") == file
        } else {
            val directories = listOf(root) + GradleSettings.getInstance(project).getLinkedProjectSettings(root)?.modules.orEmpty()
            directories.any { directory -> find(project, directory)?.let { VfsUtilCore.isAncestor(it, file, false) } == true }
        }
    }
    private val includedBuild = Regex("""\bincludeBuild\s*(?:\(\s*)?(["'])([^"'\r\n$]+)\1(?=[ \t]*(?:[){;}]|\r?$))""", RegexOption.MULTILINE)
    private fun scriptText(file: VirtualFile): String = FileDocumentManager.getInstance().getCachedDocument(file)?.text
        ?: String(file.contentsToByteArray(), Charsets.UTF_8)

    private fun inputDirectories(project: Project, root: String): GradleInputDirectories {
        val settings = GradleSettings.getInstance(project).getLinkedProjectSettings(root)
        val directories = (listOf(root) + settings?.modules.orEmpty().sorted()).distinct().toMutableList()
        val logicDirectories = mutableSetOf<String>()
        // Settings scripts name included builds, including unlinked siblings. Inspect the
        // current editor text so an unsaved include change cannot reuse the old inventory.
        var index = 0
        val visited = mutableSetOf<String>()
        while (index < directories.size) {
            val directory = directories[index++]
            val canonical = find(project, directory)?.canonicalPath ?: directory
            if (!visited.add(canonical)) continue
            find(project, directory)?.findChild("buildSrc")?.takeIf { it.isDirectory }?.let { buildSrc ->
                logicDirectories += buildSrc.path
                val canonicalBuildSrc = buildSrc.canonicalPath ?: buildSrc.path
                if (directories.none { (find(project, it)?.canonicalPath ?: it) == canonicalBuildSrc }) directories += buildSrc.path
            }
            for (name in listOf("settings.gradle", "settings.gradle.kts")) {
                val script = find(project, directory)?.findChild(name) ?: continue
                for (match in includedBuild.findAll(scriptText(script))) {
                    val path = Path.of(directory).resolve(match.groupValues[2]).normalize().toString()
                    if (find(project, path) != null) {
                        logicDirectories += path
                        val canonicalPath = find(project, path)?.canonicalPath ?: path
                        if (directories.none { (find(project, it)?.canonicalPath ?: it) == canonicalPath }) directories += path
                    }
                }
            }
        }
        // Gradle imports included builds as additional modules. Outside modules are
        // executable build inputs, so conservatively capture their source/resources too.
        // Imported modules may use the canonical alias of the user's linked symlink.
        // Compare VFS-known identities here without adding physical IO to this read action.
        val rootLocation = Path.of(find(project, root)?.canonicalPath ?: root).normalize()
        logicDirectories += directories.filter {
            !Path.of(find(project, it)?.canonicalPath ?: it).normalize().startsWith(rootLocation)
        }
        return GradleInputDirectories(directories.toList(), logicDirectories.toSet())
    }
    fun inventory(project: Project, root: String): GradleInputInventory {
        val directories = inputDirectories(project, root)
        return project.service<GradleProjectCache>().inventory(root, directories) {
            GradleInputInventory.discover(directories) { find(project, it) }
        }
    }
    fun files(project: Project, root: String): List<VirtualFile> = inventory(project, root).files

    fun find(project: Project, path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(path) }
    suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
        val (inputs, home) = readAction {
            val selectedRoots = if (selection.scope == UpdateScope.CURRENT_FILE)
                listOfNotNull(selection.currentFile?.let { find(project, it) }?.let { owner(project, it) }) else roots(project)
            val paths = selectedRoots.flatMap { inventory(project, it).resolutionInputPaths() }.toSet()
            val home = Path.of(GradleSettings.getInstance(project).serviceDirectoryPath ?: System.getenv("GRADLE_USER_HOME")
                ?: Path.of(System.getProperty("user.home"), ".gradle").toString())
            paths to home
        }
        return withContext(Dispatchers.IO) {
            val paths = inputs.toMutableSet()
            listOf("gradle.properties", "init.gradle", "init.gradle.kts").forEach { paths += home.resolve(it).toString() }
            val init = home.resolve("init.d")
            val context = currentCoroutineContext()
            if (Files.isDirectory(init)) Files.walk(init).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    context.ensureActive()
                    val path = iterator.next()
                    if (Files.isRegularFile(path)) paths += path.toString()
                }
            }
            paths
        }
    }

    fun externalHome(project: Project): Path = Path.of(GradleSettings.getInstance(project).serviceDirectoryPath
        ?: System.getenv("GRADLE_USER_HOME") ?: Path.of(System.getProperty("user.home"), ".gradle").toString())

    private fun readExternalInputs(home: Path, checkCancellation: () -> Unit): Map<String, String> {
        val hashes = mutableMapOf<String, String>()
        fun capture(path: Path) {
            checkCancellation()
            hashes[path.toString()] = if (Files.exists(path)) sha256(externalInputBytes(path)) else "missing"
        }
        for (name in listOf("gradle.properties", "init.gradle", "init.gradle.kts")) capture(home.resolve(name))
        val init = home.resolve("init.d")
        if (Files.isDirectory(init)) Files.walk(init).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                checkCancellation()
                val path = iterator.next()
                if (Files.isRegularFile(path)) capture(path)
            }
        }
        return hashes
    }

    private data class InputCapture<T>(val value: T)

    /** Capture disk inputs once per operation, then overlay the latest editor/settings state. */
    suspend fun <T> capture(project: Project, action: (Map<String, String>) -> T): T =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            repeat(3) {
                context.ensureActive()
                val home = readAction { externalHome(project) }
                val hashes = readExternalInputs(home) { context.ensureActive() }
                context.ensureActive()
                val captured = readAction {
                    if (home != externalHome(project)) null else InputCapture(action(hashes))
                }
                if (captured != null) return@withContext captured.value
            }
            error("Gradle user home changed during input capture. Run the check again.")
        }

    fun fingerprint(project: Project, root: String, externalHashes: Map<String, String>? = null): BuildFingerprint {
        val settings = GradleSettings.getInstance(project)
        val linked = settings.getLinkedProjectSettings(root)
        val hashes = project.service<GradleProjectCache>().hashes(root, files(project, root)).toMutableMap()
        // These inputs can change outside IntelliJ's VFS, so always read their current contents.
        val external = externalHashes ?: readExternalInputs(externalHome(project)) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }
        for ((path, saved) in external) {
            val documents = FileDocumentManager.getInstance()
            val unsaved = LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))?.let(documents::getCachedDocument)
                ?.takeIf(documents::isDocumentUnsaved)?.let { sha256(it.text.toByteArray()) }
            hashes[path] = "$saved:$unsaved"
        }
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val gradleHome = linked?.let { gradleHomeGetter.invoke(it)?.toString() }
        return BuildFingerprint(hashes, listOf(root, linked?.modules?.sorted(), linked?.gradleJvm, gradleHome, linked?.distributionType, sdk?.name, sdk?.homePath, settings.serviceDirectoryPath, settings.gradleVmOptions, settings.isOfflineWork, project.service<VersionCheckerSettings>().state.deprecatedDependencies, System.getenv().toSortedMap()).joinToString("|").let { sha256(it.toByteArray()) })
    }
    suspend fun prepareEditValidation(project: Project, snapshots: Collection<BuildSnapshot>): CapturedEditInputs {
        // Native Gradle reads physical files. A same-stamp external write can leave
        // both VFS and PSI stale, so an edit must compare fresh bytes to their baseline.
        val captured = readAction {
            if (project.isDisposed) emptyList() else snapshots.map { it.context.root }.distinct().map { root ->
                val inventory = inventory(project, root)
                val saved = project.service<GradleProjectCache>().savedHashes(root, inventory.files)
                    .filterKeys { it.fileSystem.protocol == "file" }
                inventory to saved
            }
        }
        val diskCurrent = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            captured.all { (inventory, saved) -> inventory.diskMatches(saved) { context.ensureActive() } }
        }
        return capture(project) { external ->
            val home = externalHome(project)
            val guard = CapturedEditInputs { diskCurrent && !project.isDisposed && externalHome(project) == home &&
                snapshots.all { coverageLimitation(project, it.context.root) == null } && areCurrent(project, snapshots, external) }
            guard
        }
    }

    fun areCurrent(project: Project, snapshots: Collection<BuildSnapshot>, externalHashes: Map<String, String>? = null): Boolean {
        val fingerprints = mutableMapOf<String, BuildFingerprint>()
        // Final synchronous validation needs fresh disk bytes too, shared across linked builds.
        val external by lazy {
            externalHashes ?: readExternalInputs(externalHome(project)) { com.intellij.openapi.progress.ProgressManager.checkCanceled() }
        }
        return snapshots.all { snapshot ->
            find(project, snapshot.sourceFile)?.let { owner(project, it) == snapshot.context.root &&
                snapshot.fingerprint == fingerprints.getOrPut(snapshot.context.root) { fingerprint(project, snapshot.context.root, external) } } == true
        }
    }
    /** Arbitrary executable Gradle can load inputs we cannot infer statically. */
    fun coverageLimitation(project: Project, root: String): String? {
        for (file in files(project, root).filter { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") }) {
            val text = scriptText(file).lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            if (includedBuild.findAll(text).any { match ->
                    find(project, Path.of(file.parent.path).resolve(match.groupValues[2]).normalize().toString()) == null
                }) return "Included build inputs are unavailable; Gradle input coverage is limited."
            if (Regex("""\bincludeBuild\b""").findAll(text).count() != includedBuild.findAll(text).count())
                return "Computed included build paths need manual review; Gradle input coverage is limited."
            // External/dynamic applied scripts have no imported input inventory. Keep
            // results reviewable until a fresh check and explicit source review occur.
            val applied = Regex("""\bapply\s*(?:\(\s*)?from\s*[:=]\s*(["'])([^"']+)\1(?=[ \t]*(?:[);}]|\r?$))""", RegexOption.MULTILINE)
            for (match in applied.findAll(text)) {
                val path = match.groupValues[2]
                val resolved = runCatching { Path.of(file.parent.path).resolve(path).normalize() }.getOrNull()
                if ('$' in path || resolved == null || !resolved.startsWith(Path.of(root).normalize()) ||
                    find(project, resolved.toString()) !in files(project, root))
                    return "External applied scripts need manual review; Gradle input coverage is limited."
            }
            if (Regex("""\bapply\s*(?:\(\s*)?from\s*[:=]""").findAll(text).count() != applied.findAll(text).count())
                return "Computed applied script paths need manual review; Gradle input coverage is limited."
        }
        return null
    }
}
