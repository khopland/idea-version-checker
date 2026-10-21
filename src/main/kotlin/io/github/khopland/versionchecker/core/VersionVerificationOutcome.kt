package io.github.khopland.versionchecker.core

/** Completion describes the observation operation, never successful dependency or test verification. */
internal data class VersionVerificationOutcome(val status: Status, val message: String) {
    enum class Status { OBSERVED, CANCELLED, FAILED }
}
