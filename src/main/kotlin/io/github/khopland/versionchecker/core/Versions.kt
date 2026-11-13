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
 * `-incubating` is a variant: Apache incubator projects publish real releases with it.
 */
object Versions {
    private val markers = setOf("alpha", "a", "beta", "b", "milestone", "m", "rc", "cr", "pre", "preview", "snapshot", "dev", "ea", "eap", "canary",
        "nightly", "next", "experimental", "insiders")
    private val releaseWords = setOf("final", "release", "ga")
    private const val SEPARATORS = ".-_+"

    /**
     * A version read once. [numbers] is empty and the rest unset for text that does not start with a number. Build
     * numbers directly after the numbers belong to the version, not the [variant].
     */
    private class Parsed(val numbers: LongArray, val matched: Boolean, val prerelease: List<String> = emptyList(),
                         val build: List<String> = emptyList(), val variant: List<String> = emptyList()) {
        val stable get() = matched && prerelease.isEmpty()
    }

    private val UNKNOWN = Parsed(LongArray(0), false)

    private fun digit(c: Char) = c in '0'..'9'

    /**
     * Reads `v1.2.3` and what follows it. Versions are compared and sorted in the thousands for one package, so this
     * scans the text once instead of matching several patterns for every comparison.
     */
    private fun parse(version: String): Parsed {
        var i = if (version.isNotEmpty() && (version[0] == 'v' || version[0] == 'V')) 1 else 0
        if (i >= version.length || !digit(version[i]) || version.any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) return UNKNOWN
        val numbers = ArrayList<Long>(4)
        while (true) {
            val start = i
            while (i < version.length && digit(version[i])) i++
            numbers += version.substring(start, i).toLongOrNull() ?: Long.MAX_VALUE
            if (i + 1 < version.length && version[i] == '.' && digit(version[i + 1])) i++ else break
        }
        if (i == version.length) return Parsed(numbers.toLongArray(), true)
        val tokens = ArrayList<String>(4)
        while (i < version.length) {
            while (i < version.length && version[i] in SEPARATORS) i++
            val start = i
            while (i < version.length && version[i] !in SEPARATORS) i++
            if (i > start) version.substring(start, i).takeIf { it.lowercase() !in releaseWords }?.let(tokens::add)
        }
        val prerelease = ArrayList<String>(2)
        val build = ArrayList<String>(1)
        val variant = ArrayList<String>(2)
        var t = 0
        while (t < tokens.size) {
            val token = tokens[t]
            when {
                marker(token) -> {
                    prerelease += token.lowercase()
                    // A number after a marker is its counter, as in `rc-5`.
                    while (t + 1 < tokens.size && tokens[t + 1].all(Char::isDigit)) prerelease += tokens[++t]
                }
                variant.isEmpty() && prerelease.isEmpty() && token.all(Char::isDigit) -> build += token
                else -> variant += token.lowercase()
            }
            t++
        }
        return Parsed(numbers.toLongArray(), true, prerelease, build, variant)
    }

    /** A prerelease word with an optional counter, such as `rc`, `RC1` or `beta2`. */
    private fun marker(token: String): Boolean {
        var end = token.length
        while (end > 0 && digit(token[end - 1])) end--
        if (end == 0) return false
        val word = CharArray(end) { token[it].let { c -> if (c in 'A'..'Z') c + 32 else c } }
        return String(word) in markers
    }

    fun isStable(version: String) = parse(version).stable

    /** The numeric part, for example `[4, 13, 2]` for `4.13.2-jre`. */
    fun numbers(version: String): List<Long> = parse(version).numbers.asList()

    /** The variant, for example `jre` for `33.0.0-jre` and `groovy.4.0` for `2.3-groovy-4.0`. Empty for `5.6.15.Final`. */
    fun flavor(version: String): String = flavor(version, parse(version))

    private fun flavor(version: String, parsed: Parsed) = if (parsed.matched) parsed.variant.joinToString(".") else version.lowercase()

    fun compare(left: String, right: String): Int = if (left == right) 0 else compare(left, parse(left), right, parse(right))

    private fun compare(left: String, a: Parsed, right: String, b: Parsed): Int {
        compareNumbers(a.numbers, b.numbers).let { if (it != 0) return it }
        if (!a.matched || !b.matched) return left.compareTo(right)
        compareTokens(a.build, b.build).let { if (it != 0) return it }
        // A prerelease comes before the release with the same numbers.
        if (a.prerelease.isEmpty() != b.prerelease.isEmpty()) return if (a.prerelease.isEmpty()) 1 else -1
        compareTokens(a.prerelease, b.prerelease).let { if (it != 0) return it }
        return compareTokens(a.variant, b.variant)
    }

    private fun compareNumbers(a: LongArray, b: LongArray): Int {
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
        val a = parse(current).numbers
        val b = parse(candidate).numbers
        return when {
            a.getOrElse(0) { 0 } != b.getOrElse(0) { 0 } -> UpdateKind.MAJOR
            a.getOrElse(1) { 0 } != b.getOrElse(1) { 0 } -> UpdateKind.MINOR
            else -> UpdateKind.PATCH
        }
    }

    /** Stable versions newer than [current] with the same variant, newest first. Each version is read once, also for the sort. */
    fun candidates(current: String, versions: Collection<String>): List<String> {
        val base = parse(current)
        val flavor = flavor(current, base)
        return versions.asSequence().distinct()
            .map { it to parse(it) }
            .filter { (version, parsed) -> parsed.stable && flavor(version, parsed) == flavor && compare(version, parsed, current, base) > 0 }
            .sortedWith { (left, a), (right, b) -> compare(right, b, left, a) }
            .map { it.first }
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
