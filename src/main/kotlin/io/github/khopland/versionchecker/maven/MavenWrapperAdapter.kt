package io.github.khopland.versionchecker.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.*
import io.github.khopland.versionchecker.core.*
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * `.mvn/wrapper/maven-wrapper.properties`. The distribution is the ordinary Maven artifact
 * `org.apache.maven:apache-maven`, so it resolves through the root POM's repositories and mirrors
 * with the same stable-version, update-mode and minimum-release-age rules as dependencies.
 */
internal class MavenWrapperAdapter : WrapperBuildSystemAdapter("maven-wrapper", "Maven wrapper", "maven",
    ArtifactId("maven", "$GROUP:$ARTIFACT")) {
    override fun isOffline(project: Project) = MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline

    override fun wrapperFiles(project: Project): List<VirtualFile> {
        // The wrapper sits next to the top-level POM; modules rarely have their own `.mvn`.
        val manager = MavenProjectsManager.getInstance(project)
        return manager.projects.filter { !manager.isIgnored(it) && it.directoryFile.isValid }
            .mapNotNull { it.directoryFile.findFileByRelativePath(WRAPPER) }
            .filter { it.isValid && !it.isDirectory }.distinctBy { it.path }
    }

    override fun version(url: String): String? = distribution.find(url)?.takeIf { it.groupValues[1] == it.groupValues[2] }?.groupValues?.get(1)
    override fun review(distribution: WrapperDistribution): String? = null
    override fun newer(current: String, latest: String) = current != latest
    override fun kind(current: String, latest: String) = MavenVersionSemantics.between(current, latest)

    override suspend fun lookup(project: Project, file: String, current: String, mode: UpdateMode): WrapperLookup {
        val manager = MavenProjectsManager.getInstance(project)
        val maven = BuildSystemAdapter.find("maven") as? MavenBuildSystemAdapter
            ?: return WrapperLookup(failure = "Maven support is unavailable")
        val (pom, snapshot) = readAction {
            val root = manager.projects.firstOrNull { !manager.isIgnored(it) && it.directoryFile.isValid &&
                it.directoryFile.findFileByRelativePath(WRAPPER)?.path == file }
            root to root?.let { maven.snapshot(project, it.file) }
        }
        if (pom == null || snapshot == null) return WrapperLookup(failure = "The Maven wrapper's root project is not imported")
        val declaration = VersionDeclaration(DeclarationId(snapshot.sourceFile, "${MavenArtifactKind.DEPENDENCY.name}:maven-wrapper"),
            ArtifactId("maven", "$GROUP:$ARTIFACT"), current, current)
        val delta = MavenBatchedLookup.check(manager, pom, snapshot.copy(declarations = listOf(declaration)), mode, null)
            ?: return WrapperLookup(failure = "Maven repository metadata is unavailable for this project")
        delta.failures.firstOrNull()?.let { return WrapperLookup(failure = "Could not check $GROUP:$ARTIFACT: ${it.message}") }
        return WrapperLookup(delta.updates[declaration.coordinate()], validUntilNanos = delta.validUntil, checkedAtNanos = delta.checkedAtNanos)
    }

    companion object {
        const val GROUP = "org.apache.maven"
        private const val WRAPPER = ".mvn/wrapper/maven-wrapper.properties"
        const val ARTIFACT = "apache-maven"
        private val distribution = Regex("/org/apache/maven/apache-maven/([^/]+)/apache-maven-([^/]+?)-bin\\.(?:zip|tar\\.gz)$")
    }
}

class MavenWrapperVersionInspection : WrapperVersionInspection("maven-wrapper")
