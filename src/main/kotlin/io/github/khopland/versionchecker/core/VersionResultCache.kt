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
    private var global = 0L
    private val adapters = mutableMapOf<String, Long>()
    private val generations = mutableMapOf<BuildContextId, Long>()
    private val entries = mutableMapOf<Key, Entry>()
    private val sources = mutableMapOf<BuildContextId, String>()

    @Synchronized fun revision(context: BuildContextId) = Revision(global, adapters[context.adapterId] ?: 0, generations[context] ?: 0)

    @Synchronized fun begin(snapshot: BuildSnapshot): Revision {
        sources[snapshot.context] = snapshot.sourceFile
        return revision(snapshot.context)
    }

    private fun entry(snapshot: BuildSnapshot, mode: UpdateMode): Entry? = entries[Key(snapshot.context, mode)]?.takeIf {
        it.snapshot.fingerprint == snapshot.fingerprint && it.snapshot.declarations == snapshot.declarations &&
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

    @Synchronized fun put(snapshot: BuildSnapshot, revision: Revision, report: UpdateReport, mode: UpdateMode = UpdateMode.MAJOR): Boolean {
        if (revision != revision(snapshot.context)) return false
        val time = now()
        val ttl = TimeUnit.MINUTES.toNanos(if (report.successful) 10 else 1)
        val expires = minOf(time + ttl, report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        sources[snapshot.context] = snapshot.sourceFile
        entries[Key(snapshot.context, mode)] = Entry(snapshot, revision, time, expires, report)
        return true
    }

    @Synchronized fun invalidate(context: BuildContextId? = null) {
        if (context == null) {
            global++
            entries.clear()
            generations.clear()
            adapters.clear()
            sources.clear()
        } else {
            generations[context] = (generations[context] ?: 0) + 1
            entries.keys.removeAll { it.context == context }
        }
    }

    @Synchronized fun invalidateAdapter(adapterId: String) {
        adapters[adapterId] = (adapters[adapterId] ?: 0) + 1
        entries.keys.removeAll { it.context.adapterId == adapterId }
    }

    @Synchronized fun invalidateSource(adapterId: String, sourceFile: String) {
        sources.filter { (context, file) -> context.adapterId == adapterId && file == sourceFile }
            .keys.toList().forEach { invalidate(it) }
    }
}
