package io.github.khopland.versionchecker.npm

/**
 * `yarn.lock`, of Yarn 1 or of Yarn 2 and later. Each entry starts with the descriptors that resolve to it, such as
 * `"qs@^6.0.0", qs@^6.1.0:` in Yarn 1 or `"qs@npm:^6.0.0, qs@npm:^6.1.0":` since Yarn 2, and lists the package's
 * dependencies as more descriptors. Since Yarn 2, [workspaces] holds each workspace's entry, `app@workspace:packages/app`,
 * by path (`.` for the root). Yarn 1 has none, so a declared package is found by the range that package.json gives it.
 * The file is read as text, one entry per line, like `pnpm-lock.yaml`.
 */
internal class YarnLock(private val entries: Map<String, Entry>, private val workspaces: Map<String, Entry>?) : Lockfile {
    /** [pkg] is the registry name and version, or null for a git, file, link, patch or workspace package. */
    class Entry(val id: String, val pkg: Pair<String, String>?, val dependencies: Map<String, String>)

    /** Yarn 1 does not list workspaces, so it is taken to install every one. */
    override fun hasWorkspace(path: String) = workspaces == null || path.ifEmpty { "." } in workspaces

    override fun direct(workspace: String, name: String, range: String?) =
        (workspaces?.get(workspace.ifEmpty { "." })?.dependencies?.get(name) ?: range)?.let { node(name, it) }

    override fun dependencies(node: Lockfile.Node) = entries[node.id]?.dependencies.orEmpty().mapNotNull { (name, range) -> node(name, range) }

    /** Since Yarn 2, a range without a protocol, as Yarn 2 wrote dependencies, is an npm range. */
    private fun node(name: String, range: String): Lockfile.Node? {
        val entry = entries["$name@" + (if (workspaces == null || ':' in range) range else "npm:$range")] ?: return null
        val (pkg, version) = entry.pkg ?: return null
        return Lockfile.Node(entry.id, pkg, version)
    }

    private class Block(val descriptors: List<String>) {
        var version: String? = null
        var resolution: String? = null
        val dependencies = linkedMapOf<String, String>()
    }

    companion object {
        const val FILE_NAME = "yarn.lock"
        private val INSTALLED = setOf("dependencies", "optionalDependencies")
        private val field = Regex("""^("[^"]*"|\S+)\s+(.*)$""")

        /** Null for a file that is neither Yarn 1's, with its `# yarn lockfile v1` header, nor Yarn 2's or later, with `__metadata`. */
        fun parse(text: String): YarnLock? {
            val classic = text.lineSequence().any { it.trim() == "# yarn lockfile v1" }
            val blocks = mutableListOf<Block>()
            var group: String? = null
            for (row in text.lineSequence()) {
                val (indent, key, value) = (if (classic) classic(row) else PnpmLock.line(row)) ?: continue
                val block = blocks.lastOrNull()
                when (indent) {
                    0 -> blocks += Block(key.split(",").map { it.trim().removeSurrounding("\"") })
                    2 -> {
                        group = key
                        if (key == "version") block?.version = value
                        if (key == "resolution") block?.resolution = value
                    }
                    4 -> if (group in INSTALLED && value.isNotEmpty()) block?.dependencies?.put(key, value)
                }
            }
            val metadata = blocks.firstOrNull { it.descriptors == listOf("__metadata") }
            // Version 4 is Yarn 2's; earlier ones come from its prereleases.
            if (!classic && (metadata?.version?.toIntOrNull() ?: return null) < 4) return null
            val entries = HashMap<String, Entry>()
            val workspaces = HashMap<String, Entry>()
            for (block in blocks) {
                if (block === metadata) continue
                val id = block.descriptors.first()
                // Yarn 1 gives the version; later ones a resolution such as `qs@npm:6.0.0` or `app@workspace:packages/app`.
                val resolution = if (classic) classicName(id)?.let { name -> block.version?.let { "$name@npm:$it" } } else block.resolution
                val (name, reference) = resolution?.let(PnpmLock::split) ?: ("" to "")
                // A package from another registry URL than the configured one has `::__archiveUrl=…` after the version.
                val version = reference.takeIf { it.startsWith("npm:") }?.removePrefix("npm:")?.substringBefore("::")
                val entry = Entry(id, version?.takeIf { ':' !in name && it.firstOrNull()?.isDigit() == true }?.let { name to it }, block.dependencies)
                block.descriptors.forEach { entries[it] = entry }
                if (reference.startsWith("workspace:")) workspaces[reference.removePrefix("workspace:")] = entry
            }
            return YarnLock(entries, if (classic) null else workspaces)
        }

        /**
         * The registry package of a Yarn 1 descriptor: `qs@^6.0.0`, or `qs` for the alias `x@npm:qs@^6.0.0`. Null for a git,
         * file, link or tarball range, which has a colon or a slash.
         */
        private fun classicName(descriptor: String): String? {
            val (alias, range) = PnpmLock.split(descriptor) ?: return null
            val (name, spec) = if (range.startsWith("npm:")) PnpmLock.split(range.removePrefix("npm:")) ?: return null else alias to range
            return name.takeIf { ':' !in spec && '/' !in spec }
        }

        /** A Yarn 1 line, `key "value"` or `key:`, as its indent, key and value. Keys of an entry keep their quotes, for [parse] to split. */
        private fun classic(line: String): Triple<Int, String, String>? {
            val content = line.trim()
            if (content.isEmpty() || content.startsWith("#")) return null
            val indent = line.length - line.trimStart().length
            if (content.endsWith(":")) return Triple(indent, content.dropLast(1), "")
            val match = field.matchEntire(content) ?: return null
            return Triple(indent, match.groupValues[1].removeSurrounding("\""), match.groupValues[2].removeSurrounding("\""))
        }
    }
}
