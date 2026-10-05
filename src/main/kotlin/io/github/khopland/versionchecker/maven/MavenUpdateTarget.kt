package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.core.*
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.intellij.openapi.vfs.VirtualFile

internal typealias MavenArtifactKind = ArtifactRole

internal fun selectMavenProjects(manager: MavenProjectsManager, scope: UpdateScope, currentPom: VirtualFile?): List<MavenProject> =
    when (scope) {
        UpdateScope.WHOLE_PROJECT -> manager.nonIgnoredProjects.toList()
        UpdateScope.CURRENT_FILE -> listOfNotNull(currentPom?.let(manager::findProject))
            .filter { !manager.isIgnored(it) }
    }
