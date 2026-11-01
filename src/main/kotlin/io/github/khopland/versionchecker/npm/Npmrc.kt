package io.github.khopland.versionchecker.npm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The registry and credentials npm would use for one package, from `.npmrc` files and the environment. */
internal data class NpmRegistry(val url: String, val headers: Map<String, String>)

/** npm settings merged from the nearest `.npmrc` files, the user `.npmrc` and `npm_config_*` variables. */
internal class Npmrc(private val values: Map<String, String>) {
    fun registry(packageName: String): NpmRegistry {
        val scope = packageName.takeIf { it.startsWith('@') && '/' in it }?.substringBefore('/')
        val url = (scope?.let { values["$scope:registry"] } ?: values["registry"] ?: DEFAULT).trimEnd('/') + "/"
        return NpmRegistry(url, auth(url))
    }

    /** npm matches credentials by the longest `//host/path/:` prefix of the registry URL. */
    private fun auth(url: String): Map<String, String> {
        val bare = url.substringAfter("://").let { "//$it" }
        val prefix = values.keys.asSequence().filter { it.startsWith("//") && ':' in it }.map { it.substringBeforeLast(':') }
            .filter { bare.startsWith(it.trimEnd('/') + "/") || bare == it }.maxByOrNull { it.length }
        fun value(name: String) = prefix?.let { values["$it:$name"] } ?: values[name]?.takeIf { prefix == null && url.startsWith(values["registry"] ?: DEFAULT) }
        value("_authToken")?.let { return mapOf("Authorization" to "Bearer $it") }
        value("_auth")?.let { return mapOf("Authorization" to "Basic $it") }
        val user = value("username")
        val password = value("_password")?.let { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }
        if (user != null && password != null) return mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))
        return emptyMap()
    }

    companion object {
        const val DEFAULT = "https://registry.npmjs.org/"
        private val placeholder = Regex("""\$\{([^}?]+)\??}""")
        private val cache = ConcurrentHashMap<Path, Pair<Long, Map<String, String>>>()

        /** Settings for a package in [directory]. Nearer files win, then the user file, then the environment. */
        fun forDirectory(directory: Path, env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Npmrc {
            val files = generateSequence(directory) { it.parent }.map { it.resolve(".npmrc") }.toMutableList()
            files.add((env["NPM_CONFIG_USERCONFIG"] ?: env["npm_config_userconfig"])?.let { Paths.get(it) } ?: Paths.get(home, ".npmrc"))
            val merged = linkedMapOf<String, String>()
            (env["NPM_CONFIG_REGISTRY"] ?: env["npm_config_registry"])?.let { merged["registry"] = it }
            for (file in files.distinct()) read(file).forEach { (key, value) -> merged.putIfAbsent(key, expand(value, env)) }
            return Npmrc(merged)
        }

        fun parse(text: String): Map<String, String> = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it[0] != '#' && it[0] != ';' && '=' in it }
            .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("\"") }

        private fun read(file: Path): Map<String, String> {
            val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return emptyMap()
            cache[file]?.takeIf { it.first == stamp }?.let { return it.second }
            val values = runCatching { parse(Files.readString(file)) }.getOrDefault(emptyMap())
            cache[file] = stamp to values
            return values
        }

        private fun expand(value: String, env: Map<String, String>) = placeholder.replace(value) { env[it.groupValues[1]] ?: "" }
    }
}
