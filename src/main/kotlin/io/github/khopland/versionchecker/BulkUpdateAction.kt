package io.github.khopland.versionchecker

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

abstract class BulkUpdateAction(private val mode: UpdateMode,
                                private val scope: MavenUpdateScope = MavenUpdateScope.WHOLE_PROJECT,
                                private val artifactKind: MavenArtifactKind = MavenArtifactKind.DEPENDENCY) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let {
            val manager = MavenProjectsManager.getInstance(it)
            !manager.generalSettings.isWorkOffline && selectMavenProjects(manager, scope, currentMavenPom(event)).isNotEmpty()
        } == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        project.service<BulkUpdateService>().preview(mode, scope, artifactKind, currentMavenPom(event))
    }
}
class PatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH)
class MinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR)
class MajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR)
class CurrentPomPatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, MavenUpdateScope.CURRENT_POM)
class CurrentPomMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, MavenUpdateScope.CURRENT_POM)
class CurrentPomMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, MavenUpdateScope.CURRENT_POM)
class PluginPatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, artifactKind = MavenArtifactKind.PLUGIN)
class PluginMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, artifactKind = MavenArtifactKind.PLUGIN)
class PluginMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, artifactKind = MavenArtifactKind.PLUGIN)
class CurrentPomPluginPatchUpdateAction : BulkUpdateAction(UpdateMode.PATCH, MavenUpdateScope.CURRENT_POM, MavenArtifactKind.PLUGIN)
class CurrentPomPluginMinorUpdateAction : BulkUpdateAction(UpdateMode.MINOR, MavenUpdateScope.CURRENT_POM, MavenArtifactKind.PLUGIN)
class CurrentPomPluginMajorUpdateAction : BulkUpdateAction(UpdateMode.MAJOR, MavenUpdateScope.CURRENT_POM, MavenArtifactKind.PLUGIN)
