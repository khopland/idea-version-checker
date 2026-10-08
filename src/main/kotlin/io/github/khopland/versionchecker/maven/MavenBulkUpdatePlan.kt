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
        val problems = files.flatMap { (file, analysis) -> analysis.problems(file) }
        val skipped = mutableListOf<String>()
        val candidates = problems.filter { problem ->
            if (problem.target == null || problem.latest == null) {
                skipped += problem.notice ?: "${problem.coordinate.groupId}:${problem.coordinate.artifactId}: version is managed outside this declaration"
                false
            } else true
        }.groupBy { key(it.target!!) }
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
                val attributeUsage = usageFiles.any { file ->
                    PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).any { tag ->
                        tag.attributes.any { it.value?.contains(reference) == true }
                    }
                }
                val usages = usageFiles.flatMap { file ->
                    PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).filter {
                        it.subTags.isEmpty() && it.value.trimmedText.contains(reference)
                    }
                }
                val safe = !attributeUsage && usages.isNotEmpty() && usages.all { usage ->
                    usage.localName == "version" && usage.value.trimmedText == reference &&
                        files[usage.containingFile]?.versionPropertyTarget(usage, reference, target.value.trimmedText)?.let(::key) == targetKey &&
                        group.any { it.anchor == usage && it.latest == latest }
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

    private fun key(tag: XmlTag): Pair<String, Int> = tag.containingFile.virtualFile.path to tag.textOffset
}
