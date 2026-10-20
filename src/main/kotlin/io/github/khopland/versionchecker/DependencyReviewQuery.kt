package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.BuildContextId
import io.github.khopland.versionchecker.core.VersionChangeKind

/** Filter display data without repository requests or edit authorization. */
internal data class DependencyReviewQuery(
    val text: String = "",
    val provider: String? = null,
    val root: String? = null,
    val outcome: DependencyReviewFilter = DependencyReviewFilter.ALL,
    val kind: VersionChangeKind? = null,
) {
    fun apply(rows: List<DependencyReviewRow>): List<DependencyReviewRow> {
        val search = text.trim()
        return rows.filter { row ->
            (provider == null || row.adapter == provider) &&
                (root == null || row.root == root) &&
                (kind == null || row.kind == kind) &&
                outcome.matches(row) &&
                (search.isEmpty() || row.artifact.contains(search, ignoreCase = true) ||
                    row.source.file.contains(search, ignoreCase = true) ||
                    row.root.contains(search, ignoreCase = true) ||
                    row.origin?.checkSourceFile?.contains(search, ignoreCase = true) == true)
        }
    }
}

/** This identity stays the same when versions, outcomes, reasons, or sort order change. */
internal data class DependencyReviewIdentity(
    val provider: String, val root: String, val source: DeclarationId, val artifact: String, val mode: UpdateMode,
    val context: BuildContextId? = null, val declaration: DeclarationId = source,
)

internal fun DependencyReviewRow.identity() = DependencyReviewIdentity(adapter, root, source, artifact, mode,
    origin?.context, origin?.declaration ?: source)
