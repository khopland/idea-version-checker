package io.github.khopland.versionchecker.core

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.EmptyIntentionAction
import com.intellij.codeInsight.intention.IntentionAction
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
    /** [suppressed] and [movedSuppressed] are kept by a suppress comment. They still take part in the agreement on a shared version. */
    class Info(val declarations: List<Declaration>, val related: List<Declaration>,
               val suppressed: Set<Declaration> = emptySet(), val movedSuppressed: Set<Declaration> = emptySet())
    class Result(val info: Info, val outcomes: Map<Declaration, Outcome>) {
        val declarations get() = info.declarations
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        if (!VersionCheckerSettings.getInstance().enabled) return null
        val provider = DeclarationProvider.forFile(file) ?: return null
        val declarations = provider.collect(file)
        if (declarations.isEmpty()) return null
        return Info(declarations, provider.related(file, declarations), Suppression.suppressed(file, declarations, OutdatedVersionInspection()),
            Suppression.suppressed(file, declarations, MovedDependencyInspection()))
    }

    override fun doAnnotate(info: Info): Result =
        Result(info, runBlockingCancellable { VersionService.getInstance().check(info.declarations + info.related) })

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val outcomes = result.outcomes
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        fun severity(shortName: String, default: HighlightDisplayLevel): HighlightSeverity? {
            val key = HighlightDisplayKey.find(shortName) ?: return default.severity
            return if (profile.isToolEnabled(key, file)) profile.getErrorLevel(key, file).severity else null
        }
        val movedSeverity = severity(MovedDependencyInspection.SHORT_NAME, HighlightDisplayLevel.WEAK_WARNING)
        val movedKey = HighlightDisplayKey.find(MovedDependencyInspection.SHORT_NAME)
        for (declaration in result.declarations) {
            val moved = (outcomes[declaration] as? Outcome.Updates)?.moved ?: continue
            if (movedSeverity != null && declaration !in result.info.movedSuppressed && declaration.range.endOffset <= file.textLength) {
                val builder = holder.newAnnotation(movedSeverity, movedMessage(declaration, moved)).range(declaration.range)
                // An annotation without fixes has no Alt+Enter menu, so an empty action carries the suppress actions.
                (if (movedKey == null) builder else builder.newFix(EmptyIntentionAction("Moved dependency")).key(movedKey).registerFix()).create()
            }
        }
        val severity = severity(OutdatedVersionInspection.SHORT_NAME, HighlightDisplayLevel.WARNING) ?: return
        val key = HighlightDisplayKey.find(OutdatedVersionInspection.SHORT_NAME)
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
            val outcome = outcomes[declaration] ?: continue
            if (declaration.range.endOffset > file.textLength) continue
            when (outcome) {
                is Outcome.Failed -> {}
                is Outcome.Updates -> {
                    val message = message(declaration, outcome, fixes[declaration].orEmpty()) ?: continue
                    var builder = holder.newAnnotation(severity, message).range(declaration.range)
                    // The key adds the inspection's suppress actions to each fix's submenu.
                    fun register(fix: IntentionAction) {
                        builder = if (key == null) builder.withFix(fix) else builder.newFix(fix).key(key).registerFix()
                    }
                    val edit = declaration.edit
                    if (edit != null) fixes[declaration].orEmpty().values.distinct().forEach { version ->
                        register(UpdateVersionFix(edit, version, Versions.kind(declaration.current, version)))
                    }
                    register(UpdateFileFix())
                    builder.create()
                }
            }
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
