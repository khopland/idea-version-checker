package io.github.khopland.versionchecker.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The result of [Updater.check]. [files] is the build file each declaration was found in, and [scanned] every build file read.
 * [vulnerabilities] leaves out the versions that a suppress comment for vulnerabilities keeps, but has those that one for
 * outdated versions keeps, which are not in [declarations]. [vulnerabilityError] says why they could not be checked.
 */
class Checked(
    val declarations: List<Declaration>,
    val files: Map<Declaration, VirtualFile>,
    val scanned: List<VirtualFile>,
    val outcomes: Map<Declaration, Outcome>,
    val fixes: Map<Declaration, Map<UpdateKind, String>>,
    val vulnerabilities: Map<Declaration, Vulnerable> = emptyMap(),
    val vulnerabilityError: String? = null,
)

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
        val checked = check(project, files)
        val changes = checked.declarations.mapNotNull { declaration ->
            val version = checked.fixes[declaration]?.get(mode) ?: return@mapNotNull null
            declaration to Change(declaration.edit!!, declaration.edit.format(version))
        }.distinctBy { it.second.edit.key }
        val applied = withContext(Dispatchers.EDT) {
            Edits.apply(project, changes.map { it.second }, "Update ${mode.label} versions").toSet()
        }
        val failed = checked.declarations.associateWith { checked.outcomes.getValue(it) }.filterValues { it is Outcome.Failed }
        val updated = changes.filter { it.second in applied }.map { it.first }
        // Against the versions the others have now, so packages that were updated together do not conflict.
        val written = updated.associateWith { checked.fixes.getValue(it).getValue(mode) }
        val conflicts = updated.flatMap { Conflicts.of(it, written.getValue(it), checked.outcomes) { other -> written[other] ?: other.checked } }
            .map { it.text }.distinct()
        notify(updated, checked.fixes, mode, failed, conflicts)
    }

    private fun notify(updated: List<Declaration>, fixes: Map<Declaration, Map<UpdateKind, String>>, mode: UpdateKind,
                       failed: Map<Declaration, Outcome>, conflicts: List<String>) {
        val title = if (updated.isEmpty()) "No ${mode.label} updates" else
            "Updated ${StringUtil.pluralize("version", updated.size).let { "${updated.size} $it" }} in " +
                updated.map { it.edit!!.file }.distinct().size.let { "$it ${StringUtil.pluralize("file", it)}" }
        val lines = updated.take(LIST_LIMIT).map { "${it.name} ${it.current} → ${fixes[it]?.get(mode)}" } +
            listOfNotNull("…and ${updated.size - LIST_LIMIT} more".takeIf { updated.size > LIST_LIMIT }) +
            conflicts.take(LIST_LIMIT).map { "Conflict: $it" } +
            failed.entries.take(LIST_LIMIT).map { (declaration, outcome) -> "Could not check ${declaration.name}: ${(outcome as Outcome.Failed).message}" }
        NotificationGroupManager.getInstance().getNotificationGroup("Version Checker")
            .createNotification(title, lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) },
                if (failed.isEmpty() && conflicts.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        private val LOG = logger<Updater>()
        private const val LIST_LIMIT = 15

        fun getInstance(project: Project): Updater = project.service()

        /**
         * Checks [files], or every build file in the project when [files] is null. Versions kept by a suppress comment
         * are left out of [Checked.declarations], but still take part in the agreement on a shared version.
         * With [vulnerabilities], the declarations are also checked for known vulnerabilities, each file in its own
         * Nexus IQ application.
         */
        suspend fun check(project: Project, files: List<VirtualFile>?, vulnerabilities: Boolean = false): Checked = coroutineScope {
            withContext(Dispatchers.EDT) { PsiDocumentManager.getInstance(project).commitAllDocuments() }
            val targets = files ?: buildFiles(project)
            // Every build file already takes part when the whole project is checked, so only a file scope needs related declarations.
            val found = readAction {
                targets.mapNotNull { file ->
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
                    val provider = DeclarationProvider.forFile(psi) ?: return@mapNotNull null
                    val own = provider.collect(psi)
                    Found(file, own, if (files == null) emptyList() else provider.related(psi, own), Suppression.suppressed(psi, own, OutdatedVersionInspection()),
                        if (vulnerabilities) Suppression.suppressed(psi, own, VulnerableDependencyInspection()) else emptySet(),
                        if (vulnerabilities) VulnerabilityService.application(provider, psi) else null)
                }
            }
            // Files of one Nexus IQ application go together, so versions they share agree on a safe version.
            val vulnerable = if (vulnerabilities) found.groupBy { it.application }.map { (application, group) ->
                async { runCatching { VulnerabilityService.getInstance().check(group.flatMap { it.declarations + it.related }, project, application) } }
            } else emptyList()
            val outcomes = VersionService.getInstance().check(found.flatMap { it.declarations + it.related })
            val kept = found.flatMap { f -> f.declarations.filter { it !in f.suppressed }.map { it to f.file } }
            // One application that fails, such as one Nexus IQ does not have, does not hide the others or the outdated versions.
            val results = vulnerable.map { it.await() }
            val error = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.also {
                if (it is CancellationException) throw it
                LOG.info("Could not check for vulnerabilities", it)
            }
            val unsuppressed = found.flatMapTo(HashSet()) { it.declarations - it.vulnerable }
            Checked(kept.map { it.first }, found.flatMap { f -> f.declarations.map { it to f.file } }.toMap(), found.map { it.file }, outcomes, VersionService.fixes(outcomes),
                results.flatMap { it.getOrNull().orEmpty().entries }.filter { it.key in unsuppressed }.associate { it.toPair() },
                error?.let { it.message ?: it.javaClass.simpleName })
        }

        /** [suppressed] are kept by a suppress comment for outdated versions, [vulnerable] for vulnerabilities. */
        private class Found(val file: VirtualFile, val declarations: List<Declaration>, val related: List<Declaration>, val suppressed: Set<Declaration>,
                            val vulnerable: Set<Declaration>, val application: ApplicationHint?)

        suspend fun buildFiles(project: Project): List<VirtualFile> = smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            DeclarationProvider.EP.extensionList.flatMap { it.buildFiles(project, scope) }
                .filter { "/node_modules/" !in it.path }
                .distinct()
        }
    }
}
