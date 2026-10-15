package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.khopland.versionchecker.core.*

/** Physical edit ownership is separate from each native/catalog consumer's identity. */
internal data class GradleEditTarget(val file: String, val range: TextRange)

/** Provider eligibility only; leases and fresh input validation still authorize application. */
internal data class GradlePreparedUpdate(
    val target: GradleEditTarget?,
    val affectedDeclarations: Set<DeclarationId>,
    val replacement: String?,
    val blockingReasons: List<String>,
    val edit: GradleVersionEdit?,
    val followUp: List<String>,
    val consumers: List<GradleDeclaration>,
    val candidates: List<UpdateCandidate>,
    val notice: UpdateNotice?,
    val location: String,
    val reportSkipped: Boolean
) {
    companion object {
        private const val FOLLOW_UP = "Reload the Gradle project in IntelliJ after updating versions. Update dependency locks yourself if the build uses locking."

        /** Parse and index once per file/pass, including consumers with no available update. */
        fun prepare(file: PsiFile, report: UpdateReport): List<GradlePreparedUpdate> {
            val original = file.text
            val path = file.virtualFile.path
            val candidatesById = report.candidates.groupBy { it.declaration.id }
            val noticesById = report.notices.withIndex().groupBy { it.value.declaration.id }
            return GradleDeclarations.parse(path, original).groupBy { it.range }.map { (range, consumers) ->
                val ids = consumers.map { it.declaration.id }.toSet()
                val updates = consumers.map { candidatesById[it.declaration.id].orEmpty() }
                val candidates = updates.mapNotNull { it.singleOrNull() }
                val notice = ids.flatMap { noticesById[it].orEmpty() }.minByOrNull { it.index }?.value
                val reasons = consumers.mapNotNull { it.reason }.toMutableList()
                if (notice != null) reasons += notice.message
                report.failure?.let { reasons += it }
                if (range == null) reasons += "Gradle version has no physical edit target"
                if (updates.any { it.size != 1 } || candidates.map { it.version }.distinct().size != 1)
                    reasons += "Shared version has conflicting or unchanged consumers"
                if (consumers.zip(updates).any { (consumer, values) -> values.any { candidate ->
                        candidate.declaration != consumer.declaration ||
                            !GradleVersions.newer(consumer.declaration.baseline, candidate.version) ||
                            candidate.replacementSelector != candidate.version
                    } }) reasons += "Gradle candidate does not match a newer fixed version of the physical declaration"
                val location = "$path: " + consumers.joinToString {
                    "${it.declaration.artifact.namespace}:${it.declaration.artifact.name} (${it.declaration.id.location})"
                }
                val replacement = candidates.map { it.version }.distinct().singleOrNull()
                val edit = if (reasons.isEmpty() && range != null && replacement != null)
                    GradleVersionEdit(file, range, replacement, location, original) else null
                GradlePreparedUpdate(range?.let { GradleEditTarget(path, it) }, ids, replacement,
                    reasons.distinct(), edit, if (edit == null) emptyList() else listOf(FOLLOW_UP),
                    consumers, candidates, notice, location,
                    consumers.any { it.reason != null } || updates.any { it.isNotEmpty() })
            }
        }
    }
}
