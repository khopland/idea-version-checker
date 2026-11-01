package io.github.khopland.versionchecker.npm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Where a package's registry and credentials come from. */
internal fun interface RegistryConfig {
    fun registry(packageName: String): NpmRegistry

    companion object {
        /** Yarn 2+ reads `.yarnrc.yml` and ignores `.npmrc`. Other package managers read `.npmrc`. */
        fun forDirectory(directory: Path): RegistryConfig = YarnRc.forDirectory(directory) ?: Npmrc.forDirectory(directory)
    }
}

/** Yarn 2+ settings from `.yarnrc.yml` files in the package directory and above, and the user file. */
internal class YarnRc(private val values: Map<String, String>) : RegistryConfig {
    override fun registry(packageName: String): NpmRegistry {
        val scope = packageName.takeIf { it.startsWith('@') && '/' in it }?.substringBefore('/')?.removePrefix("@")
        val scoped = scope?.let { "npmScopes.$it." }
        val url = (scoped?.let { values[it + "npmRegistryServer"] } ?: values["npmRegistryServer"] ?: DEFAULT).trimEnd('/') + "/"
        val registry = values.keys.filter { it.startsWith("npmRegistries.") }
            .map { it.removePrefix("npmRegistries.").substringBeforeLast('.') }.distinct()
            .firstOrNull { normalize(it) == normalize(url) }?.let { "npmRegistries.$it." }
        fun setting(name: String) = scoped?.let { values[it + name] } ?: registry?.let { values[it + name] } ?: values[name]
        val headers = setting("npmAuthToken")?.let { mapOf("Authorization" to "Bearer $it") }
            ?: setting("npmAuthIdent")?.let { ident ->
                // Yarn accepts `user:password` or the Base64 form.
                mapOf("Authorization" to "Basic " + if (':' in ident) Base64.getEncoder().encodeToString(ident.toByteArray()) else ident)
            }.orEmpty()
        return NpmRegistry(url, headers)
    }

    companion object {
        const val DEFAULT = "https://registry.yarnpkg.com/"
        private val placeholder = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")
        private val cache = ConcurrentHashMap<Path, Pair<Long, Map<String, String>>>()

        /** Yarn matches `https://host/path`, `//host/path` and `host/path` keys the same way. */
        private fun normalize(url: String) = url.substringAfter("://").removePrefix("//").trimEnd('/')

        /** Null when no `.yarnrc.yml` is in [directory] or above, so the project does not use Yarn 2+. */
        fun forDirectory(directory: Path, env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): YarnRc? {
            val project = generateSequence(directory) { it.parent }.map { it.resolve(".yarnrc.yml") }.filter { Files.isRegularFile(it) }.toList()
            if (project.isEmpty()) return null
            val merged = linkedMapOf<String, String>()
            for (file in project + Paths.get(home, ".yarnrc.yml")) read(file).forEach { (key, value) -> merged.putIfAbsent(key, expand(value, env)) }
            return YarnRc(merged)
        }

        private fun read(file: Path): Map<String, String> {
            val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return emptyMap()
            cache[file]?.takeIf { it.first == stamp }?.let { return it.second }
            val values = runCatching { parse(Files.readString(file)) }.getOrDefault(emptyMap())
            cache[file] = stamp to values
            return values
        }

        /** The block-mapping part of YAML that `.yarnrc.yml` uses, as dotted keys like `npmScopes.corp.npmAuthToken`. */
        fun parse(text: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            val path = ArrayDeque<Pair<Int, String>>()
            for (raw in text.lines()) {
                val line = raw.substringBefore(" #").trimEnd()
                if (line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith("- ")) continue
                val indent = line.length - line.trimStart().length
                val content = line.trim()
                val colon = keyEnd(content) ?: continue
                val key = content.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'")
                val value = content.substring(colon + 1).trim()
                while (path.isNotEmpty() && path.last().first >= indent) path.removeLast()
                val full = (path.map { it.second } + key).joinToString(".")
                if (value.isEmpty()) path.addLast(indent to key) else result[full] = value.removeSurrounding("\"").removeSurrounding("'")
            }
            return result
        }

        /** The colon that ends the key, outside quotes, so `"https://npm.corp/":` keeps its URL. */
        private fun keyEnd(content: String): Int? {
            var quote: Char? = null
            for ((i, c) in content.withIndex()) {
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c == ':' && (i + 1 == content.length || content[i + 1] == ' ') -> return i
                }
            }
            return null
        }

        private fun expand(value: String, env: Map<String, String>) = placeholder.replace(value) { env[it.groupValues[1]] ?: it.groupValues[2] }
    }
}
