package io.github.khopland.versionchecker.followup

import java.nio.file.Path

/** Presentation only: a reload request is not evidence of successful IDE synchronization. */
internal data class BuildReloadOutcome(val phase: Phase, val provider: String, val requestAttempted: Boolean = false) {
    enum class Phase { REQUESTED, COMPLETED, CANCELLED, UNAVAILABLE, FAILED }
    val warning get() = phase in setOf(Phase.UNAVAILABLE, Phase.FAILED)
    val message: String get() = when (phase) {
        Phase.REQUESTED -> "$provider reload requested. IDE synchronization completion was not observed. Check the build tool window for its result."
        Phase.COMPLETED -> "$provider reload completed. Check the build tool window for import diagnostics."
        Phase.CANCELLED -> if (requestAttempted) "$provider reload follow-up was cancelled during or after the reload request. Check the build tool window to see whether synchronization started."
            else "$provider reload follow-up was cancelled before requesting reload. Build files may already have been saved."
        Phase.UNAVAILABLE -> "$provider reload is unavailable. Enable or import the build tool and use its IDE tool window to reload."
        Phase.FAILED -> if (requestAttempted) "Could not confirm the outcome of the $provider reload request. IDE synchronization may have started. Check the build tool window before retrying."
            else "Could not request $provider reload. Trust the project, save its build files and use the IDE build tool window to reload."
    }
}

/** Native writes remain separate from declaration edits and cannot be undone by declaration undo. */
internal data class NpmFollowUpOutcome(
    val phase: Phase,
    val changed: List<Path> = emptyList(),
    val exitCode: Int? = null,
    val changesKnown: Boolean = true,
) {
    enum class Phase { NOT_STARTED, CANCELLED_BEFORE_START, COMPLETED, CANCELLED, TIMED_OUT, FAILED }
    val warning get() = phase in setOf(Phase.NOT_STARTED, Phase.CANCELLED, Phase.TIMED_OUT, Phase.FAILED) || !changesKnown
    val message: String get() = buildString {
        append(when (phase) {
            Phase.NOT_STARTED -> "npm lockfile synchronization did not start. Review and save project files, check Node/npm settings, then preview again."
            Phase.CANCELLED_BEFORE_START -> "npm lockfile synchronization was cancelled before the command started."
            Phase.COMPLETED -> "npm lockfile synchronization command completed."
            Phase.CANCELLED -> "npm lockfile synchronization cancellation was requested. Process termination was not confirmed; lockfile writes may still occur."
            Phase.TIMED_OUT -> "npm lockfile synchronization command timed out. Process termination was not confirmed; lockfile writes may still occur."
            Phase.FAILED -> "npm lockfile synchronization command failed${exitCode?.let { " (exit $it)" }.orEmpty()}. Check Node/npm settings, registry access and package constraints."
        })
        if (phase !in setOf(Phase.NOT_STARTED, Phase.CANCELLED_BEFORE_START)) {
            append("\nChanged lockfiles at inspection: ")
            append(if (!changesKnown) "could not be inspected" else if (changed.isEmpty()) "none" else changed.joinToString { it.toString() })
            if (phase != Phase.COMPLETED || !changesKnown) append("\nThe command may have written partial changes. Review the lockfile diff before retrying.")
            append("\nLockfile writes are separate from declaration undo. Review the version control diff; installed dependencies were not synchronized.")
        }
    }
}
