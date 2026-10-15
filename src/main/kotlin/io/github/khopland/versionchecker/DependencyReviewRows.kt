package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*

/** Presentation values only. They do not authorize edits or claim the build inputs are still current. */
internal data class DependencyReviewRow(
    val adapter: String, val root: String, val source: DeclarationId, val artifact: String,
    val current: String, val available: String?, val kind: VersionChangeKind?, val outcome: String,
    val detail: String, val mode: UpdateMode, val checking: Boolean, val checkFailed: Boolean = false
)

internal enum class DependencyReviewFilter { ALL, UPDATES, REVIEW, FAILED }

internal object DependencyReviewRows {
    fun from(observations: List<VersionResultCache.Observation>, ignoredVersions: String = ""): List<DependencyReviewRow> =
        observations.flatMap { observation ->
            val snapshot = observation.snapshot
            val report = VersionPresentationPolicy(snapshot.context.adapterId, ignoredVersions).filter(observation.report)
            val declarations = snapshot.declarations.associateBy { it.id }
            val ids = snapshot.declarationIds() + report.candidates.map { it.declaration.id } +
                report.notices.map { it.declaration.id } + report.coverage.orEmpty().keys
            val candidates = report.candidates.groupBy { it.declaration.id }
            val notices = report.notices.groupBy { it.declaration.id }
            ids.map { id ->
                val candidate = candidates[id]?.firstOrNull()
                val notice = notices[id]?.firstOrNull()
                val declaration = declarations[id] ?: candidate?.declaration ?: notice?.declaration
                val coverage = report.coverage?.get(id)
                val outcome = when {
                    coverage?.outcome == DeclarationOutcome.FAILED -> "Failed"
                    notice != null || coverage?.outcome == DeclarationOutcome.MANUAL_REVIEW -> "Review"
                    candidate != null -> "Update"
                    coverage != null -> coverage.outcome.name.lowercase().replaceFirstChar { it.uppercase() }
                    report.failure != null -> "Failed"
                    observation.checking -> "Pending"
                    else -> "Coverage unreported"
                }
                DependencyReviewRow(snapshot.context.adapterId, snapshot.context.root, declaration?.sourceId ?: id,
                    declaration?.let { artifactLabel(it.artifact) }.orEmpty(), declaration?.selector.orEmpty(),
                    candidate?.version, candidate?.kind, outcome,
                    DiagnosticSanitizer.sanitize(listOfNotNull(notice?.message, coverage?.reason,
                        snapshot.skippedDeclarations[id], report.failure).distinct().joinToString(" ")),
                    observation.mode, observation.checking, report.failure != null)
            } + if (ids.isEmpty()) listOf(DependencyReviewRow(snapshot.context.adapterId, snapshot.context.root,
                DeclarationId(snapshot.sourceFile, ""), "", "", null, null,
                if (report.failure != null) "Failed" else "No recognized declarations",
                DiagnosticSanitizer.sanitize(report.failure ?: snapshot.coverageDescription.orEmpty()),
                observation.mode, observation.checking, report.failure != null)) else emptyList()
        }.sortedWith(compareBy({ it.root }, { it.source.file }, { it.adapter }, { it.artifact }, { it.mode.ordinal }))

    fun filter(rows: List<DependencyReviewRow>, filter: DependencyReviewFilter,
               kind: VersionChangeKind? = null): List<DependencyReviewRow> = rows.filter { row ->
        (kind == null || row.kind == kind) && when (filter) {
            DependencyReviewFilter.ALL -> true
            DependencyReviewFilter.UPDATES -> row.outcome == "Update"
            DependencyReviewFilter.REVIEW -> row.outcome in setOf("Review", "Skipped", "Coverage unreported")
            DependencyReviewFilter.FAILED -> row.outcome == "Failed" || row.checkFailed
        }
    }
}
