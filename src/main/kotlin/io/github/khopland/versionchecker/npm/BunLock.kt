package io.github.khopland.versionchecker.npm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/**
 * `bun.lock`, the text lockfile of Bun 1.2 and later: JSON with trailing commas. [workspaces] maps each workspace's path,
 * `""` for the root, to its package name. [packages] maps install paths to `name@version` and the package's dependencies.
 * A path is the names of the packages above it: `qs` is hoisted, `parent/qs` is the copy that parent needs, and `app/qs`
 * or `./app/qs` the one that workspace app needs. Bun's older binary `bun.lockb` is not read.
 */
internal class BunLock(private val workspaces: Map<String, String>, private val packages: Map<String, Entry>) : Lockfile {
    /** [reference] is `name@version`, or for a package not from the registry such as `name@workspace:path`, another one. */
    class Entry(val reference: String, val dependencies: Set<String>)

    /** Shared by every root of a transitive walk, which otherwise resolves the same path over and over. */
    private val children = ConcurrentHashMap<String, List<Lockfile.Node>>()

    override fun hasWorkspace(path: String) = path in workspaces

    /** A workspace's own copies start with `./` when its name is taken by a root dependency of that name. */
    override fun direct(workspace: String, name: String, range: String?): Lockfile.Node? {
        val member = workspaces[workspace] ?: return null
        if (workspace.isEmpty()) return node(name)
        val prefix = if (packages[member]?.reference == "$member@workspace:$workspace") member else "./$member"
        return node("$prefix/$name".takeIf { it in packages } ?: name)
    }

    override fun dependencies(node: Lockfile.Node) = children.getOrPut(node.id) {
        packages[node.id]?.dependencies.orEmpty().mapNotNull { name -> resolve(node.id, name)?.let(::node) }
    }

    /** Every version of [name] the lockfile installs. */
    fun versions(name: String) = packages.keys.mapNotNullTo(HashSet()) { path -> node(path)?.takeIf { it.name == name }?.version }

    /** Links, folders, git, tarballs and workspaces have no registry version, so they are no [Lockfile.Node]. */
    private fun node(path: String): Lockfile.Node? {
        val (name, version) = PnpmLock.split(packages[path]?.reference ?: return null) ?: return null
        if (':' in name || version.firstOrNull()?.isDigit() != true) return null
        return Lockfile.Node(path, name, version)
    }

    /** Where Bun finds [name] for the package at [from]: under its own path, then under each parent's. A scoped name is two parts. */
    fun resolve(from: String, name: String): String? {
        var path = from
        while (true) {
            val candidate = (if (path.isEmpty()) "" else "$path/") + name
            if (candidate in packages) return candidate
            if (path.isEmpty()) return null
            val parts = path.split('/')
            path = parts.dropLast(if (parts.size >= 2 && parts[parts.size - 2].startsWith("@")) 2 else 1).joinToString("/")
        }
    }

    companion object {
        const val FILE_NAME = "bun.lock"
        // Bun installs peers, like npm, but not the optional ones.
        private val INSTALLED = listOf("dependencies", "optionalDependencies", "peerDependencies")
        // Strings and comments are kept as they are, so a comma inside them stays.
        private val trailingComma = Regex(""""[^"\\]*(?:\\.[^"\\]*)*"|//[^\n]*|/\*[\s\S]*?\*/|,(?=(?:\s|//[^\n]*|/\*[\s\S]*?\*/)*[}\]])""")

        /** Null for a file without `lockfileVersion`, `workspaces` and `packages`. */
        fun parse(text: String): BunLock? {
            // Gson reads comments, but fails on a trailing comma in an object and reads one in an array as a null.
            val document = runCatching { JsonParser.parseString(trailingComma.replace(text) { if (it.value == ",") "" else it.value }) }.getOrNull() as? JsonObject ?: return null
            if ((document.get("lockfileVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return null) < 0) return null
            val workspaces = (document.get("workspaces") as? JsonObject ?: return null).entrySet()
                .associate { (path, value) -> path to ((value as? JsonObject)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "") }
            val packages = (document.get("packages") as? JsonObject ?: return null).entrySet().mapNotNull { (path, value) ->
                val row = (value as? JsonArray)?.takeIf { !it.isEmpty && it[0].isJsonPrimitive } ?: return@mapNotNull null
                // npm: [name@version, registry, info, integrity]; others have the info second, and a workspace has none.
                val info = row.drop(1).firstOrNull { it is JsonObject } as? JsonObject
                val optionalPeers = (info?.get("optionalPeers") as? JsonArray)?.mapNotNullTo(hashSetOf()) { peer -> peer.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
                val dependencies = INSTALLED.flatMapTo(linkedSetOf()) { (info?.get(it) as? JsonObject)?.keySet().orEmpty() } - optionalPeers
                path to Entry(row[0].asString, dependencies)
            }.toMap()
            return BunLock(workspaces, packages)
        }
    }
}
