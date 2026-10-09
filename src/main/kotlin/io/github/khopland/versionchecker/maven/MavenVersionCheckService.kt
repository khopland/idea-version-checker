package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.VersionCheckService
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import org.jetbrains.idea.maven.project.MavenProject

/** Thin inspection bridge; cached result scheduling belongs to the shared coordinator. */
@Service(Service.Level.PROJECT)
internal class MavenVersionCheckService(private val project: Project) {
    private val adapter: BuildSystemAdapter get() = BuildSystemAdapter.find("maven") ?: error("Maven adapter unavailable")
    fun updates(mavenProject: MavenProject): Map<DependencyVersion, String> {
        val snapshot = adapter.snapshot(project, mavenProject.file) ?: return emptyMap()
        return project.service<VersionCheckService>().updates(adapter, snapshot)?.mavenUpdates().orEmpty()
    }
    fun relocations(mavenProject: MavenProject): Map<DependencyVersion, String> {
        val snapshot = adapter.snapshot(project, mavenProject.file) ?: return emptyMap()
        return project.service<VersionCheckService>().updates(adapter, snapshot)?.mavenRelocations().orEmpty()
    }
}
