package io.github.khopland.versionchecker.npm

import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.notifyVersionUpdates
import io.github.khopland.versionchecker.AppliedVersionChanges

internal object NpmUpdateGuidance {
    const val message = "npm updates edit package.json only. After applying, preview npm lockfile synchronization from this notification or run npm install " +
        "yourself to synchronize lockfiles and installed dependencies."

    fun notify(project: Project, changedFiles: AppliedVersionChanges? = null) =
        notifyVersionUpdates(project, listOf(message), changedFiles = changedFiles)
}
