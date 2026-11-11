package io.github.khopland.versionchecker.core

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.async

/** Shows outdated versions. Lookups run on the annotator's background thread, not in the highlighting pass. */
class VersionAnnotator : ExternalAnnotator<VersionAnnotator.Info, VersionAnnotator.Result>() {
    /**
     * [suppressed] and [movedSuppressed] are kept by a suppress comment. They still take part in the agreement on a shared version.
     * With [checkVulnerabilities], the updates say when the new version is still vulnerable.
     */
    class Info(val declarations: List<Declaration>, val related: List<Declaration>,
               val suppressed: Set<Declaration> = emptySet(), val movedSuppressed: Set<Declaration> = emptySet(),
               val checkVulnerabilities: Boolean = false, val project: Project? = null, val application: ApplicationHint? = null)
    class Result(val info: Info, val outcomes: Map<Declaration, Outcome>, val vulnerabilities: Map<Declaration, Vulnerable> = emptyMap()) {
        val declarations get() = info.declarations
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        val provider = DeclarationProvider.forFile(file) ?: return null
        val declarations = provider.collect(file)
        if (declarations.isEmpty()) return null
        val vulnerabilities = VersionCheckerSettings.getInstance().checkVulnerabilities && VulnerableDependencyInspection.enabled(file)
        return Info(declarations, provider.related(file, declarations), Suppression.suppressed(file, declarations, OutdatedVersionInspection()),
            Suppression.suppressed(file, declarations, MovedDependencyInspection()), vulnerabilities, file.project,
            if (vulnerabilities) VulnerabilityService.application(provider, file) else null)
    }

    override fun doAnnotate(info: Info): Result = runBlockingCancellable {
        val declarations = info.declarations + info.related
        // The lookups VulnerabilityAnnotator shares, so this waits for osv.dev or Nexus IQ but sends nothing twice. That annotator reports a failure.
        val vulnerabilities = if (info.checkVulnerabilities) async {
            Highlights.attempt("check for vulnerabilities") { VulnerabilityService.getInstance().check(declarations, info.project, info.application) }.first
        } else null
        val outcomes = VersionService.getInstance().check(declarations)
        Result(info, outcomes, vulnerabilities?.await().orEmpty())
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val outcomes = result.outcomes
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
            val message = message(declaration, outcome, fixes[declaration].orEmpty()) ?: continue
            val newest = outcome.targets.major
            outdated.annotate(holder, outdated.level.severity, message, declaration.range,
                VulnerabilityAnnotator.updateFixes(declaration, fixes[declaration].orEmpty().values, result.vulnerabilities) + UpdateFileFix() +
                    listOfNotNull(if (declaration.lookup.hasReleaseNotes && newest != null) OpenReleaseNotesFix(declaration.name, declaration.lookup, newest) else null))
        }
    }

    override fun getPairedBatchInspectionShortName() = OutdatedVersionInspection.SHORT_NAME

    companion object {
        fun movedMessage(declaration: Declaration, moved: Moved): String =
            "${declaration.name} has moved to ${moved.to}" + (moved.newest?.let { " (newest $it)" } ?: "") + (moved.message?.let { ": $it" } ?: "")

        fun message(declaration: Declaration, outcome: Outcome.Updates, fixes: Map<UpdateKind, String>): String? {
            val versions = UpdateKind.entries.mapNotNull { outcome.targets[it] }.distinct()
            val deprecated = outcome.deprecated?.let { "${declaration.current} is deprecated: $it" }
            if (versions.isEmpty()) return deprecated?.let { "${declaration.name} $it" }
            val updates = versions.joinToString(" · ") { "$it (${Versions.kind(declaration.current, it).label})" }
            val reason = when {
                declaration.edit == null -> declaration.note
                fixes.isEmpty() -> "the version is shared, and its users need different versions"
                else -> null
            }
            return listOfNotNull("${declaration.name} ${declaration.current} → $updates", deprecated, reason).joinToString(". ")
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
