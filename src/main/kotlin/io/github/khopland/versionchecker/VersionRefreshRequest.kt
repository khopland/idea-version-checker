package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.BuildSelection
import io.github.khopland.versionchecker.core.UpdateScope

/** Refresh intent determines saving, invalidation, priority, and failure targets together. */
internal sealed interface VersionRefreshRequest {
    val selection: BuildSelection
    val label: String get() = selection.scope.label
    val interactive: Boolean get() = this is Retry || selection.scope == UpdateScope.CURRENT_FILE
    fun requestedFiles(adapterId: String): Set<String>? = null

    sealed interface UserRequested : VersionRefreshRequest

    data class Manual(val currentFile: String? = null) : UserRequested {
        override val selection = BuildSelection(
            if (currentFile == null) UpdateScope.WHOLE_PROJECT else UpdateScope.CURRENT_FILE, currentFile)
    }

    /** Null files request the whole provider; otherwise retry only the named sources. */
    data class Retry(val files: Map<String, Set<String>?>) : UserRequested {
        override val selection = BuildSelection(UpdateScope.WHOLE_PROJECT)
        override val label = "Failed Checks"
        override fun requestedFiles(adapterId: String): Set<String>? = files[adapterId]
    }
}
