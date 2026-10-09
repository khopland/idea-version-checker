package io.github.khopland.versionchecker.maven

import io.github.khopland.versionchecker.*
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

internal data class MavenVersionEdit(
    val pointer: SmartPsiElementPointer<XmlTag>, override val expected: String,
    override val latest: String, override val location: String
) : VersionEdit {
    override val element: XmlTag? get() = pointer.element
    override fun isValid() = element?.value?.trimmedText == expected
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
            PropertyUsages(usageFiles, candidates.values.mapNotNull { group ->
                group.first().target!!.takeIf { it.parentTag?.localName == "properties" }
                    ?.let { "\${${it.localName}}" }
            }.toSet())
        }
        val changes = mutableListOf<VersionEdit>()
        for ((targetKey, group) in candidates) {
            val target = group.first().target!!
            val versions = group.map { it.latest!! }.distinct()
            if (versions.size != 1) {
                skipped += "${target.localName}: artifacts using this property require different updates"
                continue
            }
            val latest = versions.single()
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
                    skipped += "${target.localName}: shared, inherited or other uses need review"
                    continue
                }
            }
            changes += MavenVersionEdit(SmartPointerManager.createPointer(target), target.value.trimmedText,
                latest, "${target.containingFile.virtualFile.path}: ${target.localName} (" +
                    group.joinToString { "${it.coordinate.groupId}:${it.coordinate.artifactId}" } + ")")
        }
        return BulkUpdatePlan(changes, skipped.distinct(), isCurrent)
    }

    /** Used only during this preparation pass; never retained with cached reports or plans. */
    private class PropertyUsages(files: Collection<PsiFile>, references: Set<String>) {
        val attributes = hashSetOf<String>()
        val leaves = hashMapOf<String, MutableList<XmlTag>>()
        private val maximumLength = references.maxOfOrNull { it.length } ?: 0

        init {
            for (file in files) {
                for (tag in PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)) {
                    tag.attributes.forEach { attribute ->
                        attribute.value?.let { attributes += matchingReferences(it, references) }
                    }
                    if (tag.subTags.isEmpty()) {
                        matchingReferences(tag.value.trimmedText, references).forEach { reference ->
                            leaves.getOrPut(reference) { mutableListOf() } += tag
                        }
                    }
                }
            }
        }

        private fun matchingReferences(text: String, references: Set<String>): Set<String> {
            val matches = hashSetOf<String>()
            var start = text.indexOf("\${")
            var end = -1
            while (start >= 0) {
                if (end < start + 2) end = text.indexOf('}', start + 2)
                if (end < 0) break
                if (end + 1 - start <= maximumLength) {
                    val reference = text.substring(start, end + 1)
                    if (reference in references) matches += reference
                }
                // Inspect nested markers too: ${outer-${shared}} contains ${shared}.
                start = text.indexOf("\${", start + 2)
            }
            return matches
        }
    }

    private fun key(tag: XmlTag): Pair<String, Int> = tag.containingFile.virtualFile.path to tag.textOffset
}
