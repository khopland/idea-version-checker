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
    fun isValid(): Boolean
    fun apply()
}

internal data class BulkUpdatePlan(
    val changes: List<VersionEdit>, val skipped: List<String>,
    private val isCurrent: () -> Boolean = { true }
) {
    companion object {
        fun combine(plans: List<BulkUpdatePlan>) = BulkUpdatePlan(
            plans.flatMap { it.changes }, plans.flatMap { it.skipped }.distinct(),
            isCurrent = { plans.all { it.isCurrent() } }
        )
    }

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
