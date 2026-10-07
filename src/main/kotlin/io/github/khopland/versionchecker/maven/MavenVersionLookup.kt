package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenGoalExecutionRequest
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.JDOMUtil
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
        if (artifactKind != MavenArtifactKind.DEPENDENCY) {
            val declared = declared(manager, project, artifactKind)
            if (artifactKind == MavenArtifactKind.PARENT) {
                val parent = declared.singleOrNull() ?: return emptyMap()
                val latest = try {
                    val report = execute(manager, project, mode, "display-parent-updates", DependencyUpdateReport.IGNORED_VERSIONS)
                    DependencyUpdateReport.parseParent(report, parent)?.takeIf { MavenVersionSemantics.allows(mode, parent.version, it) }
                } catch (_: VersionRetrievalFailure) {
                    MavenRepositoryMetadata.latest(project.localRepositoryPath, parent, mode, effectiveRepositoryIds(manager, project))
                } ?: return emptyMap()
                return mapOf(parent to latest)
            }
            val plugins = declared
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
        // One unsortable artifact aborts the whole goal, so exclude it, rerun, and resolve it from metadata.
        val excluded = linkedSetOf<String>()
        var report: String
        while (true) {
            try {
                report = execute(manager, project, mode, "display-dependency-updates", DependencyUpdateReport.IGNORED_VERSIONS, excluded)
                break
            } catch (failure: VersionRetrievalFailure) {
                if (!excluded.add(failure.artifact)) throw failure
            }
        }
        val updates = DependencyUpdateReport.parse(report).filter { (dependency, latest) -> MavenVersionSemantics.allows(mode, dependency.version, latest) }
        if (excluded.isEmpty()) return updates
        val repositoryIds = effectiveRepositoryIds(manager, project)
        return updates + declared(manager, project, MavenArtifactKind.DEPENDENCY)
            .filter { "${it.groupId}:${it.artifactId}" in excluded }
            .mapNotNull { dependency -> MavenRepositoryMetadata.latest(project.localRepositoryPath, dependency, mode, repositoryIds)?.let { dependency to it } }
    }

    internal suspend fun effectiveRepositoryIds(manager: MavenProjectsManager, project: MavenProject): Set<String> {
        val embedders = MavenEmbeddersManager(manager.project)
        try {
            val embedder = embedders.getEmbedder(project, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
            try {
                val profiles = manager.explicitProfiles
                val pom = embedder.evaluateEffectivePom(project.file.toNioPath().toFile(), profiles.enabledProfiles, profiles.disabledProfiles)
                    ?: error("Maven could not determine effective repositories for ${project.path}")
                val repositories = MavenRepositoryMetadata.repositories(JDOMUtil.load(pom)).filter { it.releasesPolicy?.isEnabled != false }
                // Let Maven apply the current settings' mirrors rather than matching repository IDs ourselves.
                return embedder.resolveRepositories(repositories).filter { it.releasesPolicy?.isEnabled != false }.map { it.id }.toSet()
            } finally {
                embedders.release(embedder)
            }
        } finally {
            embedders.reset()
        }
    }

    private suspend fun declared(manager: MavenProjectsManager, project: MavenProject, artifactKind: MavenArtifactKind) = readAction {
        val file = PsiManager.getInstance(manager.project).findFile(project.file) ?: return@readAction emptyList()
        val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@readAction emptyList()
        val analysis = MavenDependencyAnalysis(model, project, emptyMap())
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(analysis::coordinate)
            .filter { it.artifactKind == artifactKind && it.version.isNotBlank() }.distinct()
    }

    private suspend fun execute(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                                goal: String, ignoredVersions: String, excluded: Set<String> = emptySet()): String {
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
                if (excluded.isNotEmpty()) {
                    setProperty("dependencyExcludes", excluded.joinToString(","))
                    setProperty("dependencyManagementExcludes", excluded.joinToString(","))
                }
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
                val message = "Maven could not check ${project.path}" + details.takeIf { it.isNotBlank() }?.let { ":\n$it" }.orEmpty()
                VersionRetrievalFailure.artifact(details)?.let { throw VersionRetrievalFailure(it, message) }
                throw IOException(message)
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

/** versions-maven-plugin reports this when the resolver throws while ordering an artifact's versions. */
internal class VersionRetrievalFailure(val artifact: String, message: String) : IOException(message) {
    companion object {
        private val pattern = Regex("""Unable to retrieve versions for ([^:\s]+):([^:\s]+):""")
        fun artifact(details: String): String? = pattern.find(details)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
    }
}
