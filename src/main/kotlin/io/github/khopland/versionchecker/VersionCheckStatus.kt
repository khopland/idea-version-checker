package io.github.khopland.versionchecker

import com.intellij.util.messages.Topic

/** Presentation events contain no repository data and never request a native check. */
internal fun interface VersionCheckStatusListener {
    fun changed(sourceFile: String?)

    companion object {
        val TOPIC = Topic.create("Dependency version status", VersionCheckStatusListener::class.java)
    }
}

internal enum class CheckPhase {
    UNCHECKED, QUEUED, CHECKING, CHECKED, FAILED, SAVE_REQUIRED, OFFLINE, PAUSED, INDEXING, UNAVAILABLE
}

internal data class VersionCheckStatus(
    val phase: CheckPhase,
    val updates: Int = 0,
    val notices: Int = 0,
    val expiresAtNanos: Long? = null,
) {
    val text: String get() = "Versions: " + when (phase) {
        CheckPhase.UNCHECKED -> "unchecked"
        CheckPhase.QUEUED -> "queued"
        CheckPhase.CHECKING -> if (updates > 0) "checking · $updates updates" else "checking…"
        CheckPhase.CHECKED -> when {
            updates > 0 -> "$updates updates"
            notices > 0 -> "needs review"
            else -> "checked"
        }
        CheckPhase.FAILED -> "retry"
        CheckPhase.SAVE_REQUIRED -> "save build files"
        CheckPhase.OFFLINE -> "offline"
        CheckPhase.PAUSED -> "paused"
        CheckPhase.INDEXING -> "indexing"
        CheckPhase.UNAVAILABLE -> "unavailable"
    }

    val tooltip: String get() = when (phase) {
        CheckPhase.UNCHECKED -> "This file has no current complete result. Refresh Current File to check."
        CheckPhase.QUEUED -> "This file is waiting for another native version check to finish."
        CheckPhase.CHECKING -> "Checking this file. Early hints may appear before every declaration is checked."
        CheckPhase.CHECKED -> "Checked the supported declarations in this file. $updates updates and $notices notices. Click to review or refresh."
        CheckPhase.FAILED -> "The check did not finish successfully. Check repository authentication and configuration, then Refresh Current File to retry."
        CheckPhase.SAVE_REQUIRED -> "Native checking requires saved build files. Refresh Current File saves documents and checks again."
        CheckPhase.OFFLINE -> "Disable the build system’s Work offline setting, then Refresh Current File."
        CheckPhase.PAUSED -> "Version checks are disabled in Settings → Tools → Version Checker."
        CheckPhase.INDEXING -> "Waiting for IDEA indexing to finish."
        CheckPhase.UNAVAILABLE -> "Build information is unavailable. Import or reload the project, then Refresh Current File."
    }
}
