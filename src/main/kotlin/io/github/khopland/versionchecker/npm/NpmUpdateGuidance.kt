package io.github.khopland.versionchecker.npm

import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.notifyVersionUpdates
import io.github.khopland.versionchecker.AppliedVersionChanges

internal object NpmUpdateGuidance {
    const val message = "npm updates edit package.json only. After applying, use Update npm lockfile… in this notification or run npm install " +
        "yourself to synchronize lockfiles and installed dependencies."

    fun message(manager: NodePackageManager): String = if (manager == NodePackageManager.NPM) message else
        "${manager.title} updates edit package.json only. Run ${manager.id} install yourself to synchronize lockfiles and installed dependencies."

    fun notify(project: Project, changedFiles: AppliedVersionChanges? = null, manager: NodePackageManager = NodePackageManager.NPM) =
        notifyVersionUpdates(project, listOf(message(manager)), buildSystems = setOf(manager.id), changedFiles = changedFiles)
}
