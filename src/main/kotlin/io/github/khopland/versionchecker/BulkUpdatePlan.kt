package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import io.github.khopland.versionchecker.core.CapturedEditInputs
import io.github.khopland.versionchecker.core.EditAuthorization
import io.github.khopland.versionchecker.core.EditInputCapture

/** Platform edit bridge: the coordinator never needs XML/JSON/TOML types. */
internal interface VersionEdit {
    val element: PsiElement?
    val expected: String
    val latest: String
    val location: String
    val review: VersionReviewItem? get() = null
    val buildSystemId: String? get() = null
    /** Null inherits the containing plan's guidance; combined plans assign it per edit. */
    val followUp: List<String>? get() = null
    /** Captured text edits can be validated and committed once per file, including combined plans. */
    val documentChange: DocumentVersionChange? get() = null
    fun preview(): VersionEditPreview? = documentChange?.preview()
    fun isValid(): Boolean
    fun apply()
}

internal data class BulkUpdatePlan(
    val changes: List<VersionEdit>,
    val skipped: List<String>,
    private val isCurrent: () -> Boolean = { true },
    val followUp: List<String> = emptyList(),
    val prepareValidation: EditInputCapture? = null,
    val reviewItems: List<VersionReviewItem> = emptyList()
) {
    /** Historical declaration snapshots from the most recent successful, non-no-op apply. */
    var appliedChanges: AppliedVersionChanges? = null
        private set
    private val authorization = EditAuthorization(isCurrent)
    private val inputCapture = prepareValidation

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
                reviewItems = plans.flatMap { it.reviewItems }.distinct(),
                isCurrent = { plans.all { it.authorization.isCurrent() } },
                followUp = plans.flatMap { it.followUp }.distinct(),
                prepareValidation = if (plans.any { it.prepareValidation != null }) EditInputCapture {
                    val guards = plans.map { it.inputCapture?.capture() ?: CapturedEditInputs { true } }
                    CapturedEditInputs { guards.all { it.isCurrent() } }
                } else null
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

    /** Reject expired/refreshed authorization before potentially expensive provider input validation. */
    fun guardedBy(valid: () -> Boolean) = copy(isCurrent = { valid() && authorization.isCurrent() })

    /** Validate every edit and the discovery snapshot before applying one undoable command. */
    fun apply(
        project: Project,
        interaction: CheckPerformance.Interaction? = CheckPerformance.start(CheckPerformance.Stage.BULK_APPLY_INVOKED)
    ): Boolean =
        CheckPerformance.locally(interaction) {
            appliedChanges = null
            if (inputCapture != null && !authorization.isCurrent()) return@locally false
            val inputGuard = inputCapture?.let { capture ->
                CheckPerformance.measure(CheckPerformance.Stage.EDIT_INPUT_CAPTURE) {
                    runWithModalProgressBlocking(project, "Validating version update inputs") { capture.capture() }
                }
            } ?: CapturedEditInputs { true }
            var applied = false
            WriteCommandAction.runWriteCommandAction(project, "Update versions", null, Runnable {
                // Time the atomic callback after write access is acquired; input capture is separate.
                CheckPerformance.measure(CheckPerformance.Stage.EDIT_WRITE_COMMAND, changes.size) write@{
                    val validated = CheckPerformance.measure(CheckPerformance.Stage.EDIT_PLAN_VALIDATION, changes.size) validate@{
                        if (project.isDisposed || !authorization.isCurrent() || !inputGuard.isCurrent()) return@validate null
                        val textChanges = changes.mapNotNull { it.documentChange }
                        val prepared = DocumentVersionChange.prepare(textChanges) ?: return@validate null
                        val elementChanges = changes.filter { it.documentChange == null }
                        if (elementChanges.any { !it.isValid() }) return@validate null
                        prepared to elementChanges
                    } ?: return@write
                    val captured = AppliedVersionChangeCapture.before(changes)
                    validated.first.forEach { it.apply() }
                    validated.second.forEach { it.apply() }
                    applied = true
                    appliedChanges = if (captured == null) AppliedVersionChanges(emptyList(), captureUnavailable = true) else captured.finish()
                    CheckPerformance.record(
                        CheckPerformance.Stage.EDITOR_TEXT_CHANGED,
                        interaction?.started ?: System.nanoTime(),
                        changes.size,
                        interaction
                    )
                }
            })
            applied
        }
}
