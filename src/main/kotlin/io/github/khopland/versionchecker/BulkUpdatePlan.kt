package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/** Platform edit bridge: the coordinator never needs XML/JSON/TOML types. */
internal interface VersionEdit {
    val element: PsiElement?
    val expected: String
    val latest: String
    val location: String
    /** Null inherits the containing plan's guidance; combined plans assign it per edit. */
    val followUp: List<String>? get() = null
    /** Captured text edits can be validated and committed once per file, including combined plans. */
    val documentChange: DocumentVersionChange? get() = null
    fun isValid(): Boolean
    fun apply()
}

internal data class BulkUpdatePlan(
    val changes: List<VersionEdit>,
    val skipped: List<String>,
    private val isCurrent: () -> Boolean = { true },
    val followUp: List<String> = emptyList()
) {
    companion object {
        fun combine(plans: List<BulkUpdatePlan>): BulkUpdatePlan {
            val changes = plans.flatMap { plan ->
                plan.changes.map { edit ->
                    object : VersionEdit by edit {
                        override val followUp = edit.followUp ?: plan.followUp
                    }
                }
            }
            return BulkUpdatePlan(
                changes = changes,
                skipped = plans.flatMap { it.skipped }.distinct(),
                isCurrent = { plans.all { it.isCurrent() } },
                followUp = plans.flatMap { it.followUp }.distinct()
            )
        }
    }

    /** Shared properties and catalog versions are already grouped into indivisible edits. */
    fun select(indices: Set<Int>): BulkUpdatePlan {
        require(indices.all { it in changes.indices })
        val selectedChanges = changes.filterIndexed { index, _ -> index in indices }
        val selectedFollowUp = selectedChanges.flatMap { it.followUp ?: followUp }.distinct()
        return copy(changes = selectedChanges, followUp = selectedFollowUp)
    }

    fun guardedBy(valid: () -> Boolean) = copy(isCurrent = { isCurrent() && valid() })

    /** Validate every edit and the discovery snapshot before applying one undoable command. */
    fun apply(
        project: Project,
        interaction: CheckPerformance.Interaction? = CheckPerformance.start(CheckPerformance.Stage.FIX_INVOKED)
    ): Boolean =
        CheckPerformance.locally(interaction) {
            var applied = false
            WriteCommandAction.runWriteCommandAction(project, "Update versions", null, Runnable {
                if (!isCurrent()) return@Runnable
                val textChanges = changes.mapNotNull { it.documentChange }
                val prepared = DocumentVersionChange.prepare(textChanges) ?: return@Runnable
                val elementChanges = changes.filter { it.documentChange == null }
                if (elementChanges.any { !it.isValid() }) return@Runnable
                prepared.forEach { it.apply() }
                elementChanges.forEach { it.apply() }
                applied = true
                CheckPerformance.record(
                    CheckPerformance.Stage.EDITOR_TEXT_CHANGED,
                    interaction?.started ?: System.nanoTime(),
                    changes.size,
                    interaction
                )
            })
            applied
        }
}
