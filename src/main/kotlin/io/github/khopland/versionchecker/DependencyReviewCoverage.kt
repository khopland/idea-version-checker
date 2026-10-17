package io.github.khopland.versionchecker

/** Counts cached observations, including separate modes/contexts for the same physical declaration. */
internal object DependencyReviewCoverage {
    fun description(rows: List<DependencyReviewRow>): String {
        val checked = rows.count { !it.checkFailed && it.outcome in setOf(DependencyReviewOutcome.UPDATE,
            DependencyReviewOutcome.CHECKED, DependencyReviewOutcome.IGNORED) }
        val updates = rows.count { it.outcome == DependencyReviewOutcome.UPDATE }
        val failed = rows.count { it.outcome == DependencyReviewOutcome.FAILED || it.checkFailed }
        val incomplete = rows.any { it.outcome in setOf(DependencyReviewOutcome.SKIPPED,
            DependencyReviewOutcome.REVIEW, DependencyReviewOutcome.FAILED, DependencyReviewOutcome.PENDING,
            DependencyReviewOutcome.UNREPORTED, DependencyReviewOutcome.NO_DECLARATIONS) || it.checkFailed || it.checking }
        return buildList {
            add("Coverage across all cached observations: $checked checked · $updates updates")
            fun count(outcome: DependencyReviewOutcome, label: String) {
                rows.count { it.outcome == outcome }.takeIf { it > 0 }?.let { add("$it $label") }
            }
            count(DependencyReviewOutcome.SKIPPED, "skipped / unsupported")
            count(DependencyReviewOutcome.REVIEW, "need review")
            if (failed > 0) add("$failed failed")
            count(DependencyReviewOutcome.PENDING, "pending")
            count(DependencyReviewOutcome.UNREPORTED, "unknown")
            count(DependencyReviewOutcome.NO_DECLARATIONS, "contexts without recognized declarations")
        }.joinToString(" · ") + if (incomplete) ". Coverage incomplete; inspect row details and next actions." else "."
    }

    fun nextAction(row: DependencyReviewRow): String = when {
        row.checking -> "Wait for the running check, then inspect the refreshed result."
        row.checkFailed || row.outcome == DependencyReviewOutcome.FAILED ->
            "Inspect the diagnostic and check source, correct the build or repository problem, then Refresh Whole Project."
        else -> when (row.outcome) {
            DependencyReviewOutcome.SKIPPED ->
                "Open the source and inspect the skipped declaration. Check its version manually if this declaration is unsupported."
            DependencyReviewOutcome.REVIEW ->
                "Open the source and inspect the diagnostic and shared-version consumers before changing this version."
            DependencyReviewOutcome.UNREPORTED ->
                "Refresh Whole Project. If coverage remains unknown, check this declaration manually; no successful check was reported."
            DependencyReviewOutcome.PENDING -> "Refresh Whole Project to obtain a completed check."
            DependencyReviewOutcome.NO_DECLARATIONS ->
                "Inspect the check source and coverage scope for unsupported declarations; check them manually if needed."
            DependencyReviewOutcome.UPDATE -> "Use Review Updates… to prepare a fresh, validated update."
            DependencyReviewOutcome.IGNORED -> "Review ignored-version settings if you want this update considered."
            DependencyReviewOutcome.CHECKED -> "Refresh Whole Project when you need current results."
            DependencyReviewOutcome.FAILED -> error("Handled above")
        }
    }
}
