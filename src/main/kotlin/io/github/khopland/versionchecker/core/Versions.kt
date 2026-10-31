package io.github.khopland.versionchecker.core

/** How far a candidate is from the current version, and the update modes the user selects. */
enum class UpdateKind {
    PATCH, MINOR, MAJOR;

    val label: String get() = name.lowercase()
    val title: String get() = name.lowercase().replaceFirstChar(Char::uppercase)

    /** Whether a mode allows a candidate of [kind]. */
    fun allows(kind: UpdateKind) = kind.ordinal <= ordinal
}

/** Version parsing and ordering shared by Maven, Gradle and npm. */
object Versions {
    private val unstable = Regex(
        """(?i)(?:^|[.\-_+])(?:alpha|a|beta|b|milestone|m|rc|cr|pre|preview|snapshot|dev|ea|eap|incubating|canary|nightly|next|experimental|insiders)(?:[.\-_]?\d+)*(?=$|[.\-_+])"""
    )
    private val numericPrefix = Regex("""^[vV]?(\d+(?:\.\d+)*)(.*)$""")
    private val releaseQualifiers = setOf("", "final", "release", "ga")

    fun isStable(version: String) = numericPrefix.matches(version) && !unstable.containsMatchIn(version)

    /** The numeric part, for example `[4, 13, 2]` for `4.13.2-jre`. */
    fun numbers(version: String): List<Long> =
        numericPrefix.matchEntire(version)?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: Long.MAX_VALUE }.orEmpty()

    /**
     * The text after the numbers without digits or separators, so `-jre` stays a `jre` candidate
     * and `.Final`, `.RELEASE` and `.GA` count as no qualifier.
     */
    fun flavor(version: String): String {
        val rest = numericPrefix.matchEntire(version)?.groupValues?.get(2) ?: return version.lowercase()
        val letters = rest.lowercase().filter(Char::isLetter)
        return if (letters in releaseQualifiers) "" else letters
    }

    fun compare(left: String, right: String): Int {
        val a = numbers(left)
        val b = numbers(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return qualifier(left).compareTo(qualifier(right))
    }

    val comparator: Comparator<String> = Comparator(::compare)

    private fun qualifier(version: String) = numericPrefix.matchEntire(version)?.groupValues?.get(2).orEmpty()

    fun kind(current: String, candidate: String): UpdateKind {
        val a = numbers(current)
        val b = numbers(candidate)
        return when {
            a.getOrElse(0) { 0 } != b.getOrElse(0) { 0 } -> UpdateKind.MAJOR
            a.getOrElse(1) { 0 } != b.getOrElse(1) { 0 } -> UpdateKind.MINOR
            else -> UpdateKind.PATCH
        }
    }

    /** Stable versions newer than [current] with the same flavor, newest first. */
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
