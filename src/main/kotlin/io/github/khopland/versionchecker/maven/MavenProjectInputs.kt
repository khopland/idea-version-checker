package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.service
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildFingerprint
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A read-pass input view, never retained across repository work or preview application. */
internal class MavenProjectInputs(private val manager: MavenProjectsManager, private val external: External? = null) {
    internal data class External(val stamps: Map<Path, String>, val settings: Map<Path?, Set<Path>>)
    private data class Distribution(val home: Path, val version: String?)
    private data class Layout(val settings: Path, val userHome: String, val distributions: Map<Path, Distribution>)

    companion object {
        fun savedDiskStamp(path: Path): String =
            if (Files.exists(path)) "${Files.getLastModifiedTime(path)}:${Files.size(path)}" else "missing"

        private fun layout(manager: MavenProjectsManager): Layout = Layout(MavenSettingsInputs.userSettings(manager),
            System.getProperty("user.home"), manager.nonIgnoredProjects.associate { project ->
                val distribution = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file)
                project.file.toNioPath().parent to Distribution(distribution.mavenHome, distribution.version)
            })

        /** Only immutable paths cross the disk phase; current IDE state is overlaid afterward. */
        suspend fun <T> capture(manager: MavenProjectsManager, readStamp: (Path) -> String = ::savedDiskStamp,
                                action: (MavenProjectInputs) -> T): T = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            repeat(3) {
                val layout = readAction { layout(manager) }
                val settings = (listOf(null) + layout.distributions.values.map { it.home }.distinct()).associateWith { home ->
                    MavenSettingsInputs.files(layout.settings, home) { context.ensureActive() }
                }
                val paths = settings.values.flatten().toMutableSet()
                for (directory in layout.distributions.keys) {
                    var ancestor: Path? = directory
                    while (ancestor != null) {
                        context.ensureActive()
                        for (name in listOf("maven.config", "jvm.config", "extensions.xml", "wrapper/maven-wrapper.properties"))
                            paths.add(ancestor.resolve(".mvn").resolve(name).toAbsolutePath().normalize())
                        ancestor = ancestor.parent
                    }
                }
                val external = External(paths.associateWith { path -> context.ensureActive(); readStamp(path) }, settings)
                context.ensureActive()
                val captured = readAction {
                    if (layout != layout(manager)) null else Captured(action(MavenProjectInputs(manager, external)))
                }
                if (captured != null) return@withContext captured.value
            }
            error("Maven input paths changed during capture. Run the check again.")
        }
        private data class Captured<T>(val value: T)
    }
    val projects: List<MavenProject> = manager.nonIgnoredProjects.toList()
    private val byPath = projects.associateBy { it.path }
    private val diskStamps = mutableMapOf<Path, String>()
    private val fingerprints = mutableMapOf<Path, BuildFingerprint>()
    private val inspectionFingerprints = mutableMapOf<Path, BuildFingerprint>()
    private val settingsFiles = mutableMapOf<Path, Set<Path>>()
    private val userSettings by lazy { MavenSettingsInputs.userSettings(manager) }
    private val configuration by lazy {
        val policy = manager.project.service<VersionCheckerSettings>().state.deprecatedDependencies.hashCode()
        val fastScope = manager.project.service<VersionCheckerSettings>().state.mavenFastEditorChecks
        val persistent = manager.project.service<VersionCheckerSettings>().state.mavenPersistentMetadata
        "${manager.generalSettings.hashCode()}:${manager.explicitProfiles.hashCode()}:$policy:$fastScope:$persistent"
    }
    private val shared by lazy {
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_PROJECT_INPUTS, projects.size) {
            val documents = FileDocumentManager.getInstance()
            // Include unselected modules: shared-property ownership depends on their saved/unsaved POMs.
            val files = projects.associate { pom ->
                pom.path to "${pom.file.modificationStamp}:${documents.getCachedDocument(pom.file)?.takeIf { documents.isDocumentUnsaved(it) }?.modificationStamp}"
            }.toMutableMap()
            settingsPaths().forEach { files[it.toString()] = diskStamp(it) }
            BuildFingerprint(files, "${manager.modificationTracker.modificationCount}:$configuration")
        }
    }

    fun find(path: String): MavenProject? = byPath[path]

    fun fingerprint(project: MavenProject): BuildFingerprint {
        val directory = project.file.toNioPath().parent
        return fingerprints.getOrPut(directory) {
            val files = shared.files.toMutableMap()
            val distribution = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file)
            settingsFiles.getOrPut(distribution.mavenHome) { settingsPaths(distribution.mavenHome) }
                .forEach { files[it.toString()] = diskStamp(it) }
            var ancestor: Path? = directory
            while (ancestor != null) {
                for (name in listOf("maven.config", "jvm.config", "extensions.xml", "wrapper/maven-wrapper.properties")) {
                    val config = ancestor.resolve(".mvn").resolve(name)
                    files[config.toString()] = diskStamp(config)
                }
                ancestor = ancestor.parent
            }
            BuildFingerprint(files, "${shared.configuration}:${distribution.mavenHome}:${distribution.version}")
        }
    }

    /** POM edits trigger a new check but need not hide warnings for unchanged coordinates. */
    fun inspectionFingerprint(project: MavenProject): BuildFingerprint =
        inspectionFingerprints.getOrPut(project.file.toNioPath().parent) {
            BuildFingerprint(fingerprint(project).files.filterKeys { it !in byPath },
                "$configuration:${project.activatedProfilesIds.hashCode()}")
        }

    private fun settingsPaths(home: Path? = null): Set<Path> =
        external?.settings?.getValue(home) ?: MavenSettingsInputs.files(userSettings, home)

    private fun diskStamp(path: Path): String = diskStamps.getOrPut(path) {
        val saved = external?.stamps?.getValue(path.toAbsolutePath().normalize()) ?: savedDiskStamp(path)
        val documents = FileDocumentManager.getInstance()
        val document = LocalFileSystem.getInstance().findFileByNioFile(path.toAbsolutePath())
            ?.let(documents::getCachedDocument)?.takeIf { documents.isDocumentUnsaved(it) }
        "$saved:${document?.modificationStamp}"
    }
}
