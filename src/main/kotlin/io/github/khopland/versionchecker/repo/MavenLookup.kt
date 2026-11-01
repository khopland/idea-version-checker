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
        val known = knownMove(groupId, artifactId)?.let { to -> async { newestRelease(to.substringBefore(':'), to.substringAfter(':')) } }
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

    /** The new coordinates when they have a stable release. A Gradle plugin marker is named by its plugin id. */
    private suspend fun newestRelease(group: String, artifact: String): Moved? = coroutineScope {
        val versions = repos.map { repo -> async { runCatching { listing(repo, path(group, artifact)) }.getOrNull() } }
            .awaitAll().flatMap { it?.versions.orEmpty() }
        val name = if (artifact == "$group.gradle.plugin") group else "$group:$artifact"
        versions.filter(Versions::isStable).maxWithOrNull(Versions::compare)?.let { Moved(name, it) }
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

        /** Packages that moved without relocation POMs, as `group:artifact`. The old coordinates get no more releases. */
        internal val MOVES = mapOf(
            "javax.servlet:javax.servlet-api" to "jakarta.servlet:jakarta.servlet-api",
            "javax.servlet:servlet-api" to "jakarta.servlet:jakarta.servlet-api",
            "javax.annotation:javax.annotation-api" to "jakarta.annotation:jakarta.annotation-api",
            "javax.validation:validation-api" to "jakarta.validation:jakarta.validation-api",
            "javax.xml.bind:jaxb-api" to "jakarta.xml.bind:jakarta.xml.bind-api",
            "javax.ws.rs:javax.ws.rs-api" to "jakarta.ws.rs:jakarta.ws.rs-api",
            "javax.ws.rs:jsr311-api" to "jakarta.ws.rs:jakarta.ws.rs-api",
            "javax.persistence:javax.persistence-api" to "jakarta.persistence:jakarta.persistence-api",
            "javax.persistence:persistence-api" to "jakarta.persistence:jakarta.persistence-api",
            "javax.inject:javax.inject" to "jakarta.inject:jakarta.inject-api",
            "javax.transaction:javax.transaction-api" to "jakarta.transaction:jakarta.transaction-api",
            "javax.activation:javax.activation-api" to "jakarta.activation:jakarta.activation-api",
            "javax.activation:activation" to "jakarta.activation:jakarta.activation-api",
            "javax.json:javax.json-api" to "jakarta.json:jakarta.json-api",
            "javax.websocket:javax.websocket-api" to "jakarta.websocket:jakarta.websocket-api",
            "javax.el:javax.el-api" to "jakarta.el:jakarta.el-api",
            "javax.mail:javax.mail-api" to "jakarta.mail:jakarta.mail-api",
            "com.sun.mail:javax.mail" to "org.eclipse.angus:angus-mail",
            "com.sun.mail:jakarta.mail" to "org.eclipse.angus:angus-mail",
            "org.glassfish:javax.json" to "org.eclipse.parsson:parsson",
            "org.glassfish:jakarta.json" to "org.eclipse.parsson:parsson",
            "log4j:log4j" to "org.apache.logging.log4j:log4j-core",
            "dom4j:dom4j" to "org.dom4j:dom4j",
            "postgresql:postgresql" to "org.postgresql:postgresql",
            "commons-lang:commons-lang" to "org.apache.commons:commons-lang3",
            "commons-collections:commons-collections" to "org.apache.commons:commons-collections4",
            "org.codehaus.jackson:jackson-mapper-asl" to "com.fasterxml.jackson.core:jackson-databind",
            "org.codehaus.jackson:jackson-core-asl" to "com.fasterxml.jackson.core:jackson-core",
            "com.squareup.okhttp:okhttp" to "com.squareup.okhttp3:okhttp",
            "com.squareup.retrofit:retrofit" to "com.squareup.retrofit2:retrofit",
            "io.reactivex:rxjava" to "io.reactivex.rxjava3:rxjava",
            "io.reactivex.rxjava2:rxjava" to "io.reactivex.rxjava3:rxjava",
            "org.mockito:mockito-all" to "org.mockito:mockito-core",
            "org.hamcrest:hamcrest-all" to "org.hamcrest:hamcrest",
            "org.hamcrest:hamcrest-core" to "org.hamcrest:hamcrest",
            "org.hamcrest:hamcrest-library" to "org.hamcrest:hamcrest",
            "org.apache.poi:poi-ooxml-schemas" to "org.apache.poi:poi-ooxml-lite",
        )

        /** Gradle plugin ids that moved. */
        internal val PLUGIN_MOVES = mapOf(
            "com.github.johnrengelman.shadow" to "com.gradleup.shadow",
            "org.jetbrains.intellij" to "org.jetbrains.intellij.platform",
        )

        /**
         * The `group:artifact` a package moved to without publishing relocation POMs. The move counts only when the
         * new coordinates have a stable release, so the patterns may guess: Jackson 3 keeps `jackson-annotations` in
         * the old group and merged some modules, and not every AWS SDK v1 module has a v2 module with the same name.
         */
        fun knownMove(groupId: String, artifactId: String): String? {
            PLUGIN_MOVES[groupId]?.takeIf { artifactId == "$groupId.gradle.plugin" }?.let { return "$it:$it.gradle.plugin" }
            MOVES["$groupId:$artifactId"]?.let { return it }
            return when {
                groupId == "com.fasterxml.jackson" || groupId.startsWith("com.fasterxml.jackson.") ->
                    "tools.jackson" + groupId.removePrefix("com.fasterxml.jackson") + ":" + artifactId
                // Testcontainers 2 prefixed every module.
                groupId == "org.testcontainers" && artifactId != "testcontainers" && !artifactId.startsWith("testcontainers-") ->
                    "$groupId:testcontainers-$artifactId"
                groupId == "org.bouncycastle" && artifactId.endsWith("-jdk15on") -> "$groupId:" + artifactId.removeSuffix("-jdk15on") + "-jdk18on"
                // v2 `core` is only a parent POM.
                groupId == "com.amazonaws" && artifactId.startsWith("aws-java-sdk-") && artifactId != "aws-java-sdk-core" ->
                    "software.amazon.awssdk:" + artifactId.removePrefix("aws-java-sdk-")
                else -> null
            }
        }

        fun parseLastUpdated(text: String): Long? =
            runCatching { LocalDateTime.parse(text.take(14), LAST_UPDATED).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
