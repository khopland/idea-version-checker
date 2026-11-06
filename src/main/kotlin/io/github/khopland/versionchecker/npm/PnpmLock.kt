package io.github.khopland.versionchecker.npm

/**
 * `pnpm-lock.yaml`, lockfile version 6 (pnpm 8) or 9 (pnpm 9 and later). [importers] maps each workspace (`.` for the
 * root) to its declared packages and the versions they resolve to, such as `1.0.0`, `1.0.0(react@18.2.0)` with peers,
 * `other@1.0.0` for an alias, or `link:../shared` for a workspace. [snapshots] maps `name@version(peers)` to the same for
 * the package's own dependencies. The file is read as text, one entry per line, like `pnpm-workspace.yaml`.
 */
internal class PnpmLock(private val importers: Map<String, Map<String, String>>, private val snapshots: Map<String, Map<String, String>>) : Lockfile {
    override fun hasWorkspace(path: String) = path.ifEmpty { "." } in importers

    override fun direct(workspace: String, name: String) = importers[workspace.ifEmpty { "." }]?.get(name)?.let { node(name, it) }

    override fun dependencies(node: Lockfile.Node) = snapshots[node.id].orEmpty().mapNotNull { (name, reference) -> node(name, reference) }

    /**
     * An alias names the real package: `other@1.0.0`, with a leading slash in version 6. Links, files, git and tarballs
     * have no registry version, so they are no [Lockfile.Node].
     */
    private fun node(name: String, reference: String): Lockfile.Node? {
        val ref = reference.removePrefix("/")
        val id = if ('@' in ref.substringBefore('(')) ref else "$name@$ref"
        val (pkg, version) = split(id.substringBefore('(')) ?: return null
        if (':' in pkg || version.firstOrNull()?.isDigit() != true) return null
        return Lockfile.Node(id, pkg, version)
    }

    companion object {
        const val FILE_NAME = "pnpm-lock.yaml"
        private val SECTIONS = setOf("dependencies", "devDependencies", "optionalDependencies")
        private val INSTALLED = setOf("dependencies", "optionalDependencies")
        // A plain key ends at the first colon followed by a space or the line end, so `a@file:../a:` is one key.
        private val entry = Regex("""^( *)(?:'([^']*)'|"([^"]*)"|([^\s'"#].*?))\s*:(?:\s+(.*?))?\s*$""")

        /** `@scope/name@1.0.0` to the name and the version. */
        fun split(id: String): Pair<String, String>? {
            val at = id.indexOf('@', 1)
            return if (at <= 0) null else id.substring(0, at) to id.substring(at + 1)
        }

        /** Null for lockfile version 5 and older, which name packages another way. */
        fun parse(text: String): PnpmLock? {
            val importers = HashMap<String, MutableMap<String, String>>()
            val snapshots = HashMap<String, MutableMap<String, String>>()
            var version: Double? = null
            var section: String? = null
            // A lockfile of one package, before version 9, has its dependency sections at the top: they count as the root importer's.
            var shift = 0
            var importer: String? = null
            var group: String? = null
            var key: String? = null
            for (line in text.lineSequence()) {
                val match = entry.matchEntire(line) ?: continue
                val name = match.name()
                val value = match.groupValues[5].trim().removeSurrounding("'").removeSurrounding("\"")
                if (match.groupValues[1].isEmpty()) {
                    section = name
                    shift = 0
                    if (name == "lockfileVersion") version = value.toDoubleOrNull()
                    if (name in SECTIONS) {
                        section = "importers"
                        importer = "."
                        group = name
                        shift = 4
                    }
                    continue
                }
                val level = match.groupValues[1].length + shift
                when (section) {
                    "importers" -> when (level) {
                        2 -> importer = name
                        4 -> group = name
                        6 -> key = name
                        8 -> if (group in SECTIONS && name == "version") importers.getOrPut(importer!!) { linkedMapOf() }[key!!] = value
                    }
                    "packages", "snapshots" -> when (level) {
                        // Version 6 starts package keys with a slash.
                        2 -> key = name.removePrefix("/").also { snapshots.getOrPut(it) { linkedMapOf() } }
                        4 -> group = name
                        6 -> if (group in INSTALLED && value.isNotEmpty()) snapshots.getValue(key!!)[name] = value
                    }
                }
            }
            if ((version ?: return null) < 6) return null
            return PnpmLock(importers, snapshots)
        }

        private fun MatchResult.name() = groupValues[2].ifEmpty { groupValues[3] }.ifEmpty { groupValues[4] }
    }
}
