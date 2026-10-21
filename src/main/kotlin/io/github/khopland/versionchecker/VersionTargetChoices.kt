package io.github.khopland.versionchecker

/** An option is an already prepared provider edit, never a replacement version string. */
internal data class VersionTargetChoice(val mode: UpdateMode, val plan: BulkUpdatePlan, val index: Int) {
    val edit get() = plan.changes[index]
    override fun toString() = "${edit.latest} · ${when (mode) {
        UpdateMode.PATCH -> "highest patch"
        UpdateMode.MINOR -> "highest minor / patch"
        UpdateMode.MAJOR -> "highest stable"
    }}"
}

private data class VersionTargetOwner(
    val provider: String?, val file: String?, val start: Int?, val end: Int?,
    val location: String, val expected: String
)

private fun VersionEdit.targetOwner() = VersionTargetOwner(buildSystemId,
    documentChange?.file?.virtualFile?.path ?: element?.containingFile?.virtualFile?.path,
    documentChange?.range?.startOffset ?: element?.textRange?.startOffset,
    documentChange?.range?.endOffset ?: element?.textRange?.endOffset, location, expected)

/** Keep each selected target attached to the native checks and guards that prepared it. */
internal class VersionTargetChoices(
    private val primary: BulkUpdatePlan,
    private val mode: UpdateMode,
    plans: Map<UpdateMode, BulkUpdatePlan> = emptyMap()
) {
    private val alternatives = plans.filterKeys { it.ordinal <= mode.ordinal }.mapValues { (kind, plan) ->
        plan.changes.indices.map { VersionTargetChoice(kind, plan, it) }.groupBy { it.edit.targetOwner() }
    }
    private val selected = primary.changes.indices.map { VersionTargetChoice(mode, primary, it) }.toMutableList()

    fun selected(row: Int): VersionTargetChoice = selected[row]

    fun options(row: Int): List<VersionTargetChoice> {
        val original = VersionTargetChoice(mode, primary, row)
        return (listOf(original) + alternatives.values.mapNotNull { it[original.edit.targetOwner()]?.singleOrNull() })
            .distinctBy { it.edit.latest }
    }

    fun choose(row: Int, choice: VersionTargetChoice): Boolean {
        if (choice !in options(row) || selected[row] == choice) return false
        selected[row] = choice
        return true
    }

    fun plan(indices: Set<Int>): BulkUpdatePlan {
        require(indices.all { it in selected.indices })
        if (indices.isEmpty()) return primary.select(emptySet())
        val chosen = indices.sorted().map { selected[it] }
        val plans = chosen.groupBy { it.plan }.map { (plan, choices) ->
            plan.select(choices.map { it.index }.toSet())
        }
        if (plans.size == 1) return plans.single().copy(changes = chosen.map { it.edit })
        return BulkUpdatePlan.combine(plans).copy(changes = chosen.map { choice ->
            object : VersionEdit by choice.edit {
                override val followUp = choice.edit.followUp ?: choice.plan.followUp
            }
        })
    }
}
