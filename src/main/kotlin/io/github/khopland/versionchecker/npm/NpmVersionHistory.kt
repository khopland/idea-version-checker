package io.github.khopland.versionchecker.npm

import io.github.khopland.versionchecker.UpdateMode

internal data class NpmPublishedVersion(val text: String, val version: NpmVersion)

/** Parsed once per metadata response. Text and equal-precedence order remain registry-owned. */
internal class NpmVersionHistory(versions: List<String>) {
    private val publishedBaselines = mutableMapOf<NpmVersion, String>()
    private val descending = versions.mapNotNull { text ->
        NpmVersion.parse(text)?.let { version ->
            // Build metadata does not affect precedence. Baseline notices use the first original
            // spelling, just as the previous firstOrNull lookup did before candidate sorting.
            publishedBaselines.putIfAbsent(version, text)
            NpmPublishedVersion(text, version)
        }
    }.sortedByDescending { it.version }

    // Charge both parsed records and baseline-map entries in addition to the raw version list.
    val retainedVersionWeight: Long get() = descending.size.toLong() + publishedBaselines.size

    fun publishedBaseline(baseline: NpmVersion): String? = publishedBaselines[baseline]

    fun eligible(baseline: NpmVersion, mode: UpdateMode): Sequence<NpmPublishedVersion> {
        val ceiling = when (mode) {
            UpdateMode.PATCH -> NpmVersion(baseline.major, baseline.minor + 1, 0)
            UpdateMode.MINOR -> NpmVersion(baseline.major + 1, 0, 0)
            UpdateMode.MAJOR -> null
        }
        val start = ceiling?.let(::firstBelow) ?: 0
        return descending.subList(start, descending.size).asSequence()
            .takeWhile { baseline.allows(it.version, mode) }
    }

    /** Skip newer major/minor groups without scanning them for every patch/minor consumer. */
    private fun firstBelow(ceiling: NpmVersion): Int {
        var low = 0
        var high = descending.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (descending[middle].version >= ceiling) low = middle + 1 else high = middle
        }
        return low
    }
}
