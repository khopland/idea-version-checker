package io.github.khopland.versionchecker.gradle

import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.repo.MavenRepo
import java.net.URI

/** Where a `repositories { }` block applies. */
internal enum class RepoScope {
    /** The project of the script. */
    PROJECT,

    /** `allprojects` and `subprojects`, which also reach the other projects of the build. */
    SHARED,

    /** `dependencyResolutionManagement` in the settings script. */
    SETTINGS,

    /** `pluginManagement`, for `plugins { }` blocks. */
    PLUGINS,

    /** `buildscript`, for its `classpath` dependencies. */
    BUILDSCRIPT,
}

/** The repositories in one script, by scope. [preferSettings] is set when `repositoriesMode` ignores project repositories. */
internal class DeclaredRepos(val repos: List<Pair<RepoScope, MavenRepo>>, val preferSettings: Boolean) {
    operator fun get(vararg scopes: RepoScope) = repos.filter { it.first in scopes }.map { it.second }

    companion object {
        val NONE = DeclaredRepos(emptyList(), false)
    }
}

/**
 * Reads `repositories { }` blocks: `mavenCentral()`, `google()`, `gradlePluginPortal()` and `maven { }` with a literal
 * or property URL. Credentials come from `credentials { }` with `username` and `password`, from an
 * `HttpHeaderCredentials` name and value, or from `credentials(PasswordCredentials::class)` and the Gradle properties
 * `<name>Username` and `<name>Password`. Repositories in `publishing` are where the build uploads, so they are skipped.
 */
internal object GradleRepositories {
    private val wellKnown = Regex("""\b(mavenCentral|google|gradlePluginPortal)\s*\(""")
    private val bareMaven = Regex("""\bmaven\s*\(([^)]*(?:\([^)]*\)[^)]*)*)\)(?!\s*\{)""")
    private val urlStatement = statement("url", "setUrl")
    private val typedCredentials = Regex("""\bcredentials\s*\(\s*(PasswordCredentials|HttpHeaderCredentials)\b""")
    private val preferSettings = Regex("""\b(PREFER_SETTINGS|FAIL_ON_PROJECT_REPOS)\b""")

    /** `key value`, `key = value` or `key(value)` as a statement on its own line or after `;`. */
    private fun statement(vararg keys: String) =
        Regex("""(?m)(?:^|[;{])\s*(?:${keys.joinToString("|")})\b(?!\s*[.\[])\s*(?:=\s*)?([^;\n]+?)\s*(?:;|$)""")

    fun parse(script: ScriptText, values: GradleValues): DeclaredRepos {
        val result = mutableListOf<Pair<RepoScope, MavenRepo>>()
        for (block in script.blocks) {
            if (block.name != "repositories") continue
            val path = block.path
            if ("publishing" in path || "uploadArchives" in path) continue
            val scope = when {
                "pluginManagement" in path -> RepoScope.PLUGINS
                "buildscript" in path -> RepoScope.BUILDSCRIPT
                "dependencyResolutionManagement" in path -> RepoScope.SETTINGS
                "allprojects" in path || "subprojects" in path -> RepoScope.SHARED
                else -> RepoScope.PROJECT
            }
            val found = mutableListOf<Pair<Int, MavenRepo>>()
            val own = script.own(block)
            wellKnown.findAll(own).forEach { found += it.range.first to known(it.groupValues[1]) }
            bareMaven.findAll(own).forEach { match -> url(match.groupValues[1], values)?.let { found += match.range.first to repo(it, null, values) } }
            for (child in block.children) {
                val offset = child.open - block.open - 1
                when (child.name) {
                    "mavenCentral", "google", "gradlePluginPortal" -> found += offset to known(child.name)
                    "maven" -> maven(script, child, values)?.let { found += offset to it }
                }
            }
            found.sortedBy { it.first }.forEach { result += scope to it.second }
        }
        return DeclaredRepos(result, preferSettings.containsMatchIn(script.code))
    }

    private fun known(name: String) = when (name) {
        "google" -> MavenRepo.GOOGLE
        "gradlePluginPortal" -> MavenRepo.GRADLE_PLUGINS
        else -> MavenRepo.CENTRAL
    }

    /** The URL from `url = uri("…")`, `maven("…")` or `maven(url = "…")`. Only HTTP repositories can be read. */
    private fun url(expression: String, values: GradleValues): String? {
        val text = expression.trim().removePrefix("url").trim().removePrefix("=").trim()
            .let { if (it.startsWith("(") && it.endsWith(")")) it.substring(1, it.length - 1).trim() else it }
        if (text.isEmpty()) return null
        return values.resolve(text)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun maven(script: ScriptText, block: Block, values: GradleValues): MavenRepo? {
        val own = script.own(block)
        val url = (if (block.args.isNotBlank()) url(block.args, values) else null)
            ?: urlStatement.find(own)?.let { url(it.groupValues[1], values) } ?: return null
        val name = statement("name").find(own)?.let { values.resolve(it.groupValues[1]) }
        return repo(url, name, values, credentials(script, block, own, name, values))
    }

    private class Credentials(val headers: Map<String, String>, val missing: List<String>)

    private fun credentials(script: ScriptText, block: Block, own: String, name: String?, values: GradleValues): Credentials? {
        val headers = linkedMapOf<String, String>()
        val missing = mutableListOf<String>()
        fun resolve(expression: String?): String? {
            expression ?: return null
            val (value, unresolved) = values.resolveOrMissing(expression)
            missing += unresolved
            return value
        }
        fun basic(user: String?, password: String?) {
            if (user != null && password != null) headers["Authorization"] = Http.basicAuth(user, password)
        }
        fun header(headerName: String?, value: String?) {
            if (headerName != null && value != null) headers[headerName] = value
        }
        val blocks = block.children.filter { it.name == "credentials" }
        for (credentials in blocks) {
            val body = script.own(credentials)
            fun field(key: String) = statement(key).find(body)?.groupValues?.get(1)
            if ("HttpHeaderCredentials" in credentials.args) header(resolve(field("name")), resolve(field("value")))
            else basic(resolve(field("username")), resolve(field("password")))
        }
        // `credentials(PasswordCredentials::class)` reads the Gradle properties `<name>Username` and `<name>Password`.
        val typed = typedCredentials.find(own)?.groupValues?.get(1)
        if (typed != null && blocks.none { it.args.contains(typed) } && name != null) {
            if (typed == "PasswordCredentials") basic(resolve("${name}Username"), resolve("${name}Password"))
            else header(resolve("${name}AuthHeaderName"), resolve("${name}AuthHeaderValue"))
        }
        if (blocks.isEmpty() && typed == null) return null
        return Credentials(headers, missing.distinct())
    }

    private fun repo(url: String, name: String?, values: GradleValues, credentials: Credentials? = null): MavenRepo {
        val id = name ?: runCatching { URI(url).host }.getOrNull() ?: url
        val hint = when {
            credentials == null -> "Add credentials to the repository in the build script"
            credentials.missing.isNotEmpty() -> "Set the ${credentials.missing.joinToString(" and ")} for the IDE: " +
                "Gradle properties in ~/.gradle/gradle.properties, environment variables before the IDE starts"
            else -> "Check the credentials for repository '$id' in the build script"
        }
        return MavenRepo(id, url, credentials?.headers.orEmpty(), hint)
    }
}

/**
 * Values of simple expressions in a build script: string literals with `$name` templates, Gradle properties through
 * `providers.gradleProperty("x")`, `findProperty("x")`, `property("x")` or a bare name, environment variables through
 * `System.getenv("X")` or `providers.environmentVariable("X")`, local `val` and `def` values, and `?:` between them.
 */
internal class GradleValues(private val script: ScriptText?, private val property: (String) -> String?, private val env: (String) -> String?) {
    private val missing = mutableListOf<String>()

    fun resolve(expression: String): String? = resolve(expression, 0)

    /** The value, or the Gradle properties and environment variables that would give it. */
    fun resolveOrMissing(expression: String): Pair<String?, List<String>> {
        missing.clear()
        val value = resolve(expression, 0)
        return value to if (value == null) missing.toList() else emptyList()
    }

    private fun resolve(expression: String, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        for (alternative in expression.split("?:")) value(alternative.trim(), depth)?.let { return it }
        return null
    }

    private fun value(raw: String, depth: Int): String? {
        var text = raw
        while (true) {
            val stripped = suffix.replace(text, "").trim().let { wrapper.matchEntire(it)?.groupValues?.get(1)?.trim() ?: it }
            if (stripped == text) break
            text = stripped
        }
        quoted.matchEntire(text)?.let { return interpolate(it.groupValues[1].ifEmpty { it.groupValues[2] }, depth) }
        environment.find(text)?.let { match ->
            val name = match.groupValues[1]
            env(name)?.let { return it }
            missing += "environment variable $name"
            return null
        }
        gradleProperty.find(text)?.let { return propertyOrMissing(it.groupValues[1]) }
        identifier.matchEntire(text)?.let { match ->
            val name = match.groupValues[1]
            script?.let { Regex("""(?m)^\s*(?:val|var|def|String)\s+${Regex.escape(name)}\s*(?::\s*[\w?]+\s*)?=\s*(.+?)\s*;?\s*$""").find(it.code) }
                ?.let { return resolve(it.groupValues[1], depth + 1) }
            return propertyOrMissing(name)
        }
        return null
    }

    private fun propertyOrMissing(name: String): String? {
        property(name)?.let { return it }
        missing += "Gradle property $name"
        return null
    }

    private fun interpolate(text: String, depth: Int): String? {
        var failed = false
        val result = template.replace(text) { match ->
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            resolve(inner, depth + 1) ?: "".also { failed = true }
        }
        return if (failed) null else result
    }

    private companion object {
        const val MAX_DEPTH = 5
        val suffix = Regex("""(?:\.get\(\)|\.orNull|\.getOrNull\(\)|\.getOrElse\([^)]*\)|\.toString\(\)|!!|\s+as\s+String\??)$""")
        val wrapper = Regex("""^(?:uri|URI|java\.net\.URI|URI\.create)\s*\((.*)\)$""")
        val quoted = Regex("""^"([^"]*)"$|^'([^']*)'$""")
        val template = Regex("""\$\{([^}]*)}|\$(\w+)""")
        val environment = Regex("""(?:getenv|environmentVariable)\s*\(\s*["'](\w+)["']\s*\)""")
        val gradleProperty = Regex("""(?:gradleProperty|findProperty|property|getProperty|properties\s*\[|extra\s*\[)\s*\(?\s*["']([\w.\-]+)["']""")
        val identifier = Regex("""^(?:(?:project|rootProject|settings)\.)?([A-Za-z_]\w*)$""")
    }
}
