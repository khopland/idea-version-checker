package io.github.khopland.versionchecker.core

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.VersionCheckerSettings

/** Shows outdated versions. Lookups run on the annotator's background thread, not in the highlighting pass. */
class VersionAnnotator : ExternalAnnotator<VersionAnnotator.Info, VersionAnnotator.Result>() {
    class Info(val declarations: List<Declaration>, val related: List<Declaration>)
    class Result(val declarations: List<Declaration>, val outcomes: Map<Declaration, Outcome>)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        if (!VersionCheckerSettings.getInstance().enabled) return null
        val provider = DeclarationProvider.forFile(file) ?: return null
        val declarations = provider.collect(file)
        return if (declarations.isEmpty()) null else Info(declarations, provider.related(file, declarations))
    }

    override fun doAnnotate(info: Info): Result =
        Result(info.declarations, runBlockingCancellable { VersionService.getInstance().check(info.declarations + info.related) })

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val outcomes = result.outcomes
        val key = HighlightDisplayKey.find(OutdatedVersionInspection.SHORT_NAME)
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        if (key != null && !profile.isToolEnabled(key, file)) return
        val severity = key?.let { profile.getErrorLevel(it, file) }?.severity ?: HighlightDisplayLevel.WARNING.severity
        val fixes = VersionService.fixes(outcomes)
        val failures = result.declarations.filter { outcomes[it] is Outcome.Failed }.groupBy { (outcomes.getValue(it) as Outcome.Failed).message }
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
        for (declaration in result.declarations) {
            val outcome = outcomes[declaration] ?: continue
            if (declaration.range.endOffset > file.textLength) continue
            when (outcome) {
                is Outcome.Failed -> {}
                is Outcome.Updates -> {
                    val message = message(declaration, outcome, fixes[declaration].orEmpty()) ?: continue
                    var builder = holder.newAnnotation(severity, message).range(declaration.range)
                    val edit = declaration.edit
                    if (edit != null) fixes[declaration].orEmpty().values.distinct().forEach { version ->
                        builder = builder.withFix(UpdateVersionFix(edit, version, Versions.kind(declaration.current, version)))
                    }
                    builder.withFix(UpdateFileFix()).create()
                }
            }
        }
    }

    override fun getPairedBatchInspectionShortName() = OutdatedVersionInspection.SHORT_NAME

    companion object {
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
