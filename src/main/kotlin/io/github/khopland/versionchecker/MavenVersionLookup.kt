package io.github.khopland.versionchecker

import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenGoalExecutionRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.Properties

/** Let the Maven server handle settings.xml, settings-security.xml, mirrors, proxies and transports. */
internal object MavenVersionLookup {
    suspend fun check(manager: MavenProjectsManager, project: MavenProject,
                      mode: UpdateMode = UpdateMode.MAJOR): Map<DependencyVersion, String> {
        val output = Files.createTempFile("maven-version-checker-", ".txt")
        // Own the embedder lifetime so a refresh reads settings.xml again and cannot disturb Maven imports.
        val embedders = MavenEmbeddersManager(manager.project)
        try {
            val properties = Properties().apply {
                // executeGoal replaces the request properties, so preserve Maven config -D values.
                putAll(MavenConfigProperties.read(project.file.toNioPath().parent))
                setProperty("versions.outputFile", output.toString())
                setProperty("versions.outputLineWidth", "4096")
                setProperty("versions.overwriteOutput", "true")
                setProperty("versions.logOutput", "false")
                setProperty("outputEncoding", "UTF-8")
                setProperty("allowSnapshots", "false")
                setProperty("allowMajorUpdates", (mode == UpdateMode.MAJOR).toString())
                setProperty("allowMinorUpdates", (mode != UpdateMode.PATCH).toString())
                setProperty("maven.version.ignore", DependencyUpdateReport.IGNORED_VERSIONS)
                setProperty("processDependencyManagement", "true")
                setProperty("processDependencyManagementTransitive", "false")
                setProperty("processPluginDependencies", "false")
                setProperty("processPluginDependenciesInPluginManagement", "false")
                setProperty("displayManagedBy", "false")
            }
            val id = project.mavenId
            // Restrict an aggregator scan to this POM, keeping each module's repository context separate.
            val request = MavenGoalExecutionRequest(
                project.file.toNioPath().toFile(), manager.explicitProfiles,
                listOf("${id.groupId}:${id.artifactId}"), properties
            )
            val embedder = embedders.getEmbedder(project, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
            val results = try {
                withMavenProgress { reporter ->
                    embedder.executeGoal(
                        listOf(request),
                        "org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates",
                        reporter, MavenLogEventHandler
                    )
                }
            } finally {
                embedders.release(embedder)
            }
            if (results.isEmpty() || results.any { !it.success }) {
                val details = results.flatMap { it.problems }.mapNotNull { it.description }.joinToString("\n")
                throw IOException("Maven could not check ${project.path}" + details.takeIf { it.isNotBlank() }?.let { ":\n$it" }.orEmpty())
            }
            // The goal can remove its output file when a successful module has nothing to report.
            // Empty aggregators and up-to-date modules must not abort a reactor-wide update scan.
            val report = try { Files.readString(output) } catch (_: NoSuchFileException) { "" }
            return DependencyUpdateReport.parse(report).filter { (dependency, latest) -> mode.allows(dependency.version, latest) }
        } finally {
            embedders.reset()
            Files.deleteIfExists(output)
        }
    }
}
