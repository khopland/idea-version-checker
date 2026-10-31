package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*

import org.jetbrains.idea.maven.server.MavenEmbedderWrapper
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
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

/** Let Maven handle settings.xml, settings-security.xml, mirrors, proxies and transports. */
internal object MavenVersionLookup {
    suspend fun check(manager: MavenProjectsManager, project: MavenProject,
                      mode: UpdateMode = UpdateMode.MAJOR,
                      artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY): Map<DependencyVersion, String> =
        runScan(manager, project, mode, setOf(artifactKind), null)

    suspend fun checkAll(manager: MavenProjectsManager, project: MavenProject, mode: UpdateMode,
                         kinds: Set<MavenArtifactKind>, coordinates: List<DependencyVersion>? = null): Map<DependencyVersion, String> =
        runScan(manager, project, mode, kinds, coordinates)

    /** Keep one model session per POM; failed categories cannot discard successful earlier categories. */
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
                    reporter.itemStep("Maven ${kind.name.lowercase()} versions") { withholdRecent(scan, mode, check(scan, mode, kind)) }
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
        val fresh = try {
            freshHistories(scan, skipped.map { MavenMetadataKey(it.groupId, it.artifactId) }.distinct())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Logger.getInstance(MavenVersionLookup::class.java).debug("Could not refresh metadata for excluded artifacts", failure)
            emptyMap()
        }
        return updates + skipped.mapNotNull { dependency ->
            val versions = fresh[MavenMetadataKey(dependency.groupId, dependency.artifactId)]?.versions
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

    /** Read histories through the bundled helper, bypassing Maven's version sort and the cached metadata. */
    private suspend fun freshHistories(scan: Scan, keys: List<MavenMetadataKey>): Map<MavenMetadataKey, MavenMetadataValue> {
        val repository = scan.localRepository ?: return emptyMap()
        if (keys.isEmpty()) return emptyMap()
        return keys.chunked(128).flatMap { chunk -> load(scan, repository, chunk)
            .mapNotNull { (key, result) -> result.getOrNull()?.let { key to it } } }.toMap()
    }

    private suspend fun load(scan: Scan, repository: Path, keys: List<MavenMetadataKey>): Map<MavenMetadataKey, Result<MavenMetadataValue>> {
        val context = MavenResolutionContext("", repository, scan.inputs.properties, scan.profiles,
            nativeSupported = false)
        return MavenNativeMetadata.load(scan.manager, scan.project, context, keys)
    }

    /** The Versions goals cannot see release dates, so re-select their answers when a minimum release age is set. */
    private suspend fun withholdRecent(scan: Scan, mode: UpdateMode, updates: Map<DependencyVersion, String>): Map<DependencyVersion, String> {
        val age = MavenReleaseAge.of(scan.manager.project.service<VersionCheckerSettings>().state.minimumReleaseAgeHours)
        if (age == null || updates.isEmpty()) return updates
        fun key(dependency: DependencyVersion) = MavenMetadataKey(dependency.groupId, dependency.artifactId,
            dependency.artifactKind == MavenArtifactKind.PLUGIN)
        try {
            val repository = scan.localRepository ?: throw IOException("Maven local repository is unknown")
            val histories = freshHistories(scan, updates.keys.map(::key).distinct())
            val kept = linkedMapOf<DependencyVersion, String>()
            val candidates = linkedMapOf<DependencyVersion, List<String>>()
            for ((dependency, latest) in updates) {
                val history = histories[key(dependency)]
                    ?: throw IOException("Missing version history for ${dependency.groupId}:${dependency.artifactId}")
                if (age.settled(history)) { kept[dependency] = latest; continue }
                val ceiling = org.jetbrains.idea.maven.dom.MavenVersionComparable(latest)
                candidates[dependency] = history.versionIndex.eligible(dependency.version, mode)
                    .filter { org.jetbrains.idea.maven.dom.MavenVersionComparable(it) <= ceiling }
            }
            val mavenVersion = MavenDistributionsCache.getInstance(scan.manager.project).getMavenDistribution(scan.project.file).version
            val selection = MavenCandidateWalk.select(candidates, age, candidates.keys, mavenVersion) { load(scan, repository, it) }
            selection.failures.values.firstOrNull()?.let { throw it }
            return kept + selection.selected
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw IOException("Could not check release dates for the minimum release age: ${failure.message ?: failure.javaClass.simpleName}", failure)
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
            if (MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file).version?.startsWith("4.") == true) {
                val repository = localRepository ?: MavenSettingsCache(manager.project).let { settings ->
                    settings.reloadAsync()
                    settings.getEffectiveUserLocalRepo()
                }
                val context = MavenResolutionContext("", repository, inputs.properties, profiles, false)
                repositories.putAll(MavenNativeMetadata.repositoryIds(manager, project, context))
                return repositories.getValue(plugins)
            }
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
        val directory = Files.createTempDirectory("maven-version-checker-")
        val id = project.mavenId
        val output = directory.resolve("${id.groupId}~${id.artifactId}.txt")
        try {
            val properties = Properties().apply {
                // Pass the same Maven config properties as the native model capture.
                putAll(scan.inputs.properties)
                // Maven 4 also executes an aggregator's children. Give each project its own
                // report so a child cannot replace (or delete) the selected POM's answer.
                setProperty("versions.outputFile", directory.resolve("${'$'}{project.groupId}~${'$'}{project.artifactId}.txt").toString())
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
            val stage = when (goal) {
                "display-dependency-updates" -> CheckPerformance.Stage.MAVEN_DEPENDENCY_GOAL
                "display-plugin-updates" -> CheckPerformance.Stage.MAVEN_PLUGIN_GOAL
                else -> CheckPerformance.Stage.MAVEN_PARENT_GOAL
            }
            val result = CheckPerformance.measure(stage) {
                MavenGoalProcess.run(scan.manager, project, scan.profiles,
                    properties.stringPropertyNames().associateWith(properties::getProperty),
                    "org.codehaus.mojo:versions-maven-plugin:2.21.0:$goal", checkNotNull(scan.localRepository))
            }
            val details = result.text
            val message = "Maven could not check ${project.path}" + details.takeIf { it.isNotBlank() }?.let { ":\n$it" }.orEmpty()
            if (result.exitCode != 0) {
                VersionRetrievalFailure.from(details, message)?.let { throw it }
                throw IOException(message)
            }
            // A goal can report a sort failure without a failing exit code.
            // Its missing output file must not be read as "no updates".
            VersionRetrievalFailure.from(details, message)?.let { throw it }
            if (!Files.exists(output) && !details.contains("BUILD SUCCESS"))
                throw MavenGoalConfigurationFailure("Maven did not confirm that the Versions goal completed. " +
                    "Check .mvn/maven.config for quiet output, log redirection or informational options, then retry.")
            // The goal can remove its output file when a successful module has nothing to report.
            // Empty aggregators and up-to-date modules must not abort a reactor-wide update scan.
            val report = try { Files.readString(output) } catch (_: NoSuchFileException) { "" }
            return report
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
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
