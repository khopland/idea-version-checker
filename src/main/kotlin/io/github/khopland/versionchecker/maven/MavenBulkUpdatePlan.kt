package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlTag

internal data class MavenVersionEdit(
    val pointer: SmartPsiElementPointer<XmlTag>, override val expected: String,
    override val latest: String, override val location: String
) : VersionEdit {
    override val element: XmlTag? get() = pointer.element
    override fun isValid() = element?.value?.trimmedText == expected
    override fun preview(): VersionEditPreview? = element?.let {
        VersionEditPreview.capture(this, it.value.textRange, com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(latest))
    }
    override fun apply() { element!!.value.setText(latest) }
}

internal object MavenBulkUpdatePlan {
    fun create(files: Map<PsiFile, MavenDependencyAnalysis>,
               usageFiles: Collection<PsiFile> = files.keys, isCurrent: () -> Boolean = { true }): BulkUpdatePlan {
        val problems = files.flatMap { (file, analysis) ->
            val declarations = analysis.problems(file).map(analysis::bulkProblem)
            val coordinates = declarations.mapTo(hashSetOf()) { it.coordinate }
            declarations + analysis.propertyProblems().filter { it.coordinate !in coordinates }.map {
                it.copy(target = null, notice = it.notice ?:
                    "${it.anchor.localName}: inherited property consumers need review (${it.coordinate.groupId}:${it.coordinate.artifactId})")
            }
        }
        val skipped = mutableListOf<String>()
        val candidates = problems.filter { problem ->
            if (problem.target == null || problem.latest == null) {
                skipped += problem.notice ?: "${problem.coordinate.groupId}:${problem.coordinate.artifactId}: version is managed outside this declaration"
                false
            } else true
        }.groupBy { key(it.target!!) }
        val propertyEligibility by lazy { MavenPropertyEligibility(usageFiles) }
        val changes = mutableListOf<VersionEdit>()
        for (group in candidates.values) {
            val target = group.first().target!!
            val blockers = mutableListOf<String>()
            blockers += propertyEligibility.blockers(target, group.map {
                MavenPropertyEligibility.Consumer(it.anchor, it.anchor.containingFile)
            })
            val replacement = group.map { it.latest }.distinct().singleOrNull()
            if (blockers.isEmpty() && replacement != null)
                blockers += files.values.first().bulkPropertyScopeBlockers(target, files, replacement)
            val prepared = MavenPreparedUpdate.prepare(target, group.map {
                MavenPreparedUpdate.Consumer(it.coordinate, it.latest, it.notice)
            }, blockers)
            skipped += prepared.blockingReasons
            prepared.edit?.let { changes += it }
        }
        return BulkUpdatePlan(changes, skipped.distinct(), isCurrent)
    }

    private fun key(tag: XmlTag): Pair<String, Int> = tag.containingFile.virtualFile.path to tag.textOffset
}
