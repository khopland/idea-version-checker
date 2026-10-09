package io.github.khopland.versionchecker.npm

import com.google.gson.JsonParser
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildFingerprint
import java.nio.file.Files
import java.nio.file.Path

/** Track native resolution configuration separately from the manifests a prepared edit can change. */
internal object NpmBuildInputs {
    private val runtimeConfigurationNames = listOf(".npmrc", ".nvmrc", ".node-version", ".tool-versions", ".mise.toml", "mise.toml", "mise.local.toml")

    private val userConfigurationVariables = listOf(
        "NPM_CONFIG_USERCONFIG", "npm_config_userconfig", "NPM_CONFIG_GLOBALCONFIG", "npm_config_globalconfig"
    )

    private fun userConfigurationPaths(environment: Map<String, String>): List<Path> =
        listOf(Path.of(System.getProperty("user.home"), ".npmrc")) +
            userConfigurationVariables.mapNotNull { environment[it]?.takeIf(String::isNotBlank)?.let(Path::of) }

    fun hasUnsavedResolutionInputs(project: Project, root: String, unsavedPaths: Set<String>): Boolean {
        val paths = mutableSetOf<Path>()
        var ancestor: Path? = Path.of(root)
        while (ancestor != null) {
            for (name in runtimeConfigurationNames + "package.json") paths.add(ancestor.resolve(name))
            ancestor = ancestor.parent
        }
        paths.addAll(userConfigurationPaths(EnvironmentUtil.getEnvironmentMap()))
        (NodeJsInterpreterManager.getInstance(project).interpreter as? NodeJsLocalInterpreter)?.let {
            val node = Path.of(it.interpreterSystemDependentPath)
            paths.add(node)
            node.parent?.parent?.resolve("etc/npmrc")?.let { config -> paths.add(config) }
        }
        runCatching { Path.of(NpmManager.getInstance(project).packageRef.referenceName) }.getOrNull()?.let { configured ->
            paths.add(configured)
            paths.add(configured.resolve("package.json"))
            paths.add(configured.resolve("bin/npm-cli.js"))
        }
        return paths.any { it.toAbsolutePath().normalize().toString() in unsavedPaths }
    }

    fun fingerprint(project: Project, workspace: NpmWorkspace): BuildFingerprint {
        // Only this workspace's manifests affect local package identities and workspace-wide edits.
        val cache = project.service<NpmProjectCache>()
        val files = cache.manifestDigests(workspace) {
            workspace.manifests.associate { it.path to virtualDigest(project, it) }
        }.toMutableMap()
        // Registry commands run at the workspace root; member-local npmrc files do not configure them.
        var ancestor: Path? = Path.of(workspace.root.path)
        while (ancestor != null) {
            for (name in listOf(".npmrc", "package.json") + NpmWorkspaces.otherManagerFiles) {
                val path = ancestor.resolve(name)
                files["disk-config:$path"] = if (name in NpmWorkspaces.otherManagerFiles) Files.exists(path).toString() else diskDigest(path)
            }
            ancestor = ancestor.parent
        }
        var virtualAncestor: VirtualFile? = workspace.root
        while (virtualAncestor != null) {
            for (name in listOf(".npmrc", "package.json") + NpmWorkspaces.otherManagerFiles) {
                val config = virtualAncestor.findChild(name)
                files["virtual-config:${virtualAncestor.path}/$name"] =
                    if (name in NpmWorkspaces.otherManagerFiles) (config != null).toString() else config?.let { virtualDigest(project, it) } ?: "missing"
            }
            virtualAncestor = virtualAncestor.parent
        }
        val environment = EnvironmentUtil.getEnvironmentMap()
        for (path in userConfigurationPaths(environment)) {
            files[path.toString()] = diskDigest(path)
        }
        val policy = project.service<VersionCheckerSettings>().state.deprecatedDependencies
        return BuildFingerprint(files, digest((resolutionContext(project, workspace.root).configuration + policy).toByteArray()))
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
    fun resolutionContext(project: Project, root: VirtualFile): NpmResolutionContext {
        val inputs = sortedMapOf<String, String>()
        fun manifestConfiguration(text: String): String = runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            listOf("packageManager", "devEngines", "workspaces", "engines", "volta")
                .joinToString("\n") { field -> "$field=${json.get(field)}" }
        }.getOrElse { text }
        var ancestor: Path? = Path.of(root.path)
        while (ancestor != null) {
            for (name in runtimeConfigurationNames) {
                val path = ancestor.resolve(name)
                inputs[path.toString()] = diskDigest(path)
            }
            val manifest = ancestor.resolve("package.json")
            // Saved documents can lag an external edit. Track disk and unsaved VFS inputs
            // separately so a runtime configuration edit cannot reuse the previous context.
            val text = manifest.takeIf(Files::isRegularFile)?.let(Files::readString)
            inputs[manifest.toString()] = text?.let(::manifestConfiguration) ?: "missing"
            ancestor = ancestor.parent
        }
        // Non-local VFS files and unsaved configuration have no reliable disk counterpart.
        var virtualAncestor: VirtualFile? = root
        while (virtualAncestor != null) {
            for (name in runtimeConfigurationNames) {
                val file = virtualAncestor.findChild(name)
                inputs["virtual:${virtualAncestor.path}/$name"] = file?.let { virtualDigest(project, it) } ?: "missing"
            }
            val manifest = virtualAncestor.findChild("package.json")
            val text = manifest?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text ?: String(it.contentsToByteArray(), Charsets.UTF_8) }
            inputs["virtual:${virtualAncestor.path}/package.json"] = text?.let(::manifestConfiguration) ?: "missing"
            virtualAncestor = virtualAncestor.parent
        }
        val environment = EnvironmentUtil.getEnvironmentMap()
        val interpreters = NodeJsInterpreterManager.getInstance(project)
        val npm = NpmManager.getInstance(project)
        inputs["runtime"] = interpreters.interpreterRef.referenceName + ":" + npm.packageRef.referenceName
        fun runtimeFile(path: Path) {
            inputs["runtime:$path"] = if (Files.exists(path)) {
                val actual = path.toRealPath()
                "$actual:${Files.getLastModifiedTime(actual)}:${Files.size(actual)}"
            } else "missing"
        }
        (interpreters.interpreter as? NodeJsLocalInterpreter)?.let {
            val node = Path.of(it.interpreterSystemDependentPath)
            runtimeFile(node)
            node.parent?.parent?.resolve("etc/npmrc")?.let { config -> inputs[config.toString()] = diskDigest(config) }
        }
        runCatching { Path.of(npm.packageRef.referenceName) }.getOrNull()?.let { configured ->
            runtimeFile(configured)
            for (name in listOf("package.json", "bin/npm-cli.js")) runtimeFile(configured.resolve(name))
        }
        for (path in userConfigurationPaths(environment)) inputs[path.toString()] = diskDigest(path)
        inputs["environment"] = environment.toSortedMap().toString()
        return NpmResolutionContext(root.path, digest(inputs.toString().toByteArray()))
    }
    private fun diskDigest(path: Path): String {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(path)
        val disk = if (Files.isRegularFile(path)) digest(Files.readAllBytes(path)) else "missing"
        val document = virtual?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return disk + ":" + document?.takeIf { FileDocumentManager.getInstance().isDocumentUnsaved(it) }?.let { digest(it.text.toByteArray()) }
    }
    private fun virtualDigest(project: Project, file: VirtualFile): String =
        project.service<NpmProjectCache>().digest(file)
    private fun digest(bytes: ByteArray) = NpmProjectCache.hash(bytes)
}
