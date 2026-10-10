package io.github.khopland.versionchecker.core

import io.github.khopland.versionchecker.UpdateMode
import java.util.concurrent.TimeUnit

/** Provider/context isolation and refresh generations are shared across build systems. */
internal class VersionResultCache(private val now: () -> Long = System::nanoTime) {
    data class Revision(val global: Long, val adapter: Long, val context: Long)
    private data class Key(val context: BuildContextId, val mode: UpdateMode)
    data class CachedResult(val report: UpdateReport, val checkedAtNanos: Long, val expiresAtNanos: Long, val revision: Revision)
    private data class Entry(val snapshot: BuildSnapshot, val revision: Revision,
                             val checkedAt: Long, val expires: Long, val report: UpdateReport)
    private class ProgressEntry(val snapshot: BuildSnapshot, val revision: Revision, val owner: Any,
                                var expires: Long, initial: UpdateReport?) {
        private val candidates = initial?.candidates.orEmpty().groupBy { it.declaration.id }.toMutableMap()
        private val notices = initial?.notices.orEmpty().groupBy { it.declaration.id }.toMutableMap()
        private val failures = linkedSetOf<String>().apply { initial?.failure?.let(::add) }

        fun add(update: InspectionUpdate) {
            update.completedDeclarations.forEach { candidates.remove(it); notices.remove(it) }
            candidates.putAll(update.report.candidates.groupBy { it.declaration.id })
            notices.putAll(update.report.notices.groupBy { it.declaration.id })
            update.report.failure?.let(failures::add)
        }
        fun report() = UpdateReport(candidates.values.flatten(), notices.values.flatten(),
            failures.takeIf { it.isNotEmpty() }?.joinToString("\n"), validUntilNanos = expires)
        fun counts() = candidates.values.sumOf { it.size } to notices.values.sumOf { it.size }
    }
    private var global = 0L
    private val adapters = mutableMapOf<String, Long>()
    private val generations = mutableMapOf<BuildContextId, Long>()
    private val entries = mutableMapOf<Key, Entry>()
    private val progress = mutableMapOf<BuildContextId, ProgressEntry>()
    private val sources = mutableMapOf<BuildContextId, String>()

    @Synchronized fun revision(context: BuildContextId) = Revision(global, adapters[context.adapterId] ?: 0, generations[context] ?: 0)

    @Synchronized fun begin(snapshot: BuildSnapshot): Revision {
        sources[snapshot.context] = snapshot.sourceFile
        return revision(snapshot.context)
    }

    private fun entry(snapshot: BuildSnapshot, mode: UpdateMode): Entry? = entries[Key(snapshot.context, mode)]?.takeIf {
        it.snapshot.fingerprint == snapshot.fingerprint && it.snapshot.declarations == snapshot.declarations &&
            it.snapshot.coverageDescription == snapshot.coverageDescription &&
            it.revision == revision(snapshot.context) && it.expires > now()
    }

    @Synchronized fun get(snapshot: BuildSnapshot, mode: UpdateMode = UpdateMode.MAJOR): UpdateReport? = entry(snapshot, mode)?.report
    @Synchronized fun getResult(snapshot: BuildSnapshot, mode: UpdateMode): CachedResult? =
        entry(snapshot, mode)?.let { CachedResult(it.report, it.checkedAt, it.expires, it.revision) }

    @Synchronized fun isCurrent(context: BuildContextId, mode: UpdateMode, result: CachedResult): Boolean {
        val entry = entries[Key(context, mode)] ?: return false
        return entry.revision == revision(context) && entry.revision == result.revision &&
            entry.checkedAt == result.checkedAtNanos && entry.report === result.report && entry.expires > now()
    }

    @Synchronized fun retainedInspectionReport(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        val entry = entries[Key(snapshot.context, UpdateMode.MAJOR)]?.takeIf {
            it.revision == revision(snapshot.context) && it.expires > now() && it.report.successful
        } ?: return null
        return adapter.retainInspectionReport(entry.snapshot, entry.report, snapshot)
    }

    @Synchronized fun inspectionProgress(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        val entry = progress[snapshot.context]?.takeIf {
            it.revision == revision(snapshot.context) && it.expires > now()
        } ?: return null
        val report = entry.report()
        return if (entry.snapshot.fingerprint == snapshot.fingerprint && entry.snapshot.declarations == snapshot.declarations) report
        else adapter.retainInspectionReport(entry.snapshot, report, snapshot)
    }

    /** Status counts avoid allocating a complete partial report for every UI refresh. */
    @Synchronized fun inspectionProgressCounts(snapshot: BuildSnapshot): Triple<Int, Int, Long>? = progress[snapshot.context]?.takeIf {
        it.revision == revision(snapshot.context) && it.expires > now() &&
            it.snapshot.fingerprint == snapshot.fingerprint && it.snapshot.declarations == snapshot.declarations
    }?.let { entry -> entry.counts().let { Triple(it.first, it.second, entry.expires) } }

    @Synchronized fun putProgress(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, revision: Revision,
                                  owner: Any, update: InspectionUpdate): Boolean {
        if (revision != revision(snapshot.context)) return false
        val time = now()
        val active = progress[snapshot.context]?.takeIf { it.revision == revision && it.expires > time }
        val entry = if (active?.owner === owner && active.snapshot == snapshot) active else {
            val previous = entries[Key(snapshot.context, UpdateMode.MAJOR)]?.takeIf {
                it.revision == revision && it.expires > time && it.report.successful
            }
            val previousSnapshot = active?.snapshot ?: previous?.snapshot
            val previousReport = active?.report() ?: previous?.report
            val retained = previousSnapshot?.let {
                if (it.fingerprint == snapshot.fingerprint && it.declarations == snapshot.declarations) previousReport
                else adapter.retainInspectionReport(it, previousReport!!, snapshot)
            }
            ProgressEntry(snapshot, revision, owner,
                if (retained != null) active?.expires ?: previous!!.expires else time + TimeUnit.MINUTES.toNanos(10), retained)
        }
        val expires = minOf(entry.expires, update.report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        entry.expires = expires
        entry.add(update)
        sources[snapshot.context] = snapshot.sourceFile
        progress[snapshot.context] = entry
        // A newer partial check must not leave an older complete report authorizing edits.
        entries.remove(Key(snapshot.context, UpdateMode.MAJOR))
        return true
    }

    /** An older cancelled job must not erase a replacement check's progress. */
    @Synchronized fun clearProgress(context: BuildContextId, owner: Any): Boolean {
        if (progress[context]?.owner !== owner) return false
        progress.remove(context)
        return true
    }

    @Synchronized fun put(snapshot: BuildSnapshot, revision: Revision, report: UpdateReport, mode: UpdateMode = UpdateMode.MAJOR): Boolean {
        if (revision != revision(snapshot.context)) return false
        val time = now()
        val ttl = TimeUnit.MINUTES.toNanos(if (report.successful) 10 else 1)
        val expires = minOf(time + ttl, report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        sources[snapshot.context] = snapshot.sourceFile
        entries[Key(snapshot.context, mode)] = Entry(snapshot, revision, minOf(time, report.checkedAtNanos ?: time), expires, report)
        if (mode == UpdateMode.MAJOR) progress.remove(snapshot.context)
        return true
    }

    @Synchronized fun invalidate(context: BuildContextId? = null) {
        if (context == null) {
            global++
            entries.clear()
            generations.clear()
            adapters.clear()
            sources.clear()
            progress.clear()
        } else {
            generations[context] = (generations[context] ?: 0) + 1
            entries.keys.removeAll { it.context == context }
            progress.remove(context)
        }
    }

    @Synchronized fun invalidateAdapter(adapterId: String) {
        adapters[adapterId] = (adapters[adapterId] ?: 0) + 1
        entries.keys.removeAll { it.context.adapterId == adapterId }
        progress.keys.removeAll { it.adapterId == adapterId }
    }

    @Synchronized fun invalidateSource(adapterId: String, sourceFile: String) {
        sources.filter { (context, file) -> context.adapterId == adapterId && file == sourceFile }
            .keys.toList().forEach { invalidate(it) }
    }
}
