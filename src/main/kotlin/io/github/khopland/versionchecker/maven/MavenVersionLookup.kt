package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.server.MavenEmbedderWrapper
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import org.jetbrains.idea.maven.server.MavenGoalExecutionRequest
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.util.progress.reportProgressScope
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
                      artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY): Map<DependencyVersion, String> =
        runScan(manager, project, mode, setOf(artifactKind), null)

    suspend fun checkAll(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                         kinds: Set<MavenArtifactKind>, coordinates: List<DependencyVersion>? = null): Map<DependencyVersion, String> =
        runScan(manager, project, mode, kinds, coordinates)

    /** Keep one embedder per POM; failed categories cannot discard successful earlier categories. */
    suspend fun checkAllIncrementally(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                                     kinds: Set<MavenArtifactKind>, coordinates: List<DependencyVersion>,
                                     publish: suspend (MavenArtifactKind, Result<Map<DependencyVersion, String>>) -> Unit): Map<DependencyVersion, String> =
        runScan(manager, project, mode, kinds, coordinates, publish)

    private suspend fun runScan(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                                kinds: Set<MavenArtifactKind>, coordinates: List<DependencyVersion>?,
                                publish: (suspend (MavenArtifactKind, Result<Map<DependencyVersion, String>>) -> Unit)? = null): Map<DependencyVersion, String> {
        if (kinds.isEmpty()) return emptyMap()
        val inputs = MavenScanInputs(coordinates ?: declared(manager, project), kinds,
            MavenConfigProperties.read(project.file.toNioPath().parent))
        if (inputs.kinds.isEmpty()) return emptyMap()
        val profiles = readAction { manager.explicitProfiles.clone() }
        return withMavenCheckSession(manager, project) { embedder ->
            val scan = Scan(manager, project, embedder, inputs, profiles, filtered = coordinates != null)
            expireMetadata(scan)
            reportProgressScope(inputs.kinds.size) { reporter ->
                val ordered = if (publish == null) inputs.kinds.toList() else
                    listOf(MavenArtifactKind.DEPENDENCY, MavenArtifactKind.PLUGIN, MavenArtifactKind.PARENT).filter { it in inputs.kinds }
                checkMavenCategories(ordered, publish) { kind ->
                    reporter.itemStep("Maven ${kind.name.lowercase()} versions") { check(scan, mode, kind) }
                }
            }
        }
    }

    private suspend fun check(scan: Scan, mode: UpdateMode, artifactKind: MavenArtifactKind): Map<DependencyVersion, String> {
        val manager = scan.manager
        val project = scan.project
        if (artifactKind != MavenArtifactKind.DEPENDENCY) {
            val declared = scan.inputs[artifactKind]
            if (artifactKind == MavenArtifactKind.PARENT) {
                val parent = declared.singleOrNull() ?: return emptyMap()
                val latest = try {
                    val report = execute(scan, mode, "display-parent-updates", DependencyUpdateReport.IGNORED_VERSIONS)
                    DependencyUpdateReport.parseParent(report, parent)?.takeIf { MavenVersionSemantics.allows(mode, parent.version, it) }
                } catch (_: VersionRetrievalFailure) {
                    MavenRepositoryMetadata.latest(project.localRepositoryPath, parent, mode, scan.repositories(plugins = false))
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
                    val report = execute(scan, mode, "display-plugin-updates", MavenPluginUpdates.ignoredVersions(branch))
                    putAll(MavenPluginUpdates.parse(report, candidates, mode, mavenVersion))
                }
            }
        }
        // One unsortable artifact aborts the whole goal, so exclude it, rerun, and resolve it from metadata.
        val excluded = linkedSetOf<String>()
        var report: String
        while (true) {
            try {
                report = execute(scan, mode, "display-dependency-updates", DependencyUpdateReport.IGNORED_VERSIONS, excluded)
                break
            } catch (failure: VersionRetrievalFailure) {
                if (!excluded.add(failure.artifact)) throw failure
            }
        }
        val updates = DependencyUpdateReport.parse(report).filter { (dependency, latest) -> MavenVersionSemantics.allows(mode, dependency.version, latest) }
        if (excluded.isEmpty()) return updates
        val repositoryIds = scan.repositories(plugins = false)
        return updates + scan.inputs[MavenArtifactKind.DEPENDENCY]
            .filter { "${it.groupId}:${it.artifactId}" in excluded }
            .mapNotNull { dependency -> MavenRepositoryMetadata.latest(project.localRepositoryPath, dependency, mode, repositoryIds)?.let { dependency to it } }
    }

    internal suspend fun effectiveRepositoryIds(manager: MavenProjectsManager, project: MavenProject): Set<String> =
        withMavenCheckSession(manager, project) { embedder ->
            Scan(manager, project, embedder, MavenScanInputs(emptyList(), emptySet(), emptyMap()),
                readAction { manager.explicitProfiles.clone() }, filtered = false).repositories(plugins = false)
        }

    /** Effective repository information stays local to this fresh embedder and source snapshot. */
    private class Scan(
        val manager: MavenProjectsManager, val project: MavenProject, val embedder: MavenEmbedderWrapper,
        val inputs: MavenScanInputs, val profiles: org.jetbrains.idea.maven.model.MavenExplicitProfiles,
        val filtered: Boolean
    ) {
        private var pom: org.jdom.Element? = null
        private val repositories = mutableMapOf<Boolean, Set<String>>()

        suspend fun repositories(plugins: Boolean): Set<String> {
            repositories[plugins]?.let { return it }
            val model = pom ?: CheckPerformance.measure(CheckPerformance.Stage.MAVEN_MODEL) {
                val text = embedder.evaluateEffectivePom(project.file.toNioPath().toFile(), profiles.enabledProfiles, profiles.disabledProfiles)
                    ?: error("Maven could not determine effective repositories for ${project.path}")
                JDOMUtil.load(text)
            }.also { pom = it }
            val declared = MavenRepositoryMetadata.repositories(model, plugins).filter { it.releasesPolicy?.isEnabled != false }
            // Apply current mirrors through Maven, never by matching IDs independently.
            return embedder.resolveRepositories(declared).filter { it.releasesPolicy?.isEnabled != false }
                .map { it.id }.toSet().also { repositories[plugins] = it }
        }
    }

    private suspend fun expireMetadata(scan: Scan) {
        // One settings reload and one lazy effective model serve all categories and fallback paths.
        val settings = MavenSettingsCache(scan.manager.project)
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_SETTINGS) { settings.reloadAsync() }
        val repository = settings.getEffectiveUserLocalRepo()
        for (kind in scan.inputs.kinds) {
            val dependencies = scan.inputs[kind]
            val ids = scan.repositories(plugins = kind == MavenArtifactKind.PLUGIN)
            CheckPerformance.measure(CheckPerformance.Stage.MAVEN_METADATA_EXPIRATION, dependencies.size) {
                MavenRepositoryMetadata.expireUpdates(repository, dependencies, ids)
            }
        }
    }

    private suspend fun declared(manager: MavenProjectsManager, project: MavenProject) =
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_DECLARATIONS) {
            readAction {
                val file = PsiManager.getInstance(manager.project).findFile(project.file) ?: return@readAction emptyList()
                val model = MavenDomUtil.getMavenDomProjectModel(file) ?: return@readAction emptyList()
                val analysis = MavenDependencyAnalysis(model, project, emptyMap())
                PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull(analysis::coordinate)
                    .filter { it.version.isNotBlank() }.distinct()
            }
        }

    private suspend fun execute(scan: Scan, mode: UpdateMode, goal: String, ignoredVersions: String,
                                excluded: Set<String> = emptySet()): String {
        val project = scan.project
        val output = Files.createTempFile("maven-version-checker-", ".txt")
        try {
            val properties = Properties().apply {
                // executeGoal replaces the request properties, so preserve Maven config -D values.
                putAll(scan.inputs.properties)
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
                if (goal == "display-dependency-updates") MavenDependencyFilters.apply(this, if (scan.filtered) scan.inputs[MavenArtifactKind.DEPENDENCY] else null, excluded)
            }
            val id = project.mavenId
            // Restrict an aggregator scan to this POM, keeping each module's repository context separate.
            val request = MavenGoalExecutionRequest(
                project.file.toNioPath().toFile(), scan.profiles,
                listOf("${id.groupId}:${id.artifactId}"), properties
            )
            val stage = when (goal) {
                "display-dependency-updates" -> CheckPerformance.Stage.MAVEN_DEPENDENCY_GOAL
                "display-plugin-updates" -> CheckPerformance.Stage.MAVEN_PLUGIN_GOAL
                else -> CheckPerformance.Stage.MAVEN_PARENT_GOAL
            }
            val results = CheckPerformance.measure(stage) {
                withMavenProgress { reporter ->
                    scan.embedder.executeGoal(
                        listOf(request),
                        "org.codehaus.mojo:versions-maven-plugin:2.21.0:$goal",
                        reporter, MavenLogEventHandler
                    )
                }
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
