package io.github.khopland.versionchecker.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import io.github.khopland.versionchecker.VersionCheckerSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Checks declarations. Each lookup runs once per key, and its result stays in memory for a while. So does the outcome for
 * each version, which runs to its end even when the caller stops waiting, so the next caller finds it.
 */
@Service
class VersionService(private val scope: CoroutineScope) {
    private class Entry<T>(val result: Deferred<T>, val createdAt: Long)

    private val entries = ConcurrentHashMap<String, Entry<Releases>>()
    private val outcomes = ConcurrentHashMap<String, Entry<Outcome>>()
    private val waiters = ConcurrentHashMap<Any, Job>()

    /** Replaced in tests, so they need no network. */
    @Volatile
    internal var fetch: suspend (Lookup) -> Releases = { it.fetch() }

    /**
     * How long an editor waits for slow repositories before it shows the versions it has. The others show when they
     * arrive. Null waits for all of them, as tests do.
     */
    @Volatile
    internal var patience: Duration? = if (ApplicationManager.getApplication().isUnitTestMode) null else 1.seconds

    /** Forget every cached version list, for example after the user asks to check again. */
    fun clear() {
        entries.clear()
        outcomes.clear()
    }

    suspend fun releases(lookup: Lookup): Releases {
        val now = System.currentTimeMillis()
        val entry = entries.compute(lookup.key) { _, old ->
            if (old != null && !expired(old.result, old.createdAt, now)) old
            else Entry(scope.async(Dispatchers.IO) { fetch(lookup) }, now)
        }!!
        return entry.result.await()
    }

    private fun outcome(declaration: Declaration, minAgeHours: Int): Deferred<Outcome> {
        val now = System.currentTimeMillis()
        val key = listOf(declaration.lookup.key, declaration.current, declaration.checked, minAgeHours).joinToString("\n")
        return outcomes.compute(key) { _, old ->
            if (old != null && !expired(old.result, old.createdAt, now) { it is Outcome.Failed }) old
            else Entry(scope.async {
                try {
                    outcome(declaration.current, releases(declaration.lookup), cutoff(minAgeHours), declaration.checked)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.info("Could not check ${declaration.name}", e)
                    Outcome.Failed(e.message ?: e.javaClass.simpleName)
                }
            }, now)
        }!!.result
    }

    /**
     * With [patience], a version that is not checked within it is [Outcome.Pending], and its check goes on:
     * [whenMore] tells when to ask again.
     */
    suspend fun check(declarations: List<Declaration>, minAgeHours: Int = VersionCheckerSettings.getInstance().minReleaseAgeHours,
                      patience: Duration? = null): Map<Declaration, Outcome> {
        val results = declarations.associateWith { outcome(it, minAgeHours) }
        if (patience != null) withTimeoutOrNull(patience) { results.values.joinAll() }
        return results.mapValues { (_, result) -> if (patience == null || result.isCompleted) result.await() else Outcome.Pending }
    }

    /**
     * Calls [then] once more of the [pending] declarations are checked: after the first of them, with those that follow
     * within a moment, so the editor is not painted for each one. A call with the same [owner] replaces one that still waits.
     */
    fun whenMore(owner: Any, pending: List<Declaration>, minAgeHours: Int = VersionCheckerSettings.getInstance().minReleaseAgeHours,
                 then: suspend () -> Unit) {
        val checks = pending.map { outcome(it, minAgeHours) }
        val waiter = scope.launch(start = CoroutineStart.LAZY) {
            select { checks.forEach { check -> check.onJoin {} } }
            withTimeoutOrNull(BATCH) { checks.joinAll() }
            then()
        }
        waiters.put(owner, waiter)?.cancel()
        waiter.invokeOnCompletion { waiters.remove(owner, waiter) }
        waiter.start()
    }

    companion object {
        private val LOG = logger<VersionService>()
        private val TTL = TimeUnit.MINUTES.toMillis(30)
        private val FAILURE_TTL = TimeUnit.MINUTES.toMillis(1)
        private val BATCH = 1.seconds

        /**
         * Whether a cached [result] from [createdAt] is too old at [now]. A failure is asked for again soon, and so is a
         * result that [incomplete] says is missing something.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun <T> expired(result: Deferred<T>, createdAt: Long, now: Long, incomplete: (T) -> Boolean = { false }): Boolean {
            val failed = result.isCompleted && (result.getCompletionExceptionOrNull() != null || incomplete(result.getCompleted()))
            return now - createdAt > if (failed) FAILURE_TTL else TTL
        }

        fun getInstance(): VersionService = ApplicationManager.getApplication().getService(VersionService::class.java)

        /** The newest publication time the minimum release age allows, or null when it is off. */
        fun cutoff(minAgeHours: Int): Long? = if (minAgeHours > 0) System.currentTimeMillis() - TimeUnit.HOURS.toMillis(minAgeHours.toLong()) else null

        suspend fun oldEnough(releases: Releases, minimumAgeCutoff: Long?, version: String): Boolean {
            val cutoff = minimumAgeCutoff.takeUnless { releases.ignoresMinimumAge } ?: return true
            return releases.allPublishedBefore?.let { it <= cutoff } == true || releases.publishedAt(version)?.let { it <= cutoff } == true
        }

        /** [checked] is the version a lockfile installs, when it is not [current]. */
        suspend fun outcome(current: String, releases: Releases, minimumAgeCutoff: Long?, checked: String = current): Outcome.Updates {
            val targets = Targets.pick(current, releases.versions) { oldEnough(releases, minimumAgeCutoff, it) }
            val needs = if (releases.needs.isEmpty()) emptyMap() else
                (UpdateKind.entries.mapNotNull { targets[it] } + checked).mapNotNull { version -> releases.needs[version]?.let { version to it } }.toMap()
            return Outcome.Updates(targets, releases.deprecated[current], releases.moved, needs)
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
