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
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.util.progress.reportProgressScope
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenDomUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException

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
        // One unsortable artifact aborts the whole goal, and Maven may not name it. Exclude every artifact whose
        // cached version list Maven cannot sort, rerun, and resolve the excluded artifacts from fresh metadata.
        val excluded = linkedSetOf<String>()
        var report: String
        while (true) {
            try {
                report = execute(scan, mode, "display-dependency-updates", DependencyUpdateReport.IGNORED_VERSIONS, excluded)
                break
            } catch (failure: VersionRetrievalFailure) {
                if (!excluded.addAll(listOfNotNull(failure.artifact) + unorderable(scan))) throw failure
            }
        }
        val updates = DependencyUpdateReport.parse(report).filter { (dependency, latest) -> MavenVersionSemantics.allows(mode, dependency.version, latest) }
        if (excluded.isEmpty()) return updates
        val repositoryIds = scan.repositories(plugins = false)
        val skipped = scan.inputs[MavenArtifactKind.DEPENDENCY].filter { "${it.groupId}:${it.artifactId}" in excluded }
        val fresh = freshVersions(scan, skipped)
        return updates + skipped.mapNotNull { dependency ->
            val versions = fresh[MavenMetadataKey(dependency.groupId, dependency.artifactId)]
            val latest = if (versions != null) MavenRepositoryMetadata.latest(versions, dependency.version, mode)
                else MavenRepositoryMetadata.latest(project.localRepositoryPath, dependency, mode, repositoryIds)
            latest?.let { dependency to it }
        }
    }

    private suspend fun unorderable(scan: Scan): List<String> {
        val repositoryIds = scan.repositories(plugins = false)
        return scan.inputs[MavenArtifactKind.DEPENDENCY]
            .filter { MavenRepositoryMetadata.unorderable(scan.project.localRepositoryPath, it, repositoryIds) }
            .map { "${it.groupId}:${it.artifactId}" }
    }

    /** The goal skipped these artifacts, so read their metadata without Maven's version sort instead of trusting the cache. */
    private suspend fun freshVersions(scan: Scan, dependencies: List<DependencyVersion>): Map<MavenMetadataKey, List<String>> {
        val keys = dependencies.map { MavenMetadataKey(it.groupId, it.artifactId) }.distinct()
        val repository = scan.localRepository ?: return emptyMap()
        if (keys.isEmpty()) return emptyMap()
        // Only the scan's own embedder runs the helper, so no session identity is needed.
        val context = MavenResolutionContext("", repository, scan.inputs.properties, scan.profiles,
            nativeSupported = false, sessionConfiguration = "")
        val threads = scan.manager.project.service<VersionCheckerSettings>().state.mavenMetadataThreads
        return try {
            keys.chunked(128).flatMap { chunk ->
                MavenNativeMetadata.load(scan.manager, scan.project, context, chunk, threads, scan.embedder)
                    .mapNotNull { (key, result) -> result.getOrNull()?.let { key to it.versions } }
            }.toMap()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Logger.getInstance(MavenVersionLookup::class.java).debug("Could not refresh metadata for excluded artifacts", failure)
            emptyMap()
        }
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
        var localRepository: Path? = null

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
        scan.localRepository = repository.toAbsolutePath().normalize()
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
            val details = results.flatMap { it.problems }.mapNotNull { it.description }.joinToString("\n")
            val message = "Maven could not check ${project.path}" + details.takeIf { it.isNotBlank() }?.let { ":\n$it" }.orEmpty()
            if (results.isEmpty() || results.any { !it.success }) {
                VersionRetrievalFailure.from(details, message)?.let { throw it }
                throw IOException(message)
            }
            // IDEA can report the aborted goal as successful with only the sort failure as a problem.
            // Its missing output file must not be read as "no updates".
            VersionRetrievalFailure.from(details, message)?.let { throw it }
            // The goal can remove its output file when a successful module has nothing to report.
            // Empty aggregators and up-to-date modules must not abort a reactor-wide update scan.
            val report = try { Files.readString(output) } catch (_: NoSuchFileException) { "" }
            return report
        } finally {
            Files.deleteIfExists(output)
        }
    }
}

/** The resolver threw while ordering versions. Maven names the artifact only when it reports the mojo failure. */
internal class VersionRetrievalFailure(val artifact: String?, message: String) : IOException(message) {
    companion object {
        private val pattern = Regex("""Unable to retrieve versions for ([^:\s]+):([^:\s]+):""")
        private const val UNSORTABLE = "Comparison method violates its general contract"
        fun artifact(details: String): String? = pattern.find(details)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
        fun from(details: String, message: String): VersionRetrievalFailure? {
            val artifact = artifact(details)
            return if (artifact != null || UNSORTABLE in details) VersionRetrievalFailure(artifact, message) else null
        }
    }
}
