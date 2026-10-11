package io.github.khopland.versionchecker.core

import io.github.khopland.versionchecker.UpdateMode
import java.util.concurrent.TimeUnit

/** Provider/context isolation and refresh generations are shared across build systems. */
internal class VersionResultCache(
    private val limits: Limits = Limits(),
    private val now: () -> Long = System::nanoTime
) {
    /** Payload units count declarations, input paths and report rows, plus their text in 256-char blocks. */
    data class Limits(val contexts: Int = 2_048, val results: Int = 1_024, val payloadUnits: Long = 1_000_000) {
        init { require(contexts > 0 && results > 0 && payloadUnits > 0) }
    }
    data class Retention(val contexts: Int, val sources: Int, val results: Int, val progress: Int, val payloadUnits: Long)
    data class Revision(val global: Long, val context: Long)
    data class CachedResult(val report: UpdateReport, val lease: VersionResultLease) {
        val checkedAtNanos get() = lease.checkedAtNanos
        val expiresAtNanos get() = lease.expiresAtNanos
        val revision get() = lease.revision
    }
    private data class Entry(
        val snapshot: BuildSnapshot,
        val revision: Revision,
        val checkedAtNanos: Long,
        val expiresAtNanos: Long,
        val report: UpdateReport,
        val weight: Long = snapshot.weight() + report.weight(),
        val identity: Any = Any()
    )
    private class ProgressEntry(
        val snapshot: BuildSnapshot,
        val revision: Revision,
        val owner: Any,
        var expiresAtNanos: Long,
        var checkedAtNanos: Long,
        initial: UpdateReport?
    ) {
        private val candidates = initial?.candidates.orEmpty().groupBy { it.declaration.id }.toMutableMap()
        private val notices = initial?.notices.orEmpty().groupBy { it.declaration.id }.toMutableMap()
        private val failures = linkedSetOf<String>().apply { initial?.failure?.let(::add) }
        // Retained candidates are hints, not completed units of this new check.
        private val coverage = snapshot.skippedCoverage().toMutableMap()
        private var coverageKnown = coverage.isNotEmpty()
        var weight: Long = snapshot.weight() + payloadWeight()
            private set
        var identity: Any = Any()
            private set

        private fun payloadWeight() = candidates.values.sumOf { rows -> rows.sumOf { it.weight() } } +
            notices.values.sumOf { rows -> rows.sumOf { it.weight() } } + failures.sumOf { it.textWeight() } + coverage.entries.sumOf { it.weight() }

        fun add(update: InspectionUpdate) {
            // A later partial observation must not silently authorize a descriptor from an older one.
            identity = Any()
            update.report.checkedAtNanos?.let { checkedAtNanos = minOf(checkedAtNanos, it) }
            update.completedDeclarations.forEach { id ->
                weight -= candidates.remove(id).orEmpty().sumOf { it.weight() }
                weight -= notices.remove(id).orEmpty().sumOf { it.weight() }
                coverage.remove(id)?.let { weight -= coverageWeight(id, it) }
            }
            update.report.candidates.groupBy { it.declaration.id }.forEach { (id, rows) ->
                weight -= candidates.put(id, rows).orEmpty().sumOf { it.weight() }
                weight += rows.sumOf { it.weight() }
            }
            update.report.notices.groupBy { it.declaration.id }.forEach { (id, rows) ->
                weight -= notices.put(id, rows).orEmpty().sumOf { it.weight() }
                weight += rows.sumOf { it.weight() }
            }
            update.report.coverage?.let { outcomes ->
                coverageKnown = true
                outcomes.forEach { (id, outcome) ->
                    coverage.put(id, outcome)?.let { weight -= coverageWeight(id, it) }
                    weight += coverageWeight(id, outcome)
                }
            }
            update.report.failure?.let { if (failures.add(it)) weight += it.textWeight() }
        }
        fun report() = UpdateReport(
            candidates = candidates.values.flatten(),
            notices = notices.values.flatten(),
            failure = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            validUntilNanos = expiresAtNanos,
            checkedAtNanos = checkedAtNanos,
            coverage = coverage.takeIf { coverageKnown }?.toMap()
        )
        fun coverageSummary(include: ((UpdateCandidate) -> Boolean)?): CoverageSummary? =
            if (!coverageKnown) null else CoverageSummary.from(coverage, snapshot.declarationIds(),
                if (include == null) emptySet() else candidates.filterValues { rows -> rows.any { !include(it) } }.keys)
        fun counts(include: ((UpdateCandidate) -> Boolean)?) =
            candidates.values.sumOf { if (include == null) it.size else it.count(include) } to notices.values.sumOf { it.size }
    }
    private class ContextState(val revision: Revision, var expiresAtNanos: Long, var source: String? = null) {
        val entries = mutableMapOf<UpdateMode, Entry>()
        var progress: ProgressEntry? = null
        val weight get() = entries.values.sumOf { it.weight } + (progress?.weight ?: 0)
        val results get() = entries.size + if (progress == null) 0 else 1
    }
    private var globalGeneration = 0L
    private var nextContextGeneration = 0L // Never reused, even after eviction, refresh or global cleanup.
    private val contexts = LinkedHashMap<BuildContextId, ContextState>(16, 0.75f, true)
    private var nextSweepAtNanos = Long.MIN_VALUE
    private val contextTtl = TimeUnit.MINUTES.toNanos(10)
    private val sweepInterval = TimeUnit.SECONDS.toNanos(30)

    private fun state(context: BuildContextId): ContextState? {
        sweep()
        val state = contexts[context] ?: return null
        if (state.expiresAtNanos <= now()) { contexts.remove(context); return null }
        prune(state, now())
        return state
    }

    private fun prune(state: ContextState, time: Long) {
        state.entries.values.removeAll { it.expiresAtNanos <= time }
        if (state.progress?.expiresAtNanos?.let { it <= time } == true) state.progress = null
    }

    private fun sweep(force: Boolean = false) {
        val time = now()
        if (!force && time < nextSweepAtNanos) return
        nextSweepAtNanos = time + sweepInterval
        val iterator = contexts.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            prune(state, time)
            if (state.expiresAtNanos <= time) iterator.remove()
        }
    }

    private fun trim() {
        var results = contexts.values.sumOf { it.results }
        var weight = contexts.values.sumOf { it.weight }
        val iterator = contexts.values.iterator()
        while (contexts.size > limits.contexts || results > limits.results || weight > limits.payloadUnits) {
            val evicted = iterator.next()
            results -= evicted.results
            weight -= evicted.weight
            iterator.remove()
        }
    }

    @Synchronized fun retention(): Retention {
        sweep(force = true)
        return Retention(contexts.size, contexts.values.count { it.source != null },
            contexts.values.sumOf { it.entries.size }, contexts.values.count { it.progress != null },
            contexts.values.sumOf { it.weight })
    }

    @Synchronized fun revision(context: BuildContextId): Revision = state(context)?.revision ?: run {
        val revision = Revision(globalGeneration, ++nextContextGeneration)
        contexts[context] = ContextState(revision, now() + contextTtl)
        trim()
        revision
    }

    @Synchronized fun begin(snapshot: BuildSnapshot): Revision {
        val revision = revision(snapshot.context)
        contexts[snapshot.context]?.let { it.source = snapshot.sourceFile; it.expiresAtNanos = now() + contextTtl }
        return revision
    }

    private fun entry(snapshot: BuildSnapshot, mode: UpdateMode): Entry? = state(snapshot.context)?.entries?.get(mode)?.takeIf {
        it.snapshot.hasSameDeclarations(snapshot) &&
            it.snapshot.coverageDescription == snapshot.coverageDescription &&
            it.revision == revision(snapshot.context) && it.expiresAtNanos > now()
    }

    @Synchronized fun get(snapshot: BuildSnapshot, mode: UpdateMode = UpdateMode.MAJOR): UpdateReport? = entry(snapshot, mode)?.report
    @Synchronized fun getResult(snapshot: BuildSnapshot, mode: UpdateMode): CachedResult? =
        entry(snapshot, mode)?.let { CachedResult(it.report,
            lease(snapshot.context, mode, it.revision, it.checkedAtNanos, it.expiresAtNanos, it.identity)) }

    @Synchronized fun isCurrent(context: BuildContextId, mode: UpdateMode, result: CachedResult): Boolean {
        return result.lease.context == context && result.lease.mode == mode && result.lease.isCurrent()
    }

    private fun lease(context: BuildContextId, mode: UpdateMode, revision: Revision,
                      checkedAtNanos: Long, expiresAtNanos: Long, identity: Any,
                      partial: Boolean = false) = VersionResultLease(context, mode, revision, checkedAtNanos, expiresAtNanos) {
        isLeaseCurrent(context, mode, revision, expiresAtNanos, identity, partial)
    }

    @Synchronized private fun isLeaseCurrent(context: BuildContextId, mode: UpdateMode, revision: Revision,
                                             expiresAtNanos: Long, identity: Any, partial: Boolean): Boolean {
        val state = state(context) ?: return false
        if (state.revision != revision || now() >= expiresAtNanos) return false
        return if (partial) state.progress?.identity === identity else state.entries[mode]?.identity === identity
    }

    /** Retained/partial hints carry their originating lease; copying/filtering cannot renew it. */
    @Synchronized fun inspectionReport(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        getResult(snapshot, UpdateMode.MAJOR)?.let { return it.report.copy(lease = it.lease) }
        val state = state(snapshot.context) ?: return null
        state.progress?.let { progress ->
            inspectionProgress(adapter, snapshot)?.let { report ->
                return report.copy(lease = lease(snapshot.context, UpdateMode.MAJOR, progress.revision,
                    progress.checkedAtNanos, progress.expiresAtNanos, progress.identity, partial = true))
            }
        }
        val entry = state.entries[UpdateMode.MAJOR] ?: return null
        val retained = retainedInspectionReport(adapter, snapshot) ?: return null
        return retained.copy(lease = lease(snapshot.context, UpdateMode.MAJOR, entry.revision,
            entry.checkedAtNanos, entry.expiresAtNanos, entry.identity))
    }

    @Synchronized fun retainedInspectionReport(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        val entry = state(snapshot.context)?.entries?.get(UpdateMode.MAJOR)?.takeIf {
            it.revision == revision(snapshot.context) && it.expiresAtNanos > now() && it.report.successful
        } ?: return null
        return adapter.retainInspectionReport(entry.snapshot, entry.report, snapshot)
    }

    @Synchronized fun inspectionProgress(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        val entry = state(snapshot.context)?.progress?.takeIf {
            it.revision == revision(snapshot.context) && it.expiresAtNanos > now()
        } ?: return null
        val report = entry.report()
        return if (entry.snapshot.hasSameDeclarations(snapshot)) report
        else adapter.retainInspectionReport(entry.snapshot, report, snapshot)
    }

    /** Status counts avoid allocating a complete partial report for every UI refresh. */
    @Synchronized fun inspectionProgressCounts(snapshot: BuildSnapshot,
                                               include: ((UpdateCandidate) -> Boolean)? = null): Triple<Int, Int, Long>? {
        val entry = state(snapshot.context)?.progress?.takeIf {
            it.revision == revision(snapshot.context) && it.expiresAtNanos > now() &&
                it.snapshot.hasSameDeclarations(snapshot)
        } ?: return null
        val (candidateCount, noticeCount) = entry.counts(include)
        return Triple(candidateCount, noticeCount, entry.expiresAtNanos)
    }

    @Synchronized fun inspectionProgressCoverage(snapshot: BuildSnapshot,
                                                include: ((UpdateCandidate) -> Boolean)? = null): CoverageSummary? =
        state(snapshot.context)?.progress?.takeIf {
            it.revision == revision(snapshot.context) && it.expiresAtNanos > now() && it.snapshot.hasSameDeclarations(snapshot)
        }?.coverageSummary(include)

    @Synchronized fun putProgress(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, revision: Revision,
                                  owner: Any, update: InspectionUpdate): Boolean {
        val state = state(snapshot.context)?.takeIf { it.revision == revision } ?: return false
        val time = now()
        val active = state.progress?.takeIf { it.revision == revision && it.expiresAtNanos > time }
        val entry = if (active?.owner === owner && active.snapshot == snapshot) active else {
            val previous = state.entries[UpdateMode.MAJOR]?.takeIf {
                it.revision == revision && it.expiresAtNanos > time && it.report.successful
            }
            val previousSnapshot = active?.snapshot ?: previous?.snapshot
            val previousReport = active?.report() ?: previous?.report
            val retained = when {
                previousSnapshot == null || previousReport == null -> null
                previousSnapshot.hasSameDeclarations(snapshot) -> previousReport
                else -> adapter.retainInspectionReport(previousSnapshot, previousReport, snapshot)
            }
            // Retaining warnings must also retain the metadata's original freshness deadline.
            val retainedExpiry = if (retained != null) active?.expiresAtNanos ?: previous?.expiresAtNanos else null
            val checkedAt = if (retained != null) active?.checkedAtNanos ?: previous?.checkedAtNanos ?: time
                else update.report.checkedAtNanos ?: time
            ProgressEntry(
                snapshot = snapshot,
                revision = revision,
                owner = owner,
                expiresAtNanos = retainedExpiry ?: time + TimeUnit.MINUTES.toNanos(10),
                checkedAtNanos = minOf(time, checkedAt),
                initial = retained
            )
        }
        val expires = minOf(entry.expiresAtNanos, update.report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        entry.expiresAtNanos = expires
        entry.add(update)
        if (entry.weight > limits.payloadUnits) { contexts.remove(snapshot.context); return false }
        state.source = snapshot.sourceFile
        state.progress = entry
        state.expiresAtNanos = maxOf(state.expiresAtNanos, expires)
        // A newer partial check must not leave an older complete report authorizing edits.
        state.entries.remove(UpdateMode.MAJOR)
        trim()
        return contexts[snapshot.context] === state
    }

    /** An older cancelled job must not erase a replacement check's progress. */
    @Synchronized fun clearProgress(context: BuildContextId, owner: Any): Boolean {
        val state = state(context) ?: return false
        if (state.progress?.owner !== owner) return false
        state.progress = null
        return true
    }

    @Synchronized fun put(snapshot: BuildSnapshot, revision: Revision, report: UpdateReport, mode: UpdateMode = UpdateMode.MAJOR): Boolean {
        val state = state(snapshot.context)?.takeIf { it.revision == revision } ?: return false
        val time = now()
        val ttl = TimeUnit.MINUTES.toNanos(if (report.successful) 10 else 1)
        val expires = minOf(time + ttl, report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        val entry = Entry(snapshot, revision, minOf(time, report.checkedAtNanos ?: time), expires, report)
        if (entry.weight > limits.payloadUnits) { contexts.remove(snapshot.context); return false }
        state.source = snapshot.sourceFile
        state.entries[mode] = entry
        state.expiresAtNanos = maxOf(state.expiresAtNanos, expires)
        if (mode == UpdateMode.MAJOR) state.progress = null
        trim()
        return contexts[snapshot.context] === state
    }

    @Synchronized fun invalidate(context: BuildContextId? = null) {
        if (context == null) {
            globalGeneration++
            contexts.clear()
        } else {
            contexts.remove(context)
        }
    }

    @Synchronized fun invalidateAdapter(adapterId: String) {
        contexts.keys.removeAll { it.adapterId == adapterId }
    }

    @Synchronized fun invalidateSource(adapterId: String, sourceFile: String) {
        contexts.entries.removeAll { (context, state) -> context.adapterId == adapterId && state.source == sourceFile }
    }

    /** Inspection hints can match declarations without claiming the same audit coverage. */
    private fun BuildSnapshot.hasSameDeclarations(other: BuildSnapshot): Boolean =
        fingerprint == other.fingerprint && declarations == other.declarations && skippedDeclarations == other.skippedDeclarations
}

private fun String?.textWeight(): Long = if (this == null) 0 else 1 + length.toLong() / 256
private fun VersionDeclaration.weight(): Long = 1 + id.file.textWeight() + id.location.textWeight() +
    artifact.namespace.textWeight() + artifact.name.textWeight() + selector.textWeight() +
    baseline.textWeight() + resolvedVersion.textWeight() + (sourceId?.let { it.file.textWeight() + it.location.textWeight() } ?: 0)
private fun BuildFingerprint.weight(): Long = configuration.textWeight() +
    files.entries.sumOf { 1 + it.key.textWeight() + it.value.textWeight() }
private fun BuildSnapshot.weight(): Long = 1 + sourceFile.textWeight() + fingerprint.weight() +
    (inspectionFingerprint?.weight() ?: 0) + coverageDescription.textWeight() + declarations.sumOf { it.weight() } +
    skippedDeclarations.entries.sumOf { 1 + it.key.file.textWeight() + it.key.location.textWeight() + it.value.textWeight() }
private fun UpdateCandidate.weight(): Long = 1 + declaration.weight() + version.textWeight() + replacementSelector.textWeight()
private fun UpdateNotice.weight(): Long = 1 + declaration.weight() + message.textWeight()
private fun UpdateReport.weight(): Long = 1 + candidates.sumOf { it.weight() } + notices.sumOf { it.weight() } + failure.textWeight() + coverage.orEmpty().entries.sumOf { it.weight() }

private fun coverageWeight(id: DeclarationId, coverage: DeclarationCoverage): Long =
    1 + id.file.textWeight() + id.location.textWeight() + coverage.reason.textWeight()
private fun Map.Entry<DeclarationId, DeclarationCoverage>.weight(): Long = coverageWeight(key, value)
