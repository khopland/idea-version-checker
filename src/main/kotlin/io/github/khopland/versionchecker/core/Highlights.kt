package io.github.khopland.versionchecker.core

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.EmptyIntentionAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException

/**
 * What the inspection [shortName] says for [file]: whether it is on and at which level, and annotations that carry its
 * suppress actions. [name] labels the empty action of an annotation without fixes.
 */
internal class Highlights(private val file: PsiFile, shortName: String, private val name: String, default: HighlightDisplayLevel) {
    private val key = HighlightDisplayKey.find(shortName)
    private val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile

    val enabled = key == null || profile.isToolEnabled(key, file)
    val level: HighlightDisplayLevel = key?.let { profile.getErrorLevel(it, file) } ?: default

    /** Nothing for a [range] the file no longer has, after an edit since the check started. */
    fun annotate(holder: AnnotationHolder, severity: HighlightSeverity, message: String, range: TextRange, fixes: List<IntentionAction> = emptyList()) {
        if (range.endOffset > file.textLength) return
        var builder = holder.newAnnotation(severity, message).range(range)
        // The key adds the inspection's suppress actions to each fix's submenu. An annotation without fixes has no
        // Alt+Enter menu, so an empty action carries them.
        if (key == null) fixes.forEach { builder = builder.withFix(it) }
        else fixes.ifEmpty { listOf(EmptyIntentionAction(name)) }.forEach { builder = builder.newFix(it).key(key).registerFix() }
        builder.create()
    }

    companion object {
        private val LOG = logger<Highlights>()

        /** The result of [block], or the reason it failed, which is logged as "Could not [what]". */
        suspend fun <T> attempt(what: String, block: suspend () -> T): Pair<T?, String?> = try {
            block() to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Could not $what", e)
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
