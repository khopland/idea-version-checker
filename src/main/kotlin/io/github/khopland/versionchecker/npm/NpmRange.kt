package io.github.khopland.versionchecker.npm

/**
 * npm version ranges, as peer dependencies and `engines` write them: `^18.0.0 || ^19.0.0`, `>=18.18 <21`, `1.x`, `1.2.3 - 2`.
 * Only stable versions are tested, so a prerelease in a range counts as the release it leads to.
 */
internal object NpmRange {
    private const val PART = """(\d+|[xX*])"""
    private val comparator = Regex("""^(\^|~>?|>=|<=|>|<|=)?v?$PART(?:\.$PART)?(?:\.$PART)?(-[0-9A-Za-z.\-]+)?(?:\+[0-9A-Za-z.\-]+)?$""")
    private val stable = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?$""")
    private val operatorSpace = Regex("""(\^|~>?|>=|<=|>|<|=)\s+""")
    private val hyphen = Regex("""^(\S+)\s+-\s+(\S+)$""")
    private val space = Regex("""\s+""")
    private const val NEWEST = 999_999L

    /**
     * Whether [version] is in [range], or null when either is not understood, such as `workspace:*`, a tag or a prerelease.
     * A version without minor or patch, as `.nvmrc` allows, counts as the newest one it can mean.
     */
    fun satisfies(range: String, version: String): Boolean? {
        val numbers = stable.matchEntire(version.trim())?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: NEWEST } ?: return null
        val sets = range.split("||").map { set(it.trim(), numbers) }
        return if (true in sets) true else if (null in sets) null else false
    }

    private fun set(text: String, version: List<Long>): Boolean? {
        val parts = hyphen.matchEntire(text)?.let { listOf(">=${it.groupValues[1]}", "<=${it.groupValues[2]}") }
            ?: text.replace(operatorSpace, "$1").split(space).filter { it.isNotEmpty() }
        val results = parts.map { one(it, version) }
        return if (false in results) false else if (null in results) null else true
    }

    private fun one(text: String, version: List<Long>): Boolean? {
        val match = comparator.matchEntire(text) ?: return null
        val operator = match.groupValues[1]
        // `1.x` and `1` give one part, `*` none.
        val given = match.groupValues.subList(2, 5).map { it.toLongOrNull() }.takeWhile { it != null }.map { it!! }
        val prerelease = match.groupValues[5].isNotEmpty()
        val low = given + List(3 - given.size) { 0L }
        // The first version after those that start with the parts up to [index]: 1.2 gives 1.3.0. Null when there is none.
        fun after(index: Int) = if (index < 0) null else low.take(index) + (low[index] + 1) + List(2 - index) { 0L }
        fun atLeast(bound: List<Long>) = compare(version, bound) >= 0
        fun below(bound: List<Long>?) = bound == null || compare(version, bound) < 0
        val next = after(given.size - 1)
        val exact = given.size == 3 && !prerelease
        return when (operator) {
            "", "=" -> !prerelease && atLeast(low) && below(next)
            // Up to the next change of the first part that is not zero.
            "^" -> atLeast(low) && below(after(given.indexOfFirst { it != 0L }.let { if (it < 0) given.size - 1 else it }))
            "~", "~>" -> atLeast(low) && below(after(minOf(given.size - 1, 1)))
            ">=" -> atLeast(low)
            ">" -> if (exact) compare(version, low) > 0 else if (given.size == 3) atLeast(low) else next != null && atLeast(next)
            "<" -> compare(version, low) < 0
            else -> if (exact) compare(version, low) <= 0 else if (given.size == 3) below(low) else below(next)
        }
    }

    private fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until 3) a[i].compareTo(b[i]).let { if (it != 0) return it }
        return 0
    }
}
