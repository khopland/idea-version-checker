package io.github.khopland.versionchecker.npm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Releases
import java.time.Instant

/**
 * Reads a package document from the registry. Without a quarantine it asks for the small
 * install document, which has versions and deprecations but no publication times.
 */
internal class NpmLookup(val packageName: String, private val registry: NpmRegistry, private val withTimes: Boolean,
                         private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "npm:$packageName@${registry.url}${if (withTimes) "+time" else ""}"

    override suspend fun fetch(): Releases {
        val url = registry.url + packageName.replace("/", "%2f")
        val accept = if (withTimes) "application/json" else "application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8"
        val response = http().get(url, registry.headers + ("Accept" to accept))
        // Private or workspace-only packages are not in the registry. Treat them as having no updates.
        if (response.status == 404) return Releases(emptyList())
        if (!response.ok) throw HttpFailure("${registry.url} returned HTTP ${response.status}" +
            if (response.status == 401 || response.status == 403) ". Check the auth token in .npmrc" else "")
        return parse(JsonParser.parseString(response.text()).asJsonObject)
    }

    companion object {
        fun parse(document: JsonObject): Releases {
            val versions = document.getAsJsonObject("versions") ?: JsonObject()
            val deprecated = versions.entrySet().mapNotNull { (version, manifest) ->
                manifest.asJsonObject.get("deprecated")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { version to it }
            }.toMap()
            val times = document.getAsJsonObject("time")?.entrySet()?.mapNotNull { (version, time) ->
                runCatching { version to Instant.parse(time.asString).toEpochMilli() }.getOrNull()
            }?.toMap().orEmpty()
            // Semver prerelease versions have a hyphen after the version core.
            val stable = versions.keySet().filter { '-' !in it.substringBefore('+') }
            return Releases(stable, deprecated, publishedAt = { times[it] })
        }
    }
}
