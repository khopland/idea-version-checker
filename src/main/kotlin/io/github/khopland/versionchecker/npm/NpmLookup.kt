package io.github.khopland.versionchecker.npm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Releases

/**
 * Reads the small install document from the registry, which has versions and deprecations.
 * The minimum release age does not apply: a registry quarantine, such as Nexus Firewall for npm,
 * already leaves held-back versions out of this document.
 */
internal class NpmLookup(val packageName: String, private val registry: NpmRegistry,
                         private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "npm:$packageName@${registry.url}"

    override suspend fun fetch(): Releases {
        val url = registry.url + packageName.replace("/", "%2f")
        val accept = "application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8"
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
            // Semver prerelease versions have a hyphen after the version core.
            val stable = versions.keySet().filter { '-' !in it.substringBefore('+') }
            return Releases(stable, deprecated, ignoresMinimumAge = true)
        }
    }
}
