package io.github.khopland.versionchecker.npm

import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.notifyVersionUpdates

internal object NpmUpdateGuidance {
    const val message = "npm updates edit package.json only. After applying, use IntelliJ's npm install action or run npm install " +
        "to synchronize lockfiles and installed dependencies."

    fun notify(project: Project) = notifyVersionUpdates(project, listOf(message))
}
