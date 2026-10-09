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
    fun isValid(): Boolean
    fun apply()
}

internal data class BulkUpdatePlan(
    val changes: List<VersionEdit>, val skipped: List<String>,
    private val isCurrent: () -> Boolean = { true },
    val followUp: List<String> = emptyList()
) {
    companion object {
        fun combine(plans: List<BulkUpdatePlan>) = BulkUpdatePlan(
            plans.flatMap { plan -> plan.changes.map { edit ->
                object : VersionEdit by edit {
                    override val followUp = edit.followUp ?: plan.followUp
                }
            } }, plans.flatMap { it.skipped }.distinct(),
            isCurrent = { plans.all { it.isCurrent() } },
            followUp = plans.flatMap { it.followUp }.distinct()
        )
    }

    /** Shared properties and catalog versions are already grouped into indivisible edits. */
    fun select(indices: Set<Int>): BulkUpdatePlan {
        require(indices.all { it in changes.indices })
        val selected = changes.filterIndexed { index, _ -> index in indices }
        return copy(changes = selected, followUp = selected.flatMap { it.followUp ?: followUp }.distinct())
    }

    fun guardedBy(valid: () -> Boolean) = copy(isCurrent = { isCurrent() && valid() })

    /** Validate every edit and the discovery snapshot before applying one undoable command. */
    fun apply(project: Project): Boolean {
        var applied = false
        WriteCommandAction.runWriteCommandAction(project, "Update versions", null, Runnable {
            if (!isCurrent() || changes.any { !it.isValid() }) return@Runnable
            changes.forEach { it.apply() }
            applied = true
        })
        return applied
    }
}
