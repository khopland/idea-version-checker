package io.github.khopland.versionchecker.gradle

import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A published Gradle release from services.gradle.org; [buildTime] is epoch milliseconds. */
internal data class GradleRelease(val version: String, val buildTime: Long?)

/**
 * `gradle/wrapper/gradle-wrapper.properties`. Gradle distributions are not Maven artifacts, so only
 * the official services.gradle.org URL is checked; mirrored distribution URLs need manual review.
 */
internal class GradleWrapperAdapter(
    private val releases: suspend () -> List<GradleRelease> = ::officialReleases,
) : WrapperBuildSystemAdapter("gradle-wrapper", "Gradle wrapper", "gradle", ArtifactId("gradle", "distribution")) {
    override fun isOffline(project: Project) = GradleSettings.getInstance(project).isOfflineWork

    override fun wrapperFiles(project: Project): List<VirtualFile> = GradleSettings.getInstance(project).linkedProjectsSettings
        .mapNotNull { findFile(project, it.externalProjectPath)?.takeIf { it.isValid }?.findFileByRelativePath("gradle/wrapper/gradle-wrapper.properties") }
        .filter { it.isValid && !it.isDirectory }.distinctBy { it.path }

    override fun version(url: String): String? = distribution.find(url)?.groupValues?.get(1)
    override fun review(distribution: WrapperDistribution): String? =
        if (official.matches(distribution.url.replace("\\", ""))) null
        else "The distribution URL is not services.gradle.org; mirrored Gradle distributions need manual review"
    override fun newer(current: String, latest: String) = GradleVersions.newer(current, latest)
    override fun kind(current: String, latest: String) = GradleVersions.kind(current, latest)

    override suspend fun lookup(project: Project, file: String, current: String, mode: UpdateMode): WrapperLookup {
        if (isOffline(project)) return WrapperLookup(failure = "Gradle offline mode is enabled")
        val checkedAt = System.nanoTime()
        val all = try { releases() } catch (failure: java.io.IOException) {
            return WrapperLookup(failure = "Could not read Gradle releases from services.gradle.org: ${failure.message}")
        }
        val age = MinimumReleaseAge.of(project.service<VersionCheckerSettings>().state.minimumReleaseAgeHours)
        val latest = all.filter { release ->
            GradleVersions.fixed(release.version) && GradleVersions.newer(current, release.version) && allows(mode, current, release.version) &&
                (age == null || release.buildTime?.let(age::oldEnough) == true)
        }.map { it.version }.reduceOrNull { best, next -> if (GradleVersions.newer(best, next)) next else best }
        return WrapperLookup(latest, validUntilNanos = checkedAt + TimeUnit.MINUTES.toNanos(10), checkedAtNanos = checkedAt)
    }

    private fun allows(mode: UpdateMode, current: String, latest: String) = when (mode) {
        UpdateMode.MAJOR -> true
        UpdateMode.MINOR -> GradleVersions.kind(current, latest) != VersionChangeKind.MAJOR
        UpdateMode.PATCH -> GradleVersions.kind(current, latest) == VersionChangeKind.PATCH
    }

    companion object {
        private val distribution = Regex("/gradle-([^/]+?)-(?:bin|all)\\.zip$")
        private val official = Regex("^https://services\\.gradle\\.org/distributions/gradle-[^/]+-(?:bin|all)\\.zip$")
        private const val RELEASES = "https://services.gradle.org/versions/all"
        @Volatile private var cached: Pair<Long, List<GradleRelease>>? = null

        /** One request per ten minutes, through the IDE's proxy settings. */
        suspend fun officialReleases(): List<GradleRelease> {
            cached?.let { (at, releases) -> if (System.nanoTime() - at < TimeUnit.MINUTES.toNanos(10)) return releases }
            val json = runInterruptible(Dispatchers.IO) {
                HttpRequests.request(RELEASES).connectTimeout(10_000).readTimeout(30_000).readString()
            }
            return parse(json).also { cached = System.nanoTime() to it }
        }

        internal fun parse(json: String): List<GradleRelease> {
            val format = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return JsonParser.parseString(json).asJsonArray.map { it.asJsonObject }.filter { release ->
                listOf("snapshot", "nightly", "releaseNightly", "broken").none { release.get(it)?.asBoolean == true } &&
                    release.get("rcFor")?.asString.isNullOrEmpty() && release.get("milestoneFor")?.asString.isNullOrEmpty()
            }.map { release ->
                GradleRelease(release.get("version").asString,
                    release.get("buildTime")?.asString?.let { runCatching { format.parse(it).time }.getOrNull() })
            }
        }
    }
}

class GradleWrapperVersionInspection : WrapperVersionInspection("gradle-wrapper")
