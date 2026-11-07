package io.github.khopland.versionchecker.core

import com.intellij.codeInsight.intention.IntentionAction

/** Package ecosystems that vulnerability databases know. Gradle libraries are Maven packages. */
enum class Ecosystem { MAVEN, NPM }

/** One package version as a vulnerability database names it: `group:artifact` on Maven, the package name on npm. */
data class Pkg(val ecosystem: Ecosystem, val name: String, val version: String)

/** Affected versions from [introduced] up to [fixed], or up to and including [lastAffected]. Open-ended when both are null. */
data class AffectedRange(val introduced: String, val fixed: String? = null, val lastAffected: String? = null) {
    operator fun contains(version: String) = Versions.compare(version, introduced) >= 0 &&
        (fixed == null || Versions.compare(version, fixed) < 0) && (lastAffected == null || Versions.compare(version, lastAffected) <= 0)
}

/**
 * One known vulnerability of one package. [severity] is LOW, MODERATE, HIGH or CRITICAL. [ranges] and [versions]
 * say which versions it affects; a source without them gives only [fixed], and every version below a fix counts as affected.
 * [unread] means only the ID is known, because its details could not be read.
 */
data class Advisory(
    val id: String,
    val aliases: List<String>,
    val severity: String?,
    val summary: String?,
    val fixed: List<String>,
    val ranges: List<AffectedRange> = emptyList(),
    val versions: List<String> = emptyList(),
    val unread: Boolean = false,
) {
    /** The CVE ID when there is one, otherwise the advisory's own ID. */
    val displayId get() = (listOf(id) + aliases).firstOrNull { it.startsWith("CVE-") } ?: id

    /** Higher is worse. 0 when the severity is unknown. */
    val rank get() = when (severity?.uppercase()) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        "MODERATE", "MEDIUM" -> 2
        "LOW" -> 1
        else -> 0
    }

    fun affects(version: String): Boolean =
        if (ranges.isEmpty() && versions.isEmpty()) fixed.isEmpty() || fixed.any { Versions.compare(version, it) < 0 }
        else version in versions || ranges.any { version in it }

    /** The fix on the branch of [version], or else the lowest fix above it. */
    fun fixedFor(version: String): String? =
        ranges.filter { version in it }.mapNotNull { it.fixed }.minWithOrNull(Versions.comparator)
            ?: fixed.filter { Versions.compare(it, version) > 0 }.minWithOrNull(Versions.comparator)
}

/** A vulnerability database, such as OSV.dev or a Nexus IQ server. */
interface VulnerabilitySource {
    /** Sources with the same key give the same answers, so they share cached results. */
    val key: String get() = javaClass.name

    /** The advisories that affect each package. Packages without any may be left out. */
    suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>>

    /** More Alt+Enter actions for a vulnerable package, such as opening it in Nexus IQ to waive it. */
    fun actions(pkg: Pkg, advisories: List<Advisory>): List<IntentionAction> = emptyList()
}

/**
 * What a build file says about the Nexus IQ application it belongs to: the public ID it [declared], such as
 * `clm.applicationId` in a POM, and [names] the application may have, nearest first, such as its artifact ID. [where] tells
 * the user where the file can declare one.
 */
data class ApplicationHint(val declared: String? = null, val names: List<String> = emptyList(), val where: String? = null)

/** The advisories for one declaration, the version a fix writes, and the source's [actions]. */
class Vulnerable(val advisories: List<Advisory>, val fix: String?, val actions: List<IntentionAction> = emptyList())

object Vulnerabilities {
    /** The lowest stable version above [current], with the same variant, that no advisory affects and [allowed] accepts. */
    suspend fun nearestSafe(current: String, versions: Collection<String>, advisories: List<Advisory>,
                            allowed: suspend (String) -> Boolean = { true }): String? =
        Versions.candidates(current, versions).asReversed().firstOrNull { version -> advisories.none { it.affects(version) } && allowed(version) }
}
