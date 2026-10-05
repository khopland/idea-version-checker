package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

enum class MavenArtifactKind(val label: String) {
    DEPENDENCY("dependencies"), PLUGIN("build plugins")
}

enum class MavenUpdateScope(val label: String) {
    CURRENT_POM("Current POM"), WHOLE_PROJECT("Whole Project")
}

internal fun currentMavenPom(event: AnActionEvent): VirtualFile? =
    event.getData(CommonDataKeys.EDITOR)?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        ?: event.getData(CommonDataKeys.PSI_FILE)?.virtualFile
        ?: event.getData(CommonDataKeys.VIRTUAL_FILE)

internal fun selectMavenProjects(manager: MavenProjectsManager, scope: MavenUpdateScope, currentPom: VirtualFile?): List<MavenProject> =
    when (scope) {
        MavenUpdateScope.WHOLE_PROJECT -> manager.nonIgnoredProjects.toList()
        MavenUpdateScope.CURRENT_POM -> listOfNotNull(currentPom?.let(manager::findProject))
            .filter { !manager.isIgnored(it) }
    }
