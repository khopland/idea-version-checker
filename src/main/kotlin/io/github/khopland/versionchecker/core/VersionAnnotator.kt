package io.github.khopland.versionchecker.core

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Shows outdated versions. Lookups run on the annotator's background thread, not in the highlighting pass. */
class VersionAnnotator : ExternalAnnotator<VersionAnnotator.Info, VersionAnnotator.Result>() {
    /**
     * [suppressed] and [movedSuppressed] are kept by a suppress comment. They still take part in the agreement on a shared version.
     * With [checkVulnerabilities], the updates say when the new version is still vulnerable. In an [editor], versions from
     * slow repositories show later than the others; Inspect Code waits for all of them.
     */
    class Info(val declarations: List<Declaration>, val related: List<Declaration>,
               val suppressed: Set<Declaration> = emptySet(), val movedSuppressed: Set<Declaration> = emptySet(),
               val checkVulnerabilities: Boolean = false, val project: Project? = null, val application: ApplicationHint? = null,
               val editor: Boolean = false)
    class Result(val info: Info, val outcomes: Map<Declaration, Outcome>, val vulnerabilities: Map<Declaration, Vulnerable> = emptyMap()) {
        val declarations get() = info.declarations
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collectInformation(file, editor = true)

    override fun collectInformation(file: PsiFile): Info? = collectInformation(file, editor = false)

    private fun collectInformation(file: PsiFile, editor: Boolean): Info? {
        val provider = DeclarationProvider.forFile(file) ?: return null
        val snapshot = FileSnapshot.of(file, provider)
        if (snapshot.declarations.isEmpty()) return null
        val vulnerabilities = VersionCheckerSettings.getInstance().checkVulnerabilities && VulnerableDependencyInspection.enabled(file)
        return Info(snapshot.declarations, snapshot.related, snapshot.suppressed(OutdatedVersionInspection()),
            snapshot.suppressed(MovedDependencyInspection()), vulnerabilities, file.project,
            if (vulnerabilities) snapshot.application else null, editor)
    }

    override fun doAnnotate(info: Info): Result = runBlockingCancellable {
        val declarations = info.declarations + info.related
        // The lookups VulnerabilityAnnotator shares, so this waits for osv.dev or Nexus IQ but sends nothing twice. That annotator reports a failure.
        val vulnerabilities = if (info.checkVulnerabilities) async {
            Highlights.attempt("check for vulnerabilities") { VulnerabilityService.getInstance().check(declarations, info.project, info.application) }.first
        } else null
        val service = VersionService.getInstance()
        val outcomes = service.check(declarations, patience = service.patience.takeIf { info.editor })
        Result(info, outcomes, vulnerabilities?.await().orEmpty())
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val outcomes = result.outcomes
        highlightAgainWhenChecked(file, outcomes)
        val moves = Highlights(file, MovedDependencyInspection.SHORT_NAME, "Moved dependency", HighlightDisplayLevel.WEAK_WARNING)
        if (moves.enabled) for (declaration in result.declarations) {
            val moved = (outcomes[declaration] as? Outcome.Updates)?.moved ?: continue
            if (declaration !in result.info.movedSuppressed) moves.annotate(holder, moves.level.severity, movedMessage(declaration, moved), declaration.range)
        }
        val outdated = Highlights(file, OutdatedVersionInspection.SHORT_NAME, "Outdated dependency version", HighlightDisplayLevel.WARNING)
        if (!outdated.enabled) return
        val fixes = VersionService.fixes(outcomes)
        val checked = result.declarations.filter { it !in result.info.suppressed }
        val failures = checked.filter { outcomes[it] is Outcome.Failed }.groupBy { (outcomes.getValue(it) as Outcome.Failed).message }
        // A repository that fails for many versions, for example without VPN, gets one warning for the file.
        for ((message, failed) in failures) {
            if (failed.size == 1) {
                val declaration = failed.single()
                if (declaration.range.endOffset <= file.textLength)
                    holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Could not check ${declaration.name}: $message").range(declaration.range).create()
            } else {
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Could not check ${failed.size} versions: $message").fileLevel().create()
            }
        }
        for (declaration in checked) {
            val outcome = outcomes[declaration] as? Outcome.Updates ?: continue
            val conflicts = Conflicts.offered(declaration, outcomes)
            val message = message(declaration, outcome, fixes[declaration].orEmpty(), conflicts) ?: continue
            val newest = outcome.targets.major
            outdated.annotate(holder, outdated.level.severity, message, declaration.range,
                VulnerabilityAnnotator.updateFixes(declaration, fixes[declaration].orEmpty().values, result.vulnerabilities, conflicts) + UpdateFileFix() +
                    listOfNotNull(if (declaration.lookup.hasReleaseNotes && newest != null) OpenReleaseNotesFix(declaration.name, declaration.lookup, newest) else null))
        }
    }

    override fun getPairedBatchInspectionShortName() = OutdatedVersionInspection.SHORT_NAME

    companion object {
        /** Highlights [file] again when more of the versions that are still [Outcome.Pending] in [outcomes] are checked. */
        fun highlightAgainWhenChecked(file: PsiFile, outcomes: Map<Declaration, Outcome>) {
            val pending = outcomes.filterValues { it is Outcome.Pending }.keys.toList()
            val target = file.virtualFile ?: return
            if (pending.isEmpty()) return
            val project = file.project
            VersionService.getInstance().whenMore(target, pending) {
                withContext(Dispatchers.EDT) {
                    if (project.isDisposed || !target.isValid) return@withContext
                    PsiManager.getInstance(project).findFile(target)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "More versions checked") }
                }
            }
        }

        fun movedMessage(declaration: Declaration, moved: Moved): String =
            "${declaration.name} has moved to ${moved.to}" + (moved.newest?.let { " (newest $it)" } ?: "") + (moved.message?.let { ": $it" } ?: "")

        private const val LISTED = 3

        /** [conflicts] are those of the offered versions, from [Conflicts.offered]. */
        fun message(declaration: Declaration, outcome: Outcome.Updates, fixes: Map<UpdateKind, String>,
                    conflicts: Map<String, List<Conflict>> = emptyMap()): String? {
            val versions = UpdateKind.entries.mapNotNull { outcome.targets[it] }.distinct()
            val deprecated = outcome.deprecated?.let { "${declaration.current} is deprecated: $it" }
            if (versions.isEmpty()) return deprecated?.let { "${declaration.name} $it" }
            val updates = versions.joinToString(" · ") { "$it (${Versions.kind(declaration.current, it).label})" }
            val reason = when {
                declaration.edit == null -> declaration.note
                fixes.isEmpty() -> "the version is shared, and its users need different versions"
                else -> null
            }
            val conflicting = conflicts.flatMap { (version, found) ->
                found.take(LISTED).map { it.text(declaration, version) } +
                    listOfNotNull("$version has ${found.size - LISTED} more conflicts".takeIf { found.size > LISTED })
            }
            return (listOfNotNull("${declaration.name} ${declaration.current} → $updates", deprecated, reason) + conflicting).joinToString(". ")
        }
    }
}

/** Gives the annotator an on/off switch and a severity in Settings → Editor → Inspections, and runs it in Inspect Code. */
class OutdatedVersionInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName() = SHORT_NAME

    companion object {
        const val SHORT_NAME = "OutdatedDependencyVersion"
    }
}

/** Moves have their own switch and severity, because the old coordinates often stay supported, as Jackson 2 does. */
class MovedDependencyInspection : LocalInspectionTool() {
    override fun getShortName() = SHORT_NAME

    companion object {
        const val SHORT_NAME = "MovedDependency"
    }
}
