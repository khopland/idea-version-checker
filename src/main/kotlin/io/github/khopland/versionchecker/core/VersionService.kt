package io.github.khopland.versionchecker.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Checks declarations. Each lookup runs once per key, and its result stays in memory for a while. */
@Service
class VersionService(private val scope: CoroutineScope) {
    private class Entry(val result: Deferred<Releases>, val createdAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Forget every cached version list, for example after the user asks to check again. */
    fun clear() = entries.clear()

    suspend fun releases(lookup: Lookup): Releases {
        val now = System.currentTimeMillis()
        val entry = entries.compute(lookup.key) { _, old ->
            if (old != null && !expired(old, now)) old
            else Entry(scope.async(Dispatchers.IO) { lookup.fetch() }, now)
        }!!
        return entry.result.await()
    }

    private fun expired(entry: Entry, now: Long): Boolean {
        val age = now - entry.createdAt
        if (entry.result.isCompleted && entry.result.getCompletionExceptionOrNull() != null) return age > FAILURE_TTL
        return age > TTL
    }

    suspend fun check(declarations: List<Declaration>, minAgeHours: Int = VersionCheckerSettings.getInstance().minReleaseAgeHours): Map<Declaration, Outcome> = coroutineScope {
        val cutoff = if (minAgeHours > 0) System.currentTimeMillis() - TimeUnit.HOURS.toMillis(minAgeHours.toLong()) else null
        val lookups = declarations.map { it.lookup }.distinctBy { it.key }
            .associate { lookup -> lookup.key to async { runCatching { releases(lookup) } } }
        declarations.associateWith { declaration ->
            async {
                lookups.getValue(declaration.lookup.key).await().fold(
                    onSuccess = { releases -> outcome(declaration.current, releases, cutoff) },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        LOG.info("Could not check ${declaration.name}", error)
                        Outcome.Failed(error.message ?: error.javaClass.simpleName)
                    })
            }
        }.mapValues { it.value.await() }
    }

    companion object {
        private val LOG = logger<VersionService>()
        private val TTL = TimeUnit.MINUTES.toMillis(30)
        private val FAILURE_TTL = TimeUnit.MINUTES.toMillis(1)

        fun getInstance(): VersionService = ApplicationManager.getApplication().getService(VersionService::class.java)

        suspend fun outcome(current: String, releases: Releases, minimumAgeCutoff: Long?): Outcome.Updates {
            val cutoff = minimumAgeCutoff.takeUnless { releases.ignoresMinimumAge }
            val targets = Targets.pick(current, releases.versions) { version ->
                cutoff == null || releases.allPublishedBefore?.let { it <= cutoff } == true ||
                    releases.publishedAt(version)?.let { it <= cutoff } == true
            }
            return Outcome.Updates(targets, releases.deprecated[current])
        }

        /**
         * The new version each mode may write for each declaration. Declarations that share one edit,
         * such as a Maven property, may change only when they all want the same version.
         */
        fun fixes(outcomes: Map<Declaration, Outcome>): Map<Declaration, Map<UpdateKind, String>> {
            val targets = outcomes.mapValues { (_, outcome) -> (outcome as? Outcome.Updates)?.targets }
            val groups = outcomes.keys.filter { it.edit != null }.groupBy { it.edit!!.key }
            return outcomes.keys.associateWith { declaration ->
                val edit = declaration.edit ?: return@associateWith emptyMap()
                val group = groups.getValue(edit.key)
                UpdateKind.entries.mapNotNull { mode ->
                    val wanted = group.map { targets[it]?.get(mode) }.distinct()
                    wanted.singleOrNull()?.let { mode to it }
                }.toMap()
            }
        }
    }
}

/** Publication times never change, so they stay on disk. */
@Service
class PublishedTimes {
    private val file: Path = PathManager.getSystemDir().resolve("version-checker").resolve("published.tsv")
    private val times: ConcurrentHashMap<String, Long> by lazy {
        val map = ConcurrentHashMap<String, Long>()
        runCatching {
            if (Files.isRegularFile(file)) Files.readAllLines(file).forEach { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { map[line.substring(0, tab)] = it }
            }
        }
        map
    }

    operator fun get(key: String): Long? = times[key]

    @Synchronized
    fun put(key: String, time: Long) {
        if (times.putIfAbsent(key, time) != null || '\n' in key) return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, "$key\t$time\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    companion object {
        fun getInstance(): PublishedTimes = ApplicationManager.getApplication().getService(PublishedTimes::class.java)
    }
}
