package io.github.khopland.versionchecker.core

import java.util.concurrent.TimeUnit

/** Provider/context isolation and refresh generations do not depend on a build system or the IDE. */
internal class VersionResultCache(private val now: () -> Long = System::nanoTime) {
    data class Revision(val global: Long, val adapter: Long, val context: Long)
    private data class Entry(val sourceFile: String, val fingerprint: BuildFingerprint,
                             val declarations: List<VersionDeclaration>, val revision: Revision,
                             val expires: Long, val report: UpdateReport)
    private var global = 0L
    private val adapters = mutableMapOf<String, Long>()
    private val generations = mutableMapOf<BuildContextId, Long>()
    private val entries = mutableMapOf<BuildContextId, Entry>()
    private val sources = mutableMapOf<BuildContextId, String>()

    @Synchronized fun revision(context: BuildContextId) = Revision(global, adapters[context.adapterId] ?: 0, generations[context] ?: 0)

    @Synchronized fun begin(snapshot: BuildSnapshot): Revision {
        sources[snapshot.context] = snapshot.sourceFile
        return revision(snapshot.context)
    }

    @Synchronized fun get(snapshot: BuildSnapshot): UpdateReport? = entries[snapshot.context]?.takeIf {
        it.fingerprint == snapshot.fingerprint && it.declarations == snapshot.declarations &&
            it.revision == revision(snapshot.context) && it.expires > now()
    }?.report

    @Synchronized fun put(snapshot: BuildSnapshot, revision: Revision, report: UpdateReport): Boolean {
        if (revision != revision(snapshot.context)) return false
        val time = now()
        val ttl = TimeUnit.MINUTES.toNanos(if (report.successful) 10 else 1)
        val expires = minOf(time + ttl, report.validUntilNanos ?: Long.MAX_VALUE)
        if (expires <= time) return false
        sources[snapshot.context] = snapshot.sourceFile
        entries[snapshot.context] = Entry(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, revision, expires, report)
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
            entries.remove(context)
        }
    }

    @Synchronized fun invalidateAdapter(adapterId: String) {
        adapters[adapterId] = (adapters[adapterId] ?: 0) + 1
        entries.keys.removeAll { it.adapterId == adapterId }
    }

    @Synchronized fun invalidateSource(adapterId: String, sourceFile: String) {
        sources.filter { (context, file) -> context.adapterId == adapterId && file == sourceFile }
            .keys.toList().forEach { invalidate(it) }
    }
}
