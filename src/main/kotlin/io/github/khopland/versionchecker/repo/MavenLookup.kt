package io.github.khopland.versionchecker.repo

import com.intellij.openapi.util.JDOMUtil
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Moved
import io.github.khopland.versionchecker.core.PublishedTimes
import io.github.khopland.versionchecker.core.Releases
import io.github.khopland.versionchecker.core.Versions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A Maven-layout repository. [headers] carry its credentials. [hint] explains how to fix an authentication failure. */
data class MavenRepo(val id: String, val url: String, val headers: Map<String, String> = emptyMap(), val hint: String? = null) {
    val base get() = url.trimEnd('/')

    companion object {
        val CENTRAL = MavenRepo("central", "https://repo.maven.apache.org/maven2")
        val GOOGLE = MavenRepo("google", "https://dl.google.com/dl/android/maven2")
        val GRADLE_PLUGINS = MavenRepo("gradle-plugins", "https://plugins.gradle.org/m2")
    }
}

/**
 * Reads `maven-metadata.xml` from every repository in parallel and merges the versions.
 * Publication times come from the POM's `Last-Modified` header, and only when the quarantine needs them.
 * A move comes from the `<relocation>` in the newest POM, or from [knownMove].
 */
class MavenLookup(val groupId: String, val artifactId: String, val repos: List<MavenRepo>,
                  private val http: () -> Http = Http::getInstance,
                  private val times: () -> PublishedTimes? = { PublishedTimes.getInstance() }) : Lookup {
    override val key = "maven:$groupId:$artifactId@" + repos.joinToString(",") { it.base }

    private val path get() = path(groupId, artifactId)

    private class Listing(val repo: MavenRepo, val versions: List<String>, val lastUpdated: Long?)

    override suspend fun fetch(): Releases = coroutineScope {
        val results = repos.map { repo -> async { runCatching { listing(repo) } } }.awaitAll()
        val listings = results.mapNotNull { it.getOrNull() }
        if (listings.isEmpty()) {
            throw results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: HttpFailure("$groupId:$artifactId was not found in ${repos.joinToString { it.base }}")
        }
        val owners = linkedMapOf<String, MavenRepo>()
        listings.forEach { listing -> listing.versions.forEach { owners.putIfAbsent(it, listing.repo) } }
        val newest = listings.map { it.lastUpdated }.takeIf { null !in it }?.maxOf { it!! }
        Releases(owners.keys.toList(), allPublishedBefore = newest, publishedAt = { version -> owners[version]?.let { published(it, version) } },
            moved = moved(owners))
    }

    /** A move is extra information, so a failure to read it leaves it out. */
    private suspend fun moved(owners: Map<String, MavenRepo>): Moved? = coroutineScope {
        val known = knownMove(groupId)?.let { group -> async { newestRelease(group) } }
        runCatching { relocation(owners) }.getOrNull() ?: known?.await()
    }

    private suspend fun relocation(owners: Map<String, MavenRepo>): Moved? {
        val version = owners.keys.filterNot { it.endsWith("-SNAPSHOT") }.maxWithOrNull(Versions::compare) ?: return null
        val repo = owners.getValue(version)
        val response = http().get("${repo.base}/$path/$version/$artifactId-$version.pom", repo.headers)
        if (!response.ok) return null
        val root = JDOMUtil.load(response.body.inputStream())
        val relocation = root.getChild("distributionManagement", root.namespace)?.getChild("relocation", root.namespace) ?: return null
        fun text(name: String) = relocation.getChildTextTrim(name, root.namespace)?.ifEmpty { null }
        val to = "${text("groupId") ?: groupId}:${text("artifactId") ?: artifactId}"
        return if (to == "$groupId:$artifactId") null else Moved(to, message = text("message"))
    }

    /** The artifact under [group] when it has a stable release there. */
    private suspend fun newestRelease(group: String): Moved? = coroutineScope {
        val versions = repos.map { repo -> async { runCatching { listing(repo, path(group, artifactId)) }.getOrNull() } }
            .awaitAll().flatMap { it?.versions.orEmpty() }
        versions.filter(Versions::isStable).maxWithOrNull(Versions::compare)?.let { Moved("$group:$artifactId", it) }
    }

    /** A missing artifact in one repository is normal, so 404 gives an empty listing. */
    private suspend fun listing(repo: MavenRepo, path: String = this.path): Listing? {
        val url = "${repo.base}/$path/maven-metadata.xml"
        val response = http().get(url, repo.headers)
        if (response.status == 404) return null
        if (!response.ok) throw failure(repo, response.status)
        val root = JDOMUtil.load(response.body.inputStream())
        val versioning = root.getChild("versioning")
        val versions = versioning?.getChild("versions")?.getChildren("version")?.map { it.textTrim }.orEmpty()
        return Listing(repo, versions, versioning?.getChildTextTrim("lastUpdated")?.let(::parseLastUpdated))
    }

    private suspend fun published(repo: MavenRepo, version: String): Long? {
        val url = "${repo.base}/$path/$version/$artifactId-$version.pom"
        times()?.get(url)?.let { return it }
        val response = http().head(url, repo.headers)
        if (!response.ok) return null
        val time = response.header("Last-Modified")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?: return null
        times()?.put(url, time)
        return time
    }

    private fun failure(repo: MavenRepo, status: Int): HttpFailure {
        val reason = when (status) {
            401 -> "needs credentials"
            403 -> "refused access"
            else -> "returned HTTP $status"
        }
        return HttpFailure("${repo.base} $reason" + (repo.hint?.takeIf { status == 401 || status == 403 }?.let { ". $it" } ?: ""))
    }

    companion object {
        private val LAST_UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        private fun path(group: String, artifact: String) = group.replace('.', '/') + "/" + artifact

        /**
         * The group a package moved to without publishing relocation POMs. The move counts only when the same
         * artifact has a stable release there: Jackson 3 keeps `jackson-annotations` in the old group and merged some modules.
         */
        fun knownMove(groupId: String): String? = when {
            groupId == "com.fasterxml.jackson" || groupId.startsWith("com.fasterxml.jackson.") -> "tools.jackson" + groupId.removePrefix("com.fasterxml.jackson")
            else -> null
        }

        fun parseLastUpdated(text: String): Long? =
            runCatching { LocalDateTime.parse(text.take(14), LAST_UPDATED).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
