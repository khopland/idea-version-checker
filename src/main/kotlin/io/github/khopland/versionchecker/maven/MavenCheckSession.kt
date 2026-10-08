package io.github.khopland.versionchecker.maven

import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenEmbedderWrapper

/** One fresh embedder per scan, shared by its goals, without changing IDEA's import settings. */
internal suspend fun <T> withMavenCheckSession(
    manager: MavenProjectsManager, project: MavenProject, action: suspend (MavenEmbedderWrapper) -> T
): T {
    val embedders = MavenEmbeddersManager(manager.project)
    try {
        val embedder = embedders.getEmbedder(project, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
        try {
            return action(embedder)
        } finally {
            embedders.release(embedder)
        }
    } finally {
        embedders.reset()
    }
}
