package io.github.khopland.versionchecker.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Updates many declarations at once: **Update This File** and **Update All Files**. */
@Service(Service.Level.PROJECT)
class Updater(private val project: Project, private val scope: CoroutineScope) {

    /** Update [files], or every supported build file in the project when [files] is null. */
    fun update(files: List<VirtualFile>?, mode: UpdateKind) {
        scope.launch {
            withBackgroundProgress(project, "Checking ${mode.label} updates") { run(files, mode) }
        }
    }

    private suspend fun run(files: List<VirtualFile>?, mode: UpdateKind) {
        withContext(Dispatchers.EDT) { PsiDocumentManager.getInstance(project).commitAllDocuments() }
        val targets = files ?: buildFiles(project)
        // Every build file already takes part when the whole project updates, so only a file scope needs related declarations.
        val (declarations, related) = readAction {
            val found = targets.mapNotNull { file ->
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
                val provider = DeclarationProvider.forFile(psi) ?: return@mapNotNull null
                val own = provider.collect(psi)
                own to (if (files == null) emptyList() else provider.related(psi, own))
            }
            found.flatMap { it.first } to found.flatMap { it.second }
        }
        val outcomes = VersionService.getInstance().check(declarations + related)
        val fixes = VersionService.fixes(outcomes)
        val changes = declarations.mapNotNull { declaration ->
            val version = fixes[declaration]?.get(mode) ?: return@mapNotNull null
            declaration to Change(declaration.edit!!, declaration.edit.format(version))
        }.distinctBy { it.second.edit.key }
        val applied = withContext(Dispatchers.EDT) {
            Edits.apply(project, changes.map { it.second }, "Update ${mode.label} versions").toSet()
        }
        val failed = declarations.associateWith { outcomes.getValue(it) }.filterValues { it is Outcome.Failed }
        notify(changes.filter { it.second in applied }.map { it.first }, fixes, mode, failed)
    }

    private fun notify(updated: List<Declaration>, fixes: Map<Declaration, Map<UpdateKind, String>>, mode: UpdateKind,
                       failed: Map<Declaration, Outcome>) {
        val title = if (updated.isEmpty()) "No ${mode.label} updates" else
            "Updated ${StringUtil.pluralize("version", updated.size).let { "${updated.size} $it" }} in " +
                updated.map { it.edit!!.file }.distinct().size.let { "$it ${StringUtil.pluralize("file", it)}" }
        val lines = updated.take(LIST_LIMIT).map { "${it.name} ${it.current} → ${fixes[it]?.get(mode)}" } +
            listOfNotNull("…and ${updated.size - LIST_LIMIT} more".takeIf { updated.size > LIST_LIMIT }) +
            failed.entries.take(LIST_LIMIT).map { (declaration, outcome) -> "Could not check ${declaration.name}: ${(outcome as Outcome.Failed).message}" }
        NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification(title, lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) },
                if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        private const val LIST_LIMIT = 15

        fun getInstance(project: Project): Updater = project.service()

        suspend fun buildFiles(project: Project): List<VirtualFile> = smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            DeclarationProvider.EP.extensionList.flatMap { it.fileNames }.distinct()
                .flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                .filter { "/node_modules/" !in it.path }
                .distinct()
        }
    }
}
