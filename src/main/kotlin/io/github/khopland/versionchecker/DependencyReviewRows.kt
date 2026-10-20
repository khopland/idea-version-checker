package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*

/** The context of a cached result can differ from its physical declaration. */
internal data class DependencyReviewOrigin(
    val context: BuildContextId, val declaration: DeclarationId, val checkSourceFile: String,
    val coverageDescription: String? = null,
)

/** Display data does not authorize edits or confirm that build inputs are current. */
internal data class DependencyReviewRow(
    val adapter: String, val root: String, val source: DeclarationId, val artifact: String,
    val current: String, val available: String?, val kind: VersionChangeKind?, val outcome: DependencyReviewOutcome,
    val detail: String, val mode: UpdateMode, val checking: Boolean, val checkFailed: Boolean = false,
    val checkedAtNanos: Long? = null, val expiresAtNanos: Long? = null,
    val origin: DependencyReviewOrigin? = null,
) {
    val failed: Boolean get() = checkFailed || outcome == DependencyReviewOutcome.FAILED
}

internal enum class DependencyReviewFilter {
    ALL, UPDATES, REVIEW, FAILED;

    fun matches(row: DependencyReviewRow): Boolean = when (this) {
        ALL -> true
        UPDATES -> row.outcome == DependencyReviewOutcome.UPDATE
        REVIEW -> when (row.outcome) {
            DependencyReviewOutcome.REVIEW, DependencyReviewOutcome.SKIPPED, DependencyReviewOutcome.UNREPORTED -> true
            else -> false
        }
        FAILED -> row.failed
    }
}

internal object DependencyReviewRows {
    fun from(observations: List<VersionResultCache.Observation>, ignoredVersions: String = "", policy: ProjectPolicySnapshot? = null): List<DependencyReviewRow> {
        val policies = mutableMapOf<String, VersionPresentationPolicy>()
        return observations.flatMap { observation ->
            val adapter = observation.snapshot.context.adapterId
            val presentation = policies.getOrPut(adapter) { VersionPresentationPolicy(adapter, ignoredVersions, policy) }
            fromObservation(observation, presentation.filter(observation.report))
        }.sortedWith(compareBy({ it.root }, { it.source.file }, { it.adapter }, { it.artifact }, { it.mode.ordinal }))
    }

    private fun fromObservation(observation: VersionResultCache.Observation, report: UpdateReport): List<DependencyReviewRow> {
        val snapshot = observation.snapshot
        val declarations = snapshot.declarations.associateBy { it.id }
        val candidates = report.candidates.associateByFirst { it.declaration.id }
        val notices = report.notices.associateByFirst { it.declaration.id }
        val ids = snapshot.declarationIds() + candidates.keys + notices.keys + report.coverage.orEmpty().keys
        if (ids.isEmpty()) return listOf(emptyRow(observation, report))

        return ids.map { id ->
            val candidate = candidates[id]
            val notice = notices[id]
            val declaration = declarations[id] ?: candidate?.declaration ?: notice?.declaration
            val coverage = report.coverage?.get(id)
            val outcome = outcome(coverage, notice, candidate, report.failure, observation.checking)
            DependencyReviewRow(
                adapter = snapshot.context.adapterId,
                root = snapshot.context.root,
                source = declaration?.sourceId ?: id,
                artifact = declaration?.let { artifactLabel(it.artifact) }.orEmpty(),
                current = declaration?.selector.orEmpty(),
                available = candidate?.version,
                kind = candidate?.kind,
                outcome = outcome,
                detail = DiagnosticSanitizer.sanitize(listOfNotNull(notice?.message, coverage?.reason,
                    snapshot.skippedDeclarations[id], report.failure).distinct().joinToString(" ")),
                mode = observation.mode,
                checking = observation.checking,
                checkFailed = report.failure != null,
                checkedAtNanos = observation.checkedAtNanos.takeIf { outcome.hasObservationTime },
                expiresAtNanos = observation.expiresAtNanos.takeIf { outcome.hasObservationTime },
                origin = DependencyReviewOrigin(snapshot.context, id, snapshot.sourceFile, snapshot.coverageDescription),
            )
        }
    }

    private fun outcome(coverage: DeclarationCoverage?, notice: UpdateNotice?, candidate: UpdateCandidate?,
                        failure: String?, checking: Boolean): DependencyReviewOutcome = when {
        coverage?.outcome == DeclarationOutcome.FAILED -> DependencyReviewOutcome.FAILED
        notice != null || coverage?.outcome == DeclarationOutcome.MANUAL_REVIEW -> DependencyReviewOutcome.REVIEW
        candidate != null -> DependencyReviewOutcome.UPDATE
        coverage != null -> DependencyReviewOutcome.fromCoverage(coverage.outcome)
        failure != null -> DependencyReviewOutcome.FAILED
        checking -> DependencyReviewOutcome.PENDING
        else -> DependencyReviewOutcome.UNREPORTED
    }

    private fun emptyRow(observation: VersionResultCache.Observation, report: UpdateReport): DependencyReviewRow {
        val snapshot = observation.snapshot
        val source = DeclarationId(snapshot.sourceFile, "")
        return DependencyReviewRow(
            adapter = snapshot.context.adapterId,
            root = snapshot.context.root,
            source = source,
            artifact = "",
            current = "",
            available = null,
            kind = null,
            outcome = if (report.failure != null) DependencyReviewOutcome.FAILED else DependencyReviewOutcome.NO_DECLARATIONS,
            detail = DiagnosticSanitizer.sanitize(report.failure ?: snapshot.coverageDescription.orEmpty()),
            mode = observation.mode,
            checking = observation.checking,
            checkFailed = report.failure != null,
            checkedAtNanos = observation.checkedAtNanos,
            expiresAtNanos = observation.expiresAtNanos,
            origin = DependencyReviewOrigin(snapshot.context, source, snapshot.sourceFile, snapshot.coverageDescription),
        )
    }

    fun filter(rows: List<DependencyReviewRow>, filter: DependencyReviewFilter,
               kind: VersionChangeKind? = null): List<DependencyReviewRow> = rows.filter { row ->
        (kind == null || row.kind == kind) && filter.matches(row)
    }

    /** Keep the first result for each declaration, in report order. */
    private fun <T, K> List<T>.associateByFirst(key: (T) -> K): Map<K, T> = buildMap {
        for (value in this@associateByFirst) putIfAbsent(key(value), value)
    }
}
