package io.github.khopland.versionchecker.npm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Releases
import io.github.khopland.versionchecker.core.ReleaseNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Reader
import java.time.Instant

/**
 * Reads the small install document from the registry, which has versions and deprecations.
 * Release times are only in the full document, which can be megabytes. It is read once, and only when the minimum
 * release age asks about a version, so only for a package that has an update.
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
        val times = Times(url)
        return parse(JsonParser.parseString(response.text()).asJsonObject, times::of)
    }

    private inner class Times(private val url: String) {
        private val mutex = Mutex()
        private var times: Map<String, Long>? = null

        /** Null when the time is unknown, so the version stays hidden: the registry does not list it, or the request failed. */
        suspend fun of(version: String): Long? {
            val times = mutex.withLock { times ?: load()?.also { times = it } } ?: return null
            // A registry that gives no times at all cannot hold a version back.
            return if (times.isEmpty()) 0 else times[version]
        }

        private suspend fun load(): Map<String, Long>? = try {
            http().get(url, registry.headers + ("Accept" to "application/json")).takeIf { it.ok }?.let { times(it.body.inputStream().reader()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override val hasReleaseNotes get() = true

    /** The `repository` or `homepage` of the version's manifest, as GitHub releases when the package is on GitHub. */
    override suspend fun releaseNotes(version: String): String? {
        val response = http().get(registry.url + packageName.replace("/", "%2f") + "/" + version, registry.headers + ("Accept" to "application/json"))
        if (!response.ok) return null
        return releaseNotes(JsonParser.parseString(response.text()).asJsonObject)
    }

    companion object {
        private val shorthand = Regex("""^(?:github:)?([\w.\-]+)/([\w.\-]+)$""")

        fun releaseNotes(manifest: JsonObject): String? {
            val repository = manifest.get("repository")?.let { if (it.isJsonObject) it.asJsonObject.get("url") else it }
                ?.takeIf { it.isJsonPrimitive }?.asString
            repository?.let { shorthand.matchEntire(it) }?.let { return "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}/releases" }
            repository?.let(ReleaseNotes::github)?.let { return it }
            return ReleaseNotes.page(manifest.get("homepage")?.takeIf { it.isJsonPrimitive }?.asString) ?: ReleaseNotes.page(repository)
        }

        /** The `time` of the full document: when each version was published. The rest is skipped, not kept in memory. */
        fun times(document: Reader): Map<String, Long> = JsonReader(document).use { reader ->
            val times = HashMap<String, Long>()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "time" || reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val version = reader.nextName()
                    if (reader.peek() != JsonToken.STRING) reader.skipValue()
                    else runCatching { Instant.parse(reader.nextString()).toEpochMilli() }.getOrNull()?.let { times[version] = it }
                }
                return@use times
            }
            times
        }

        fun parse(document: JsonObject, publishedAt: (suspend (String) -> Long?)? = null): Releases {
            val versions = document.getAsJsonObject("versions") ?: JsonObject()
            val deprecated = versions.entrySet().mapNotNull { (version, manifest) ->
                manifest.asJsonObject.get("deprecated")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { version to it }
            }.toMap()
            // Semver prerelease versions have a hyphen after the version core.
            val stable = versions.keySet().filter { '-' !in it.substringBefore('+') }
            return Releases(stable, deprecated, publishedAt = publishedAt)
        }
    }
}
