package io.github.khopland.versionchecker.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.khopland.versionchecker.*
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectChanges
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProjectsTree

/** Only an actual native resolution event supplies verification evidence, never a reload request. */
@Service(Service.Level.PROJECT)
internal class MavenUpdateVerificationService(private val project: Project) : Disposable {
    private data class Resolution(val generation: Long, val inputs: Map<String, String>,
                                  val versions: Map<String, Set<String>>)
    private val manager = MavenProjectsManager.getInstance(project)
    private val resolutions = linkedMapOf<String, Resolution>()
    private var generation = 0L
    private var disposed = false

    init {
        manager.addProjectsTreeListener(object : MavenProjectsTree.Listener {
            override fun projectResolved(projectWithChanges: Pair<MavenProject, MavenProjectChanges>,
                                         nativeMavenProject: org.jetbrains.idea.maven.server.NativeMavenProjectHolder?) {
                ApplicationManager.getApplication().runReadAction {
                    synchronized(this@MavenUpdateVerificationService) {
                        if (disposed || project.isDisposed) return@runReadAction
                        val pom = projectWithChanges.first
                        val versions = if (pom.hasReadingErrors() || pom.hasUnresolvedArtifacts()) emptyMap() else
                            pom.dependencies.groupBy { "${it.groupId}:${it.artifactId}" }
                                .mapValues { (_, artifacts) -> artifacts.map { it.version }.toSet() }
                        while (resolutions.size >= 128 && pom.path !in resolutions) resolutions.remove(resolutions.keys.first())
                        resolutions[pom.path] = Resolution(++generation, inputStamps(), versions)
                    }
                }
            }
        }, this)
    }

    @Synchronized fun generation(): Long = generation

    /** Called explicitly by a notification action after the user has allowed native Maven reload. */
    fun verify(changes: AppliedVersionChanges, publish: (String) -> Unit) {
        if (project.isDisposed) return
        val message = evaluate(changes)
        publish(message)
        Messages.showInfoMessage(project, message, "Maven Effective Versions")
    }

    internal fun evaluate(changes: AppliedVersionChanges): String =
        ApplicationManager.getApplication().runReadAction<String> {
            synchronized(this) {
                if (disposed) return@runReadAction "Unknown: this Maven verification session has expired."
                val documents = FileDocumentManager.getInstance()
                val capturesCurrent = !changes.captureUnavailable && changes.omittedTargetFiles == 0 && changes.files.isNotEmpty() &&
                    changes.files.all { captured ->
                        val file = LocalFileSystem.getInstance().findFileByPath(captured.path)
                        file != null && documents.getDocument(file)?.text == captured.after
                    }
                val inputs = inputStamps()
                val results = changes.mavenVerificationTargets.map { target ->
                    val resolution = resolutions[target.pomPath]
                    val result = compareEffectiveVersion(target, resolution?.generation,
                        capturesCurrent && (resolution == null || resolution.inputs == inputs),
                        resolution?.versions?.get("${target.groupId}:${target.artifactId}"))
                    "${result.status.name.lowercase().replaceFirstChar { it.uppercase() }}: ${target.groupId}:${target.artifactId} (${target.pomPath}) — ${result.detail}"
                }
                (listOf("Maven effective-version verification (imported dependency model; does not run tests):") + results).joinToString("\n")
            }
        }

    /** Changes to any imported POM invalidate evidence, including parent properties and undo. */
    private fun inputStamps(): Map<String, String> {
        val documents = FileDocumentManager.getInstance()
        return manager.nonIgnoredProjects.associate { pom ->
            pom.path to "${pom.file.modificationStamp}:${documents.getCachedDocument(pom.file)?.modificationStamp}"
        } + mapOf("<Maven settings>" to "${manager.generalSettings.hashCode()}:${manager.explicitProfiles.hashCode()}")
    }

    @Synchronized override fun dispose() { disposed = true; resolutions.clear() }
}
