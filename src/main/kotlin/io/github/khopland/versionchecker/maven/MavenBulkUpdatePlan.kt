package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlTag

internal data class MavenVersionEdit(
    val pointer: SmartPsiElementPointer<XmlTag>, override val expected: String,
    override val latest: String, override val location: String,
    private val verificationConsumers: List<MavenVerificationConsumer> = emptyList()
) : VersionEdit {
    override val buildSystemId = "maven"
    override val element: XmlTag? get() = pointer.element
    override val mavenVerificationTargets get() = element?.let {
        captureMavenVerificationTargets(it.project, verificationConsumers, latest)
    }.orEmpty()
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
            (declarations + analysis.propertyProblems().filter { it.coordinate !in coordinates }.map {
                it.copy(target = null, notice = it.notice ?:
                    "${it.anchor.localName}: inherited property consumers need review (${it.coordinate.groupId}:${it.coordinate.artifactId})")
            }).map { it to analysis }
        }
        val skipped = mutableListOf<String>()
        val review = mutableListOf<VersionReviewItem>()
        val candidates = problems.filter { (problem, _) ->
            if (problem.target == null || problem.latest == null) {
                skipped += problem.notice ?: "${problem.coordinate.groupId}:${problem.coordinate.artifactId}: version is managed outside this declaration"
                review += VersionReviewItem(problem.notice ?: "Version is managed outside this declaration", VersionReviewSource.capture(problem.anchor), "${problem.coordinate.groupId}:${problem.coordinate.artifactId}", buildContext = "Maven")
                false
            } else true
        }.groupBy { key(it.first.target!!) }
        val propertyEligibility by lazy { MavenPropertyEligibility(usageFiles) }
        val changes = mutableListOf<VersionEdit>()
        for (group in candidates.values) {
            val target = group.first().first.target!!
            val blockers = mutableListOf<String>()
            blockers += propertyEligibility.blockers(target, group.map { (problem, _) ->
                MavenPropertyEligibility.Consumer(problem.anchor, problem.anchor.containingFile)
            })
            val replacement = group.map { it.first.latest }.distinct().singleOrNull()
            if (blockers.isEmpty() && replacement != null)
                blockers += files.values.first().bulkPropertyScopeBlockers(target, files, replacement)
            val prepared = MavenPreparedUpdate.prepare(target, group.map { (problem, _) ->
                MavenPreparedUpdate.Consumer(problem.coordinate, problem.latest, problem.notice)
            }, blockers, verificationConsumers = group.flatMap { (problem, analysis) ->
                analysis.verificationConsumers(target, problem.coordinate)
            }.distinct())
            skipped += prepared.blockingReasons
            review += prepared.blockingReasons.map { VersionReviewItem(it, VersionReviewSource.capture(target), target.localName, target.value.trimmedText, prepared.replacement, prepared.affectedCoordinates.map { "${it.groupId}:${it.artifactId}" }, "Maven") }
            prepared.edit?.let { edit ->
                val metadata = VersionReviewItem("The check selected this newer version. All listed consumers share this edit; availability does not guarantee compatibility.", VersionReviewSource.capture(target), target.localName, edit.expected, edit.latest, prepared.affectedCoordinates.map { "${it.groupId}:${it.artifactId}" }, "Maven", checkedVersion = edit.expected)
                changes += object : VersionEdit by edit { override val review = metadata }
            }
        }
        return BulkUpdatePlan(changes, skipped.distinct(), isCurrent, reviewItems = review.distinct())
    }

    private fun key(tag: XmlTag): Pair<String, Int> = tag.containingFile.virtualFile.path to tag.textOffset
}
