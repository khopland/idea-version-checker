package io.github.khopland.versionchecker.npm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** The packages a lockfile installs. A workspace package is linked, not installed, so it is no [Node]. */
internal interface Lockfile {
    /** One installed package. [id] tells copies of the same version apart where the lockfile does. */
    class Node(val id: String, val name: String, val version: String)

    /** Whether the lockfile installs the workspace at [path], relative to the lockfile's folder, or `""` for the root. */
    fun hasWorkspace(path: String): Boolean

    /** The package that [name], declared by the workspace at [workspace], resolves to. */
    fun direct(workspace: String, name: String): Node?

    fun dependencies(node: Node): List<Node>

    companion object {
        private const val MAX_DEPTH = 8
        private val CACHE = Key.create<Pair<Long, Lockfile?>>("version-checker.lockfile")
        private val READERS = listOf<Pair<String, (String) -> Lockfile?>>(
            PackageLock.FILE_NAME to { PackageLock.parse(JsonParser.parseString(it).asJsonObject) },
            PnpmLock.FILE_NAME to PnpmLock::parse)
        private val packageManager = Regex(""""packageManager"\s*:\s*"(npm|pnpm)@""")
        val FILE_NAMES = READERS.map { it.first }

        /**
         * The lockfile that installs the package in [packageJson], and the package's workspace in it: `""` in the same
         * folder, or the path from a workspace root above it that lists the folder as a workspace.
         */
        fun find(packageJson: VirtualFile): Pair<Lockfile, String>? {
            var directory = packageJson.parent
            var relative = ""
            repeat(MAX_DEPTH) {
                val dir = directory ?: return null
                // With lockfiles of two package managers, `packageManager` says which one is in use, or else the newer lockfile.
                val found = READERS.mapNotNull { (name, parse) -> dir.findChild(name)?.let { it to parse } }
                val manager = if (found.size < 2) null else dir.findChild("package.json")
                    ?.let { runCatching { packageManager.find(VfsUtilCore.loadText(it))?.groupValues?.get(1) }.getOrNull() }
                val lock = found.sortedWith(compareByDescending<Pair<VirtualFile, (String) -> Lockfile?>> { (file, _) ->
                    file.name == (if (manager == "pnpm") PnpmLock.FILE_NAME else PackageLock.FILE_NAME) && manager != null
                }.thenByDescending { it.first.timeStamp }).firstNotNullOfOrNull { (file, parse) -> read(file, parse) }
                if (lock != null) return if (lock.hasWorkspace(relative)) lock to relative else null
                relative = if (relative.isEmpty()) dir.name else "${dir.name}/$relative"
                directory = dir.parent
            }
            return null
        }

        /** Parsed once per change of the file. Null for a format the plugin does not read. */
        private fun read(file: VirtualFile, parse: (String) -> Lockfile?): Lockfile? {
            file.getUserData(CACHE)?.takeIf { it.first == file.modificationStamp }?.let { return it.second }
            val lock = try {
                parse(VfsUtilCore.loadText(file))
            } catch (_: Exception) {
                null
            }
            file.putUserData(CACHE, file.modificationStamp to lock)
            return lock
        }
    }
}

/**
 * The `packages` map of a `package-lock.json`, lockfile version 2 or 3, which npm 7 and later write. Keys are install
 * locations: `""` for the root, `node_modules/a/node_modules/b` for a nested copy, `packages/app` for a workspace.
 */
internal class PackageLock(private val packages: Map<String, Entry>) : Lockfile {
    /** [name] differs from the folder for an alias such as `"x": "npm:other@1.0.0"`. A [link] points at a workspace. */
    class Entry(val name: String?, val version: String?, val dependencies: Set<String>, val link: Boolean)

    override fun hasWorkspace(path: String) = path in packages

    override fun direct(workspace: String, name: String) = resolve(workspace, name)?.let { node(it, name) }

    override fun dependencies(node: Lockfile.Node) =
        packages[node.id]?.dependencies.orEmpty().mapNotNull { name -> resolve(node.id, name)?.let { node(it, name) } }

    private fun node(location: String, name: String): Lockfile.Node? {
        val entry = packages.getValue(location)
        if (entry.link) return null
        return Lockfile.Node(location, entry.name ?: name, entry.version ?: return null)
    }

    /** Where Node finds [name] for the package at [from]: in its own `node_modules`, then in each parent's. */
    fun resolve(from: String, name: String): String? {
        var location = from
        while (true) {
            val candidate = (if (location.isEmpty()) "" else "$location/") + "node_modules/$name"
            if (candidate in packages) return candidate
            if (location.isEmpty()) return null
            location = location.lastIndexOf("node_modules/").let { if (it <= 0) "" else location.substring(0, it - 1) }
        }
    }

    companion object {
        const val FILE_NAME = "package-lock.json"

        /** Null for lockfile version 1, which has no `packages`. */
        fun parse(document: JsonObject): PackageLock? {
            val packages = document.getAsJsonObject("packages") ?: return null
            return PackageLock(packages.entrySet().associate { (location, value) ->
                val entry = value.asJsonObject
                fun string(name: String) = entry.get(name)?.takeIf { it.isJsonPrimitive }?.asString
                // Peers are installed since npm 7, so they come in too.
                val dependencies = listOf("dependencies", "optionalDependencies", "peerDependencies")
                    .flatMapTo(linkedSetOf()) { entry.getAsJsonObject(it)?.keySet().orEmpty() }
                location to Entry(string("name"), string("version"), dependencies, entry.get("link")?.asBoolean == true)
            })
        }
    }
}
