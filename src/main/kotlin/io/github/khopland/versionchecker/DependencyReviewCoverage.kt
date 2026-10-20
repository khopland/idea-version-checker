package io.github.khopland.versionchecker

/** Counts cached observations, including separate modes/contexts for the same physical declaration. */
internal object DependencyReviewCoverage {
    fun description(rows: List<DependencyReviewRow>): String {
        val counts = rows.groupingBy { it.outcome }.eachCount()
        val checked = rows.count { !it.failed && it.outcome.isChecked }
        val updates = counts[DependencyReviewOutcome.UPDATE] ?: 0
        val failed = rows.count { it.failed }
        val incomplete = rows.any { it.failed || it.checking || !it.outcome.isChecked }
        return buildList {
            add("Coverage across all cached observations: $checked checked · $updates updates")
            fun count(outcome: DependencyReviewOutcome, label: String) {
                counts[outcome]?.let { add("$it $label") }
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
        row.checking -> "Wait for the check to finish. Then examine the new result."
        row.failed ->
            "Examine the diagnostic and check source. Correct the build or repository problem. Then select Refresh Whole Project."
        else -> when (row.outcome) {
            DependencyReviewOutcome.SKIPPED ->
                "Open the source. Examine the skipped declaration. Check its version manually if this declaration is unsupported."
            DependencyReviewOutcome.REVIEW ->
                "Open the source. Examine the diagnostic and shared-version consumers before you change this version."
            DependencyReviewOutcome.UNREPORTED ->
                "Select Refresh Whole Project. If coverage stays unknown, check this declaration manually. No successful check was reported."
            DependencyReviewOutcome.PENDING -> "Select Refresh Whole Project to complete the check."
            DependencyReviewOutcome.NO_DECLARATIONS ->
                "Examine the check source and coverage scope. Check unsupported declarations manually."
            DependencyReviewOutcome.UPDATE -> "Select Review Updates… to check and prepare the update."
            DependencyReviewOutcome.IGNORED -> "Examine the ignored-version settings to include this update."
            DependencyReviewOutcome.CHECKED -> "Select Refresh Whole Project when you need current results."
            DependencyReviewOutcome.FAILED -> error("Handled above")
        }
    }
}
