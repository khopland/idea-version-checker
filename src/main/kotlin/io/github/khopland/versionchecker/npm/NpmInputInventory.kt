package io.github.khopland.versionchecker.npm

import java.nio.file.Path

/** npm's command-directory input policy. Discovery is path-only; it never probes the filesystem. */
internal class NpmInputInventory private constructor(val entries: Set<Entry>) {
    enum class Role(val content: Boolean, val editFingerprint: Boolean, val resolutionContext: Boolean) {
        NPM_CONFIGURATION(true, true, true),
        RUNTIME_CONFIGURATION(true, false, true),
        MANIFEST(true, true, true),
        OTHER_MANAGER_MARKER(false, true, false),
        USER_CONFIGURATION(true, true, true),
        NODE_CONFIGURATION(true, false, true),
        RUNTIME(false, false, true)
    }
    data class Entry(val path: Path, val role: Role, val ancestorDirectory: Path? = null)

    val contentPaths: Set<Path> = entries.filter { it.role.content }.mapTo(mutableSetOf()) { it.path }
    val manifestPaths: Set<Path> = paths(Role.MANIFEST)
    val markerPaths: Set<Path> = paths(Role.OTHER_MANAGER_MARKER)
    val runtimePaths: Set<Path> = paths(Role.RUNTIME)
    fun paths(): Set<String> = entries.mapTo(mutableSetOf()) { it.path.toAbsolutePath().normalize().toString() }
    fun hasUnsavedInputs(unsavedPaths: Set<String>): Boolean = paths().any { it in unsavedPaths }
    private fun paths(role: Role): Set<Path> = entries.filter { it.role == role }.mapTo(mutableSetOf()) { it.path }

    companion object {
        fun discover(roots: Set<String>, environment: Map<String, String>, node: String?, npm: String?, checkCancellation: () -> Unit = {}): NpmInputInventory {
            val entries = mutableSetOf<Entry>()
            for (root in roots) {
                var ancestor: Path? = Path.of(root)
                while (ancestor != null) {
                    checkCancellation()
                    entries += Entry(ancestor.resolve(".npmrc"), Role.NPM_CONFIGURATION, ancestor)
                    for (name in listOf(".nvmrc", ".node-version", ".tool-versions", ".mise.toml", "mise.toml", "mise.local.toml"))
                        entries += Entry(ancestor.resolve(name), Role.RUNTIME_CONFIGURATION, ancestor)
                    entries += Entry(ancestor.resolve("package.json"), Role.MANIFEST, ancestor)
                    NpmWorkspaces.otherManagerFiles.forEach { entries += Entry(ancestor.resolve(it), Role.OTHER_MANAGER_MARKER, ancestor) }
                    ancestor = ancestor.parent
                }
                NpmBuildInputs.userConfigurationPaths(environment, Path.of(root)).forEach {
                    entries += Entry(it, Role.USER_CONFIGURATION)
                }
            }
            node?.let { value ->
                val path = Path.of(value)
                entries += Entry(path, Role.RUNTIME)
                path.parent?.parent?.resolve("etc/npmrc")?.let { entries += Entry(it, Role.NODE_CONFIGURATION) }
            }
            runCatching { Path.of(npm) }.getOrNull()?.let { configured ->
                entries += Entry(configured, Role.RUNTIME)
                entries += Entry(configured.resolve("package.json"), Role.RUNTIME)
                entries += Entry(configured.resolve("bin/npm-cli.js"), Role.RUNTIME)
            }
            return NpmInputInventory(entries)
        }
    }
}
