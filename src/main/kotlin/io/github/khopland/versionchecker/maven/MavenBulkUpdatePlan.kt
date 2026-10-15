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
            val declarations = analysis.problems(file)
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
        val propertyUsages by lazy {
            MavenPropertyUsages(usageFiles, candidates.values.mapNotNull { group ->
                group.first().target!!.takeIf { it.parentTag?.localName == "properties" }
                    ?.let { "\${${it.localName}}" }
            }.toSet())
        }
        val changes = mutableListOf<VersionEdit>()
        for ((targetKey, group) in candidates) {
            val target = group.first().target!!
            val blockers = mutableListOf<String>()
            if (target.parentTag?.localName == "properties") {
                val reference = "\${${target.localName}}"
                val usages = propertyUsages.leaves[reference].orEmpty()
                val anchors = group.mapTo(hashSetOf()) { it.anchor }
                val safe = reference !in propertyUsages.attributes && usages.isNotEmpty() && usages.all { usage ->
                    usage.localName == "version" && usage.value.trimmedText == reference &&
                        files[usage.containingFile]?.versionPropertyTarget(usage, reference, target.value.trimmedText)?.let(::key) == targetKey &&
                        usage in anchors
                }
                if (!safe) {
                    blockers += "${target.localName}: shared, inherited or other uses need review"
                }
            }
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
