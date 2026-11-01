package io.github.khopland.versionchecker.wrapper

import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** The `distributionUrl` line of a wrapper properties file. */
internal class DistributionUrl(val range: TextRange, val text: String) {
    /** The URL with property-file escapes removed, for example `https\://` → `https://`. */
    val url get() = text.replace("\\", "")

    companion object {
        private val line = Regex("""(?m)^[ \t]*distributionUrl[ \t]*[=:][ \t]*(\S+)[ \t]*$""")

        fun find(text: String): DistributionUrl? = line.find(text)?.groups?.get(1)?.let { DistributionUrl(TextRange(it.range.first, it.range.last + 1), it.value) }

        fun hasChecksum(text: String) = Regex("""(?m)^[ \t]*distributionSha256Sum[ \t]*[=:]""").containsMatchIn(text)
    }
}

private fun wrapperDeclaration(file: PsiFile, name: String, version: String, lookup: Lookup, url: DistributionUrl, note: String?): Declaration {
    val text = file.text
    val edit = if (note != null || DistributionUrl.hasChecksum(text)) null
    else file.virtualFile?.let { Edit(it, url.range, url.text, "wrapper") { new -> url.text.replace(version, new) } }
    val reason = note ?: if (DistributionUrl.hasChecksum(text)) "Update distributionSha256Sum with the version, for example with the wrapper goal or task" else null
    // Highlight the version inside the URL.
    val start = url.text.indexOf(version).takeIf { it >= 0 }?.let { url.range.startOffset + it } ?: url.range.startOffset
    return Declaration(name, TextRange(start, start + version.length), version, lookup, edit, reason)
}

/** `.mvn/wrapper/maven-wrapper.properties`, checked against the repository in its own URL. */
class MavenWrapperProvider : DeclarationProvider {
    private val pattern = Regex("""^(https?://.+?)/org/apache/maven/apache-maven/([^/]+)/apache-maven-\2-bin\.(?:zip|tar\.gz)$""")

    override val fileNames = listOf("maven-wrapper.properties")
    override fun accepts(file: PsiFile) = file.name == "maven-wrapper.properties"

    override fun collect(file: PsiFile): List<Declaration> {
        val url = DistributionUrl.find(file.text) ?: return emptyList()
        val match = pattern.matchEntire(url.url) ?: return emptyList()
        val (base, version) = match.destructured
        val lookup = MavenLookup("org.apache.maven", "apache-maven", listOf(MavenRepo("wrapper", base)))
        return listOf(wrapperDeclaration(file, "Maven wrapper", version, lookup, url, null))
    }
}

/** `gradle/wrapper/gradle-wrapper.properties`, checked against services.gradle.org. */
class GradleWrapperProvider : DeclarationProvider {
    private val pattern = Regex("""^(https?://[^/]+)/.*?gradle-([^/]+?)-(?:bin|all)\.zip$""")

    override val fileNames = listOf("gradle-wrapper.properties")
    override fun accepts(file: PsiFile) = file.name == "gradle-wrapper.properties"

    override fun collect(file: PsiFile): List<Declaration> {
        val url = DistributionUrl.find(file.text) ?: return emptyList()
        val match = pattern.matchEntire(url.url) ?: return emptyList()
        val (host, version) = match.destructured
        val note = if (host != "https://services.gradle.org") "The wrapper uses a mirror. Change the URL after the mirror has the new version" else null
        return listOf(wrapperDeclaration(file, "Gradle wrapper", version, GradleReleases(), url, note))
    }
}

/** Every final Gradle release with its build time. */
class GradleReleases(private val http: () -> Http = Http::getInstance) : Lookup {
    override val key = "gradle-distributions"

    override suspend fun fetch(): Releases {
        val response = http().get(URL)
        if (!response.ok) throw HttpFailure("services.gradle.org returned HTTP ${response.status}")
        val times = linkedMapOf<String, Long>()
        JsonParser.parseString(response.text()).asJsonArray.map { it.asJsonObject }.forEach { release ->
            fun flag(name: String) = release.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            fun text(name: String) = release.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (flag("snapshot") || flag("nightly") || flag("releaseNightly") || flag("broken") || text("rcFor").isNotEmpty() || text("milestoneFor").isNotEmpty()) return@forEach
            times[text("version")] = runCatching { OffsetDateTime.parse(text("buildTime"), BUILD_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
        return Releases(times.keys.toList(), publishedAt = { times[it]?.takeIf { time -> time > 0 } })
    }

    companion object {
        private const val URL = "https://services.gradle.org/versions/all"
        private val BUILD_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ")
    }
}
