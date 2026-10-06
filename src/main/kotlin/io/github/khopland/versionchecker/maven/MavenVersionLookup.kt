package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenGoalExecutionRequest
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.Properties

/** Let the Maven server handle settings.xml, settings-security.xml, mirrors, proxies and transports. */
internal object MavenVersionLookup {
    suspend fun check(manager: MavenProjectsManager, project: MavenProject,
                      mode: UpdateMode = UpdateMode.MAJOR,
                      artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY): Map<DependencyVersion, String> {
        if (artifactKind == MavenArtifactKind.PLUGIN) {
            val plugins = readAction {
                val file = PsiManager.getInstance(manager.project).findFile(project.file) ?: return@readAction emptyList()
                val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@readAction emptyList()
                val analysis = MavenDependencyAnalysis(model, project, emptyMap())
                PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(analysis::coordinate)
                    .filter { it.artifactKind == MavenArtifactKind.PLUGIN && it.version.isNotBlank() }.distinct()
            }
            val mavenVersion = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file).version
            return buildMap {
                // display-plugin-updates has no allowMajor/MinorUpdates options. Limit its version
                // search before querying, so patch/minor checks find the latest within each branch.
                for ((branch, candidates) in plugins.groupBy { MavenPluginUpdates.branch(it.version, mode) }) {
                    if (branch == null) continue
                    val report = execute(manager, project, mode, "display-plugin-updates", MavenPluginUpdates.ignoredVersions(branch))
                    putAll(MavenPluginUpdates.parse(report, candidates, mode, mavenVersion))
                }
            }
        }
        val report = execute(manager, project, mode, "display-dependency-updates", DependencyUpdateReport.IGNORED_VERSIONS)
        return DependencyUpdateReport.parse(report).filter { (dependency, latest) -> MavenVersionSemantics.allows(mode, dependency.version, latest) }
    }

    private suspend fun execute(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                                goal: String, ignoredVersions: String): String {
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
                setProperty("maven.version.ignore", ignoredVersions)
                setProperty("processUnboundPlugins", "true")
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
                        "org.codehaus.mojo:versions-maven-plugin:2.21.0:$goal",
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
            return report
        } finally {
            embedders.reset()
            Files.deleteIfExists(output)
        }
    }
}
