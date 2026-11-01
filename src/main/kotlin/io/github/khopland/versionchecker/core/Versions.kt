package io.github.khopland.versionchecker.core

/** How far a candidate is from the current version, and the update modes the user selects. */
enum class UpdateKind {
    PATCH, MINOR, MAJOR;

    val label: String get() = name.lowercase()
    val title: String get() = name.lowercase().replaceFirstChar(Char::uppercase)

    /** Whether a mode allows a candidate of [kind]. */
    fun allows(kind: UpdateKind) = kind.ordinal <= ordinal
}

/**
 * Version parsing and ordering shared by Maven, Gradle and npm.
 *
 * A version is read as numbers, then an optional prerelease marker, then an optional variant:
 * `2.4-M4-groovy-4.0` is `[2, 4]`, milestone 4, variant `groovy-4.0`. Only stable versions are
 * suggested, and only with the same variant, so `-jre` stays `-jre` and `-groovy-3.0` never becomes
 * `-groovy-4.0`. A prerelease is older than its final release, so `4.0.0-rc-5` is offered `4.0.0`.
 */
object Versions {
    private val numericPrefix = Regex("""^[vV]?(\d+(?:\.\d+)*)(.*)$""")
    private val marker = Regex("""(?i)^(?:alpha|a|beta|b|milestone|m|rc|cr|pre|preview|snapshot|dev|ea|eap|incubating|canary|nightly|next|experimental|insiders)\d*$""")
    private val separators = Regex("""[.\-_+]+""")
    private val releaseWords = setOf("final", "release", "ga")

    /** The parts after the numbers. Build numbers directly after the numbers belong to the version, not the variant. */
    private class Suffix(val prerelease: List<String>, val build: List<String>, val variant: List<String>)

    private fun suffix(version: String): Suffix? {
        val rest = numericPrefix.matchEntire(version)?.groupValues?.get(2) ?: return null
        val tokens = rest.split(separators).filter { it.isNotEmpty() && it.lowercase() !in releaseWords }
        val prerelease = mutableListOf<String>()
        val build = mutableListOf<String>()
        val variant = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                marker.matches(token) -> {
                    prerelease += token.lowercase()
                    // A number after a marker is its counter, as in `rc-5`.
                    while (i + 1 < tokens.size && tokens[i + 1].all(Char::isDigit)) prerelease += tokens[++i]
                }
                variant.isEmpty() && prerelease.isEmpty() && token.all(Char::isDigit) -> build += token
                else -> variant += token.lowercase()
            }
            i++
        }
        return Suffix(prerelease, build, variant)
    }

    fun isStable(version: String) = suffix(version)?.prerelease?.isEmpty() == true

    /** The numeric part, for example `[4, 13, 2]` for `4.13.2-jre`. */
    fun numbers(version: String): List<Long> =
        numericPrefix.matchEntire(version)?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: Long.MAX_VALUE }.orEmpty()

    /** The variant, for example `jre` for `33.0.0-jre` and `groovy.4.0` for `2.3-groovy-4.0`. Empty for `5.6.15.Final`. */
    fun flavor(version: String): String = suffix(version)?.variant?.joinToString(".") ?: version.lowercase()

    fun compare(left: String, right: String): Int {
        compareNumbers(numbers(left), numbers(right)).let { if (it != 0) return it }
        val a = suffix(left)
        val b = suffix(right)
        if (a == null || b == null) return left.compareTo(right)
        compareTokens(a.build, b.build).let { if (it != 0) return it }
        // A prerelease comes before the release with the same numbers.
        if (a.prerelease.isEmpty() != b.prerelease.isEmpty()) return if (a.prerelease.isEmpty()) 1 else -1
        compareTokens(a.prerelease, b.prerelease).let { if (it != 0) return it }
        return compareTokens(a.variant, b.variant)
    }

    private fun compareNumbers(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Numbers compare as numbers and text compares alphabetically. A missing part sorts first. */
    private fun compareTokens(a: List<String>, b: List<String>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: return -1
            val y = b.getOrNull(i) ?: return 1
            val c = x.toLongOrNull()?.let { nx -> y.toLongOrNull()?.let { nx.compareTo(it) } } ?: x.compareTo(y, ignoreCase = true)
            if (c != 0) return c
        }
        return 0
    }

    val comparator: Comparator<String> = Comparator(::compare)

    fun kind(current: String, candidate: String): UpdateKind {
        val a = numbers(current)
        val b = numbers(candidate)
        return when {
            a.getOrElse(0) { 0 } != b.getOrElse(0) { 0 } -> UpdateKind.MAJOR
            a.getOrElse(1) { 0 } != b.getOrElse(1) { 0 } -> UpdateKind.MINOR
            else -> UpdateKind.PATCH
        }
    }

    /** Stable versions newer than [current] with the same variant, newest first. */
    fun candidates(current: String, versions: Collection<String>): List<String> {
        val flavor = flavor(current)
        return versions.asSequence().distinct()
            .filter { isStable(it) && flavor(it) == flavor && compare(it, current) > 0 }
            .sortedWith(comparator.reversed())
            .toList()
    }
}

/** The newest allowed version for each update mode. Null means no update in that mode. */
data class Targets(val patch: String?, val minor: String?, val major: String?) {
    operator fun get(mode: UpdateKind) = when (mode) {
        UpdateKind.PATCH -> patch
        UpdateKind.MINOR -> minor
        UpdateKind.MAJOR -> major
    }

    val isEmpty get() = major == null

    companion object {
        val NONE = Targets(null, null, null)

        /**
         * [oldEnough] is asked newest first and only as far as needed, so a quarantine reads few release dates.
         */
        suspend fun pick(current: String, versions: Collection<String>, oldEnough: suspend (String) -> Boolean): Targets {
            val candidates = Versions.candidates(current, versions)
            if (candidates.isEmpty()) return NONE
            val checked = hashMapOf<String, Boolean>()
            suspend fun newest(mode: UpdateKind): String? = candidates.firstOrNull { candidate ->
                mode.allows(Versions.kind(current, candidate)) && checked.getOrPut(candidate) { oldEnough(candidate) }
            }
            return Targets(newest(UpdateKind.PATCH), newest(UpdateKind.MINOR), newest(UpdateKind.MAJOR))
        }
    }
}
