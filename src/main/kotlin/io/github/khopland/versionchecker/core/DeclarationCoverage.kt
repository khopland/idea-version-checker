package io.github.khopland.versionchecker.core

internal enum class DeclarationOutcome { CHECKED, SKIPPED, MANUAL_REVIEW, FAILED, IGNORED }
internal data class DeclarationCoverage(val outcome: DeclarationOutcome, val reason: String? = null)

/** Counts refer to recognized declarations, never inferred from candidate/notice totals. */
internal data class CoverageSummary(
    val checked: Int = 0, val skipped: Int = 0, val review: Int = 0,
    val failed: Int = 0, val ignored: Int = 0, val pending: Int = 0
) {
    val description: String get() = buildList {
        add("$checked checked")
        if (skipped > 0) add("$skipped skipped")
        if (review > 0) add("$review need review")
        if (failed > 0) add("$failed failed")
        if (ignored > 0) add("$ignored ignored")
        if (pending > 0) add("$pending pending")
    }.joinToString(" · ")

    operator fun plus(other: CoverageSummary) = CoverageSummary(checked + other.checked, skipped + other.skipped,
        review + other.review, failed + other.failed, ignored + other.ignored, pending + other.pending)

    companion object {
        fun <K> from(outcomes: Map<K, DeclarationCoverage>, expected: Set<K>? = null,
                 ignored: Set<K> = emptySet()): CoverageSummary {
            val counts = outcomes.values.groupingBy { it.outcome }.eachCount()
            val checked = (counts[DeclarationOutcome.CHECKED] ?: 0) + (counts[DeclarationOutcome.IGNORED] ?: 0)
            val ignoredCount = outcomes.count { (id, result) -> result.outcome == DeclarationOutcome.IGNORED ||
                result.outcome == DeclarationOutcome.CHECKED && id in ignored }
            return CoverageSummary(checked, counts[DeclarationOutcome.SKIPPED] ?: 0,
                counts[DeclarationOutcome.MANUAL_REVIEW] ?: 0, counts[DeclarationOutcome.FAILED] ?: 0,
                ignoredCount, expected?.count { it !in outcomes } ?: 0)
        }
    }
}

internal fun BuildSnapshot.declarationIds(): Set<DeclarationId> = declarations.map { it.id }.toSet() + skippedDeclarations.keys
internal fun BuildSnapshot.skippedCoverage(): Map<DeclarationId, DeclarationCoverage> =
    skippedDeclarations.mapValues { DeclarationCoverage(DeclarationOutcome.SKIPPED, it.value) }

internal fun UpdateReport.coverageSummary(snapshot: BuildSnapshot? = null): CoverageSummary? =
    coverage?.let { CoverageSummary.from(it, snapshot?.declarationIds()) }
