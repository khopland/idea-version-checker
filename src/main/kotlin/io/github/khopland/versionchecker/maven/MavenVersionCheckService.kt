package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.khopland.versionchecker.VersionCheckService
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.BuildSnapshot
import io.github.khopland.versionchecker.core.UpdateReport
import org.jetbrains.idea.maven.project.MavenProject

internal data class MavenInspectionResult(
    val adapter: BuildSystemAdapter,
    val snapshot: BuildSnapshot,
    val report: UpdateReport,
)

/** Thin inspection bridge; cached result scheduling belongs to the shared coordinator. */
@Service(Service.Level.PROJECT)
internal class MavenVersionCheckService(private val project: Project) {
    private val adapter: BuildSystemAdapter get() = BuildSystemAdapter.find("maven") ?: error("Maven adapter unavailable")
    fun inspection(mavenProject: MavenProject): MavenInspectionResult? {
        val adapter = adapter
        val snapshot = adapter.snapshot(project, mavenProject.file) ?: return null
        val report = project.service<VersionCheckService>().updates(adapter, snapshot) ?: UpdateReport()
        return MavenInspectionResult(adapter, snapshot, report)
    }

    fun updates(mavenProject: MavenProject): Map<DependencyVersion, String> =
        inspection(mavenProject)?.report?.mavenUpdates().orEmpty()
}
