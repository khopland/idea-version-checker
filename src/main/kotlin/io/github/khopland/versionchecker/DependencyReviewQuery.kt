package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.BuildContextId
import io.github.khopland.versionchecker.core.VersionChangeKind

/** Literal presentation filters. No repository lookup or edit authorization occurs here. */
internal data class DependencyReviewQuery(
    val text: String = "",
    val provider: String? = null,
    val root: String? = null,
    val outcome: DependencyReviewFilter = DependencyReviewFilter.ALL,
    val kind: VersionChangeKind? = null,
) {
    fun apply(rows: List<DependencyReviewRow>): List<DependencyReviewRow> =
        DependencyReviewRows.filter(rows, outcome, kind).filter { row ->
            (provider == null || row.adapter == provider) && (root == null || row.root == root) &&
                (text.isBlank() || listOfNotNull(row.artifact, row.source.file, row.root, row.origin?.checkSourceFile)
                    .any { it.contains(text.trim(), ignoreCase = true) })
        }
}

/** Stable across result replacement, sorting and changes to versions, outcomes or reasons. */
internal data class DependencyReviewIdentity(
    val provider: String, val root: String, val source: DeclarationId, val artifact: String, val mode: UpdateMode,
    val context: BuildContextId? = null, val declaration: DeclarationId = source,
)

internal fun DependencyReviewRow.identity() = DependencyReviewIdentity(adapter, root, source, artifact, mode,
    origin?.context, origin?.declaration ?: source)
