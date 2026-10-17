package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DeclarationOutcome

/** Presentation state has an identity independent of the labels used by the UI and JSON export. */
internal enum class DependencyReviewOutcome(val label: String, val hasObservationTime: Boolean = true) {
    UPDATE("Update"),
    REVIEW("Review"),
    CHECKED("Checked"),
    SKIPPED("Skipped"),
    FAILED("Failed"),
    IGNORED("Ignored"),
    PENDING("Pending", hasObservationTime = false),
    UNREPORTED("Coverage unreported", hasObservationTime = false),
    NO_DECLARATIONS("No recognized declarations");

    companion object {
        fun fromCoverage(outcome: DeclarationOutcome): DependencyReviewOutcome = when (outcome) {
            DeclarationOutcome.CHECKED -> CHECKED
            DeclarationOutcome.SKIPPED -> SKIPPED
            DeclarationOutcome.MANUAL_REVIEW -> REVIEW
            DeclarationOutcome.FAILED -> FAILED
            DeclarationOutcome.IGNORED -> IGNORED
        }
    }
}
