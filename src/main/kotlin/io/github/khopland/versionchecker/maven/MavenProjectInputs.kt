package io.github.khopland.versionchecker.maven

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.khopland.versionchecker.CheckPerformance
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.BuildFingerprint
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

/** A read-pass input view, never retained across repository work or preview application. */
internal class MavenProjectInputs(manager: MavenProjectsManager) {
    val projects: List<MavenProject> = manager.nonIgnoredProjects.toList()
    private val byPath = projects.associateBy { it.path }
    private val diskStamps = mutableMapOf<Path, String>()
    private val fingerprints = mutableMapOf<Path, BuildFingerprint>()
    private val shared by lazy {
        CheckPerformance.measure(CheckPerformance.Stage.MAVEN_PROJECT_INPUTS, projects.size) {
            val documents = FileDocumentManager.getInstance()
            // Include unselected modules: shared-property ownership depends on their saved/unsaved POMs.
            val files = projects.associate { pom ->
                pom.path to "${pom.file.modificationStamp}:${documents.getCachedDocument(pom.file)?.takeIf { documents.isDocumentUnsaved(it) }?.modificationStamp}"
            }.toMutableMap()
            val settings = manager.generalSettings.userSettingsFile.takeIf(String::isNotBlank)
                ?: Path.of(System.getProperty("user.home"), ".m2", "settings.xml").toString()
            files[settings] = diskStamp(Path.of(settings))
            val policy = manager.project.service<VersionCheckerSettings>().state.deprecatedDependencies.hashCode()
            BuildFingerprint(files, "${manager.modificationTracker.modificationCount}:${manager.generalSettings.hashCode()}:${manager.explicitProfiles.hashCode()}:$policy")
        }
    }

    fun find(path: String): MavenProject? = byPath[path]

    fun fingerprint(project: MavenProject): BuildFingerprint {
        val directory = project.file.toNioPath().parent
        return fingerprints.getOrPut(directory) {
            val files = shared.files.toMutableMap()
            var ancestor: Path? = directory
            while (ancestor != null) {
                for (name in listOf("maven.config", "jvm.config", "wrapper/maven-wrapper.properties")) {
                    val config = ancestor.resolve(".mvn").resolve(name)
                    files[config.toString()] = diskStamp(config)
                }
                ancestor = ancestor.parent
            }
            BuildFingerprint(files, shared.configuration)
        }
    }

    private fun diskStamp(path: Path): String = diskStamps.getOrPut(path) {
        val saved = if (Files.exists(path)) "${Files.getLastModifiedTime(path)}:${Files.size(path)}" else "missing"
        val documents = FileDocumentManager.getInstance()
        val document = LocalFileSystem.getInstance().findFileByNioFile(path.toAbsolutePath())
            ?.let(documents::getCachedDocument)?.takeIf { documents.isDocumentUnsaved(it) }
        "$saved:${document?.modificationStamp}"
    }
}
