package io.github.khopland.versionchecker.npm

import io.github.khopland.versionchecker.core.sha256
import com.google.gson.JsonParser
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.openapi.components.service
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Track native resolution configuration separately from the manifests a prepared edit can change. */
internal object NpmBuildInputs {
    internal data class External(val digests: Map<Path, String>, val manifests: Map<Path, String?>,
                                 val existing: Map<Path, Boolean>, val runtimes: Map<Path, String>,
                                 val layoutIsCurrent: (Project, Set<String>) -> Boolean)
    private data class Layout(val roots: Set<String>, val environment: Map<String, String>,
                              val node: String?, val interpreter: String?, val npm: String?)
    private data class Captured<T>(val value: T)

    private fun layout(project: Project, roots: Set<String>): Layout {
        val interpreters = NodeJsInterpreterManager.getInstance(project)
        return Layout(roots, EnvironmentUtil.getEnvironmentMap().toMap(),
            (interpreters.interpreter as? NodeJsLocalInterpreter)?.interpreterSystemDependentPath,
            interpreters.interpreterRef.referenceName, NpmManager.getInstance(project).packageRef.referenceName)
    }

    /** Disk bytes/stats are local to this operation; IDE state is captured only after the IO phase. */
    suspend fun <T> capture(project: Project, roots: () -> Set<String>, readBytes: (Path) -> ByteArray = Files::readAllBytes,
                            action: (External) -> T): T = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        repeat(3) {
            val layout = readAction { layout(project, roots()) }
            val inventory = inventory(layout) { context.ensureActive() }
            val manifestText = mutableMapOf<Path, String?>()
            val saved = inventory.contentPaths.associateWith { path ->
                context.ensureActive()
                val bytes = if (Files.isRegularFile(path)) readBytes(path) else null
                if (path in inventory.manifestPaths) manifestText[path] = bytes?.let { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(it)).toString() }
                bytes?.let(::digest) ?: "missing"
            }
            val external = External(saved, manifestText,
                inventory.markerPaths.associateWith { context.ensureActive(); Files.exists(it) },
                inventory.runtimePaths.associateWith { context.ensureActive(); runtimeStamp(it) },
                { currentProject, currentRoots -> layout == layout(currentProject, currentRoots) })
            context.ensureActive()
            val captured = readAction {
                if (layout != layout(project, roots())) null else Captured(action(external))
            }
            if (captured != null) return@withContext captured.value
        }
        error("npm input paths or runtime changed during capture. Run the check again.")
    }

    private fun runtimeStamp(path: Path): String = if (Files.exists(path)) {
        val actual = path.toRealPath()
        "$actual:${Files.getLastModifiedTime(actual)}:${Files.size(actual)}"
    } else "missing"
    private fun inventory(layout: Layout, checkCancellation: () -> Unit = {}) =
        NpmInputInventory.discover(layout.roots, layout.environment, layout.node, layout.npm, checkCancellation)
    private fun inventory(project: Project, root: String) = inventory(layout(project, setOf(root)))

    private val userConfigurationVariables = listOf(
        "NPM_CONFIG_USERCONFIG", "npm_config_userconfig", "NPM_CONFIG_GLOBALCONFIG", "npm_config_globalconfig"
    )

    internal fun userConfigurationPaths(environment: Map<String, String>, directory: Path): List<Path> {
        val home = environment["HOME"] ?: System.getProperty("user.home")
        val default = if (userConfigurationVariables.take(2).any { !environment[it].isNullOrBlank() }) emptyList()
            else listOf(directory.resolve(home.ifEmpty { "~" }).resolve(".npmrc").normalize())
        return default + userConfigurationVariables.mapNotNull { key ->
            environment[key]?.takeIf(String::isNotBlank)?.let { directory.resolve(it).normalize() }
        }
    }

    /** Native input paths only: saving must not hash configuration or probe a runtime. */
    fun resolutionInputPaths(project: Project, root: String): Set<String> = inventory(project, root).paths()

    fun hasUnsavedResolutionInputs(project: Project, root: String, unsavedPaths: Set<String>): Boolean =
        inventory(project, root).hasUnsavedInputs(unsavedPaths)

    @JvmOverloads
    fun fingerprint(project: Project, workspace: NpmWorkspace, external: External? = null): BuildFingerprint {
        // Only this workspace's manifests affect local package identities and workspace-wide edits.
        val cache = project.service<NpmProjectCache>()
        val files = cache.manifestDigests(workspace) {
            workspace.manifests.associate { it.path to virtualDigest(project, it) }
        }.toMutableMap()
        // Registry commands run at the workspace root; member-local npmrc files do not configure them.
        val inventory = inventory(project, workspace.root.path)
        for (entry in inventory.entries.filter { it.role.editFingerprint }) {
            val path = entry.path
            if (entry.ancestorDirectory != null) {
                files["disk-config:$path"] = if (entry.role == NpmInputInventory.Role.OTHER_MANAGER_MARKER)
                    (external?.existing?.getValue(path) ?: Files.exists(path)).toString() else diskDigest(path, external)
            } else files[path.toString()] = diskDigest(path, external)
        }
        for ((entry, file, virtualPath) in virtualInputs(workspace.root, inventory).filter { it.entry.role.editFingerprint }) {
            files["virtual-config:$virtualPath"] = if (entry.role == NpmInputInventory.Role.OTHER_MANAGER_MARKER)
                (file != null).toString() else file?.let { virtualDigest(project, it) } ?: "missing"
        }
        val policy = project.service<VersionCheckerSettings>().state.deprecatedDependencies
        return BuildFingerprint(files, digest((resolutionContext(project, workspace.root, external).configuration + policy).toByteArray()))
    }

    fun inspectionFingerprint(workspace: NpmWorkspace, fingerprint: BuildFingerprint): BuildFingerprint {
        val manifests = workspace.manifests.map { it.path }.toSet()
        // Runtime/registry configuration is already separated from dependency selectors in configuration.
        val files = fingerprint.files.filterKeys { path ->
            val ancestorManifest = (path.startsWith("disk-config:") || path.startsWith("virtual-config:")) && path.endsWith("/package.json")
            path !in manifests && !ancestorManifest
        }
        return BuildFingerprint(files, "${fingerprint.configuration}:${workspace.names.sorted()}:${manifests.sorted()}")
    }

    /** Hash configuration, never credentials themselves, into a key separate from edit safety. */
    @JvmOverloads
    fun resolutionContext(project: Project, root: VirtualFile, external: External? = null): NpmResolutionContext {
        val inputs = sortedMapOf<String, String>()
        fun manifestConfiguration(text: String): String = runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            listOf("packageManager", "devEngines", "workspaces", "engines", "volta")
                .joinToString("\n") { field -> "$field=${json.get(field)}" }
        }.getOrElse { text }
        val layout = layout(project, setOf(root.path))
        val inventory = inventory(layout)
        for (entry in inventory.entries.filter { it.role.resolutionContext }) {
            val path = entry.path
            when (entry.role) {
                NpmInputInventory.Role.MANIFEST -> {
                    // Disk and editor configuration are independent: saved VFS can lag external edits.
                    val text = if (external != null) external.manifests.getValue(path)
                        else path.takeIf(Files::isRegularFile)?.let(Files::readString)
                    inputs[path.toString()] = text?.let(::manifestConfiguration) ?: "missing"
                }
                NpmInputInventory.Role.RUNTIME -> inputs["runtime:$path"] = external?.runtimes?.getValue(path) ?: runtimeStamp(path)
                else -> inputs[path.toString()] = diskDigest(path, external)
            }
        }
        // Non-local VFS files and unsaved configuration have no reliable disk counterpart.
        for ((entry, file, virtualPath) in virtualInputs(root, inventory).filter { it.entry.role.resolutionContext }) {
            inputs["virtual:$virtualPath"] = if (entry.role == NpmInputInventory.Role.MANIFEST) {
                val text = file?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text ?: String(it.contentsToByteArray(), Charsets.UTF_8) }
                text?.let(::manifestConfiguration) ?: "missing"
            } else file?.let { virtualDigest(project, it) } ?: "missing"
        }
        inputs["runtime"] = layout.interpreter + ":" + layout.npm
        val environment = layout.environment
        inputs["environment"] = environment.toSortedMap().toString()
        return NpmResolutionContext(root.path, digest(inputs.toString().toByteArray()))
    }
    /** Project only ancestor entries into the VFS namespace, preserving missing-file markers. */
    private data class VirtualInput(val entry: NpmInputInventory.Entry, val file: VirtualFile?, val path: String)
    private fun virtualInputs(root: VirtualFile, inventory: NpmInputInventory): List<VirtualInput> {
        val ancestors = mutableMapOf<Path, VirtualFile>()
        var ancestor: VirtualFile? = root
        while (ancestor != null) {
            ancestors[Path.of(ancestor.path)] = ancestor
            ancestor = ancestor.parent
        }
        return inventory.entries.mapNotNull { entry ->
            val directory = entry.ancestorDirectory?.let(ancestors::get) ?: return@mapNotNull null
            VirtualInput(entry, directory.findChild(entry.path.fileName.toString()), "${directory.path}/${entry.path.fileName}")
        }
    }
    private fun diskDigest(path: Path, external: External? = null): String {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(path)
        val disk = external?.digests?.getValue(path) ?: if (Files.isRegularFile(path)) digest(Files.readAllBytes(path)) else "missing"
        val document = virtual?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return disk + ":" + document?.takeIf { FileDocumentManager.getInstance().isDocumentUnsaved(it) }?.let { digest(it.text.toByteArray()) }
    }
    private fun virtualDigest(project: Project, file: VirtualFile): String =
        project.service<NpmProjectCache>().digest(file)
    private fun digest(bytes: ByteArray) = sha256(bytes)
}
