package io.github.khopland.versionchecker.maven

import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenDistributionsCache
import java.nio.file.Path

/** Maven's input policy, shared by saving and captured fingerprints. Missing paths remain inputs. */
internal class MavenInputInventory private constructor(
    val layout: Layout,
    private val settings: Map<Path?, Set<Path>>,
    private val ancestors: Map<Path, Set<Path>>
) {
    data class Distribution(val home: Path, val version: String?)
    data class Layout(val userSettings: Path, val userHome: String, val poms: Set<String>,
                      val distributions: Map<Path, Distribution>)

    data class ProjectInputs(val distribution: Distribution, val paths: Set<Path>)
    fun projectInputs(project: MavenProject): ProjectInputs {
        val directory = project.file.toNioPath().parent
        return ProjectInputs(layout.distributions.getValue(directory), projectPaths(directory))
    }

    /** Legacy synchronous readers retain per-home memoization and only visit queried modules. */
    class ReadPass(private val manager: MavenProjectsManager) {
        private val userSettings by lazy { MavenSettingsInputs.userSettings(manager) }
        private val settings = mutableMapOf<Path?, Set<Path>>()
        private fun settings(home: Path? = null) = settings.getOrPut(home) { MavenSettingsInputs.files(userSettings, home) }
        val sharedPaths: Set<Path> get() = settings()
        fun projectInputs(project: MavenProject): ProjectInputs {
            val distribution = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file)
            return ProjectInputs(Distribution(distribution.mavenHome, distribution.version),
                settings(distribution.mavenHome) + ancestorPaths(project.file.toNioPath().parent) { com.intellij.openapi.progress.ProgressManager.checkCanceled() })
        }
    }

    val sharedPaths: Set<Path> get() = settings.getValue(null)
    fun projectPaths(directory: Path): Set<Path> =
        settings.getValue(layout.distributions.getValue(directory).home) + ancestors.getValue(directory)
    val externalPaths: Set<Path> = settings.values.flatten().toSet() + ancestors.values.flatten()
    fun resolutionInputPaths(): Set<String> = layout.poms + externalPaths.map { it.toString() }

    companion object {
        /** Read IDE state under a read action; no filesystem access occurs here. */
        fun layout(manager: MavenProjectsManager, selected: Collection<MavenProject> = manager.nonIgnoredProjects): Layout =
            Layout(MavenSettingsInputs.userSettings(manager), System.getProperty("user.home"),
                manager.nonIgnoredProjects.mapTo(linkedSetOf()) { it.path }, selected.associate { project ->
                    val distribution = MavenDistributionsCache.getInstance(manager.project).getMavenDistribution(project.file)
                    project.file.toNioPath().parent to Distribution(distribution.mavenHome, distribution.version)
                })

        private fun ancestorPaths(directory: Path, checkCancellation: () -> Unit): Set<Path> {
            val paths = linkedSetOf<Path>()
            var ancestor: Path? = directory
            while (ancestor != null) {
                checkCancellation()
                for (name in listOf("maven.config", "jvm.config", "extensions.xml", "wrapper/maven-wrapper.properties"))
                    paths.add(ancestor.resolve(".mvn").resolve(name).toAbsolutePath().normalize())
                ancestor = ancestor.parent
            }
            return paths
        }

        /** Security relocation requires disk reads; callers capturing inputs run this on IO. */
        fun discover(layout: Layout, checkCancellation: () -> Unit = { com.intellij.openapi.progress.ProgressManager.checkCanceled() }): MavenInputInventory {
            val settings = (listOf(null) + layout.distributions.values.map { it.home }.distinct()).associateWith { home ->
                checkCancellation()
                MavenSettingsInputs.files(layout.userSettings, home, checkCancellation)
            }
            val ancestors = layout.distributions.keys.associateWith { directory ->
                ancestorPaths(directory, checkCancellation)
            }
            return MavenInputInventory(layout, settings, ancestors)
        }
    }
}
