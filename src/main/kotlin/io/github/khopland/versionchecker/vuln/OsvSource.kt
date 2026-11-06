package io.github.khopland.versionchecker.vuln

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import io.github.khopland.versionchecker.core.AffectedRange
import io.github.khopland.versionchecker.core.Advisory
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.Versions
import io.github.khopland.versionchecker.core.VulnerabilitySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [OSV.dev](https://osv.dev): a batch query returns the advisory IDs per package, then each advisory is read once.
 * Advisories stay on disk until OSV reports a new modification time.
 */
class OsvSource(private val http: () -> Http = Http::getInstance, private val api: String = "https://api.osv.dev/v1") : VulnerabilitySource {
    internal data class Ref(val id: String, val modified: String?)

    override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> = coroutineScope {
        val refs = packages.distinct().chunked(BATCH).map { async { query(it) } }.awaitAll().flatMap { it.entries }.associate { it.toPair() }
        val documents = refs.values.flatten().distinct().associateWith { async { advisory(it) } }
        refs.filterValues { it.isNotEmpty() }.mapValues { (pkg, ids) ->
            // An advisory that cannot be read still shows, by its ID and without a fix.
            ids.map { ref ->
                documents.getValue(ref).await()?.let { document ->
                    runCatching { parse(document, pkg) }.onFailure { LOG.info("Could not parse ${ref.id}", it) }.getOrNull()
                } ?: Advisory(ref.id, emptyList(), null, null, emptyList(), unread = true)
            }
        }
    }

    private suspend fun query(packages: List<Pkg>): Map<Pkg, List<Ref>> {
        val queries = JsonArray().apply {
            packages.forEach { pkg ->
                add(JsonObject().apply {
                    add("package", JsonObject().apply {
                        addProperty("ecosystem", ecosystem(pkg.ecosystem))
                        addProperty("name", pkg.name)
                    })
                    addProperty("version", pkg.version)
                })
            }
        }
        val response = http().post("$api/querybatch", JsonObject().apply { add("queries", queries) }.toString())
        if (!response.ok) throw HttpFailure("osv.dev returned HTTP ${response.status}")
        // A version query has a few advisories, so the `next_page_token` for more than a thousand is not read.
        val results = parseBatch(response.text())
        if (results.size != packages.size) throw HttpFailure("osv.dev returned ${results.size} results for ${packages.size} packages")
        return packages.zip(results).toMap()
    }

    private suspend fun advisory(ref: Ref): JsonObject? = try {
        val url = "$api/vulns/${ref.id}"
        val response = if (ref.modified != null) http().getVersioned(url, ref.modified) else http().get(url)
        if (!response.ok) throw HttpFailure("osv.dev returned HTTP ${response.status} for ${ref.id}")
        JsonParser.parseString(response.text()).asJsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.info("Could not read ${ref.id}", e)
        null
    }

    companion object {
        private val LOG = logger<OsvSource>()

        /** The most queries OSV accepts in one batch. */
        const val BATCH = 1000

        fun ecosystem(ecosystem: Ecosystem) = when (ecosystem) {
            Ecosystem.MAVEN -> "Maven"
            Ecosystem.NPM -> "npm"
        }

        /** The advisory IDs for each query, in query order. */
        internal fun parseBatch(text: String): List<List<Ref>> =
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("results")?.map { result ->
                (result as? JsonObject)?.array("vulns").orEmpty().mapNotNull { vuln ->
                    (vuln as? JsonObject)?.let { it.string("id")?.let { id -> Ref(id, it.string("modified")) } }
                }
            }.orEmpty()

        /** One advisory, with the ranges and fixes for [pkg] only. */
        fun parse(document: JsonObject, pkg: Pkg): Advisory {
            val affected = document.array("affected").map { it.asJsonObject }.filter { entry ->
                entry.getAsJsonObject("package")?.let { it.string("ecosystem") == ecosystem(pkg.ecosystem) && it.string("name") == pkg.name } == true
            }
            val ranges = affected.flatMap { it.array("ranges") }.map { it.asJsonObject }.filter { it.string("type") != "GIT" }
                .flatMap { ranges(it.array("events")) }
            val severity = document.getAsJsonObject("database_specific")?.string("severity")
                ?: document.array("severity").firstNotNullOfOrNull { it.asJsonObject.string("score")?.let(Cvss::rating) }
            return Advisory(
                id = document.string("id")!!,
                aliases = document.array("aliases").map { it.asString },
                severity = severity?.uppercase(),
                summary = document.string("summary")?.trim()?.ifEmpty { null },
                fixed = ranges.mapNotNull { it.fixed }.distinct(),
                ranges = ranges,
                versions = affected.flatMap { it.array("versions") }.map { it.asString },
            )
        }

        /** Events sorted by version: each `introduced` opens a range, and the next `fixed` or `last_affected` closes it. */
        private fun ranges(events: List<JsonElement>): List<AffectedRange> {
            val result = mutableListOf<AffectedRange>()
            var open: String? = null
            val sorted = events.map { it.asJsonObject }.filter { it.entrySet().size == 1 && it.entrySet().first().value.isJsonPrimitive }
                .sortedWith(compareBy(Versions.comparator) { it.entrySet().first().value.asString })
            for (event in sorted) {
                event.string("introduced")?.let { open?.let { start -> result += AffectedRange(start) }; open = it }
                val start = open ?: continue
                event.string("fixed")?.let { result += AffectedRange(start, fixed = it); open = null }
                event.string("last_affected")?.let { result += AffectedRange(start, lastAffected = it); open = null }
            }
            open?.let { result += AffectedRange(it) }
            return result
        }
    }
}
