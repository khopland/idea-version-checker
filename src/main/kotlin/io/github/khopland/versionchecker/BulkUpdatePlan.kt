package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

internal data class VersionChange(
    val pointer: SmartPsiElementPointer<XmlTag>, val expected: String, val latest: String, val location: String
)

internal data class BulkUpdatePlan(val changes: List<VersionChange>, val skipped: List<String>) {
    /** Validate the entire preview before applying one undoable command. */
    fun apply(project: Project): Boolean {
        var applied = false
        WriteCommandAction.runWriteCommandAction(project, "Update Maven dependency versions", null, Runnable {
            val targets = changes.map { it.pointer.element ?: return@Runnable }
            if (targets.zip(changes).any { (tag, change) -> tag.value.trimmedText != change.expected }) return@Runnable
            targets.zip(changes).forEach { (tag, change) -> tag.value.setText(change.latest) }
            applied = true
        })
        return applied
    }

    companion object {
        fun create(files: Map<PsiFile, MavenDependencyAnalysis>): BulkUpdatePlan {
            val problems = files.flatMap { (file, analysis) -> analysis.problems(file) }
            val skipped = mutableListOf<String>()
            val candidates = problems.filter { problem ->
                if (problem.target == null || problem.latest == null) {
                    skipped += problem.notice ?: "${problem.coordinate.groupId}:${problem.coordinate.artifactId}: version is managed outside this declaration"
                    false
                } else true
            }.groupBy { key(it.target!!) }
            val changes = mutableListOf<VersionChange>()
            for ((targetKey, group) in candidates) {
                val target = group.first().target!!
                val versions = group.map { it.latest!! }.distinct()
                if (versions.size != 1) {
                    skipped += "${target.localName}: dependencies using this property require different updates"
                    continue
                }
                val latest = versions.single()
                if (target.parentTag?.localName == "properties") {
                    val reference = "\${${target.localName}}"
                    val usages = files.keys.flatMap { file ->
                        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).filter {
                            it.subTags.isEmpty() && it.value.trimmedText.contains(reference)
                        }
                    }
                    val safe = usages.isNotEmpty() && usages.all { usage ->
                        usage.localName == "version" && usage.value.trimmedText == reference &&
                            findLocalVersionProperty(usage, reference, target.value.trimmedText)?.let(::key) == targetKey &&
                            group.any { it.anchor == usage && it.latest == latest }
                    }
                    if (!safe) {
                        skipped += "${target.localName}: shared, inherited or non-dependency uses need review"
                        continue
                    }
                }
                changes += VersionChange(SmartPointerManager.createPointer(target), target.value.trimmedText,
                    latest, "${target.containingFile.virtualFile.path}: ${target.localName}")
            }
            return BulkUpdatePlan(changes, skipped.distinct())
        }

        private fun key(tag: XmlTag): Pair<String, Int> = tag.containingFile.virtualFile.path to tag.textOffset
    }
}
