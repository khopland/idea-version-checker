package io.github.khopland.versionchecker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Executes checks, serializes provider work and owns the authority to publish cached results.
 * Project discovery, refresh scheduling and editor subscriptions remain in [VersionCheckService]. */
internal class CheckExecutor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val statusChanged: (String?) -> Unit
) : Disposable {
    private data class ScanToken(val sourceFile: String, val fingerprint: BuildFingerprint, val declarations: List<VersionDeclaration>, val revision: VersionResultCache.Revision)
    private data class PendingScan(val token: ScanToken, val job: Job? = null)
    private data class StatusCheck(val context: BuildContextId, val token: ScanToken, val running: Boolean)
    private val cache = VersionResultCache()
    private val pending = ConcurrentHashMap<BuildContextId, PendingScan>()
    private val statusChecks = ConcurrentHashMap<Any, StatusCheck>()
    private val scanQueues = ConcurrentHashMap<String, CheckQueue>()
    @Volatile private var selectedPaths = emptySet<String>()
    @Volatile private var disposed = false
    private val log = Logger.getInstance(CheckExecutor::class.java)

    override fun dispose() {
        disposed = true
        pause()
    }

    internal fun pause() {
        cache.invalidate()
        pending.values.forEach { it.job?.cancel() }
        pending.clear()
    }

    fun invalidate(adapterId: String, path: String? = null) {
        if (path == null) {
            cache.invalidateAdapter(adapterId)
            pending.keys.filter { it.adapterId == adapterId }.forEach { pending.remove(it)?.job?.cancel() }
        } else {
            cache.invalidateSource(adapterId, path)
            pending.entries.filter { it.key.adapterId == adapterId && it.value.token.sourceFile == path }
                .forEach { (context, scan) -> if (pending.remove(context, scan)) scan.job?.cancel() }
        }
    }

    val isBusy: Boolean get() = pending.isNotEmpty() || scanQueues.values.any { it.isBusy }

    fun select(paths: Set<String>) { selectedPaths = paths }

    suspend fun scanRefresh(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, interactive: Boolean,
                            failures: VersionCheckFailures): Boolean {
        if (disposed || project.isDisposed) return false
        val token = token(snapshot)
        pending.putIfAbsent(snapshot.context, PendingScan(token))
        statusChanged(snapshot.sourceFile)
        return scan(adapter, snapshot, token, interactive = interactive, failures = failures)
    }

    private fun queue(adapterId: String) = scanQueues.computeIfAbsent(adapterId) { CheckQueue { path -> path in selectedPaths } }
    internal fun queuedChecks(adapterId: String) = scanQueues[adapterId]?.waitingCount ?: 0

    internal fun observations(): List<VersionResultCache.Observation> = if (disposed) emptyList() else cache.observations()

    internal fun cached(snapshot: BuildSnapshot, mode: UpdateMode = UpdateMode.MAJOR): UpdateReport? = cache.get(snapshot, mode)
    internal fun cachedResult(snapshot: BuildSnapshot, mode: UpdateMode) = cache.getResult(snapshot, mode)
    internal fun isCachedResultCurrent(snapshot: BuildSnapshot, mode: UpdateMode, result: VersionResultCache.CachedResult) =
        cache.isCurrent(snapshot.context, mode, result)

    /** Passive, called under a background read action. Retained hints never mean a completed check. */
    internal fun status(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): VersionCheckStatus {
        if (!project.service<VersionCheckerSettings>().state.enabled) return VersionCheckStatus(CheckPhase.PAUSED)
        if (adapter.isOffline(project)) return VersionCheckStatus(CheckPhase.OFFLINE)
        if (!adapter.canCheckInBackground(project, snapshot)) return VersionCheckStatus(CheckPhase.SAVE_REQUIRED)
        projectPolicy(project).shared.diagnostic?.let { return VersionCheckStatus(CheckPhase.FAILED, recoveryMessage = it) }
        val revision = cache.revision(snapshot.context)
        fun ScanToken.matches() = sourceFile == snapshot.sourceFile && fingerprint == snapshot.fingerprint &&
            declarations == snapshot.declarations && this.revision == revision
        val checks = statusChecks.values.filter { it.context == snapshot.context && it.token.matches() }
        val phase = when {
            checks.any { it.running } -> CheckPhase.CHECKING
            checks.isNotEmpty() || pending[snapshot.context]?.token?.matches() == true -> CheckPhase.QUEUED
            else -> null
        }
        val result = cache.getResult(snapshot, UpdateMode.MAJOR)
        val presentation = projectPolicy(project).let { VersionPresentationPolicy(adapter.id, it.ignoredText, it) }
        if (phase != null) {
            val counts = result?.let { Triple(presentation.count(it.report), it.report.notices.size, it.expiresAtNanos) }
                ?: cache.inspectionProgressCounts(snapshot, presentation.candidateFilter)
            return VersionCheckStatus(phase, counts?.first ?: 0, counts?.second ?: 0, counts?.third,
                coverage = cache.inspectionProgressCoverage(snapshot, presentation.candidateFilter))
        }
        if (result != null) return VersionCheckStatus(if (!result.report.successful) CheckPhase.FAILED else
            if (snapshot.coverageDescription != null) CheckPhase.PARTIAL else CheckPhase.CHECKED,
            presentation.count(result.report), result.report.notices.size, result.expiresAtNanos, snapshot.coverageDescription,
            (result.report.failureCause as? VersionCheckFailureAdvice)?.recoveryMessage,
            presentation.coverageSummary(result.report, snapshot))
        return VersionCheckStatus(CheckPhase.UNCHECKED)
    }

    internal fun updates(adapter: BuildSystemAdapter, snapshot: BuildSnapshot): UpdateReport? {
        if (disposed || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project)) return null
        val entry = cache.get(snapshot)
        if (entry == null && adapter.canCheckInBackground(project, snapshot)) schedule(adapter, snapshot)
        return cache.inspectionReport(adapter, snapshot)
            ?.withProjectPolicy(adapter.id, project)
    }

    internal suspend fun checkNow(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                  reuseCached: Boolean = false): UpdateReport = CheckPerformance.traced(
        CheckPerformance.current() ?: CheckPerformance.start(CheckPerformance.Stage.INTERACTION_STARTED)) {
        checkNowTraced(adapter, snapshot, mode, reuseCached)
    }

    private suspend fun checkNowTraced(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                  reuseCached: Boolean = false): UpdateReport {
        check(!disposed && !project.isDisposed) { "Version checks are no longer available." }
        check(project.service<VersionCheckerSettings>().state.enabled) { "Version checks are disabled." }
        if (reuseCached) {
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(adapter.isCurrentInBackground(project, snapshot)) { "Build files or settings changed. Run the check again." }
            cache.get(snapshot, mode)?.takeIf { it.successful }?.let { return it }
        }
        val queued = System.nanoTime()
        val waiting = Any()
        statusChecks[waiting] = StatusCheck(snapshot.context, token(snapshot), running = false)
        statusChanged(snapshot.sourceFile)
        try {
            return queue(adapter.id).withSlot(snapshot.sourceFile, interactive = true) {
                statusChecks.remove(waiting)
                statusChanged(snapshot.sourceFile)
                CheckPerformance.record(CheckPerformance.Stage.CHECK_QUEUE, queued)
                check(!disposed && !project.isDisposed) { "Version checks are no longer available." }
                check(project.service<VersionCheckerSettings>().state.enabled) { "Version checks are disabled." }
                check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
                check(adapter.isCurrentInBackground(project, snapshot)) { "Build files or settings changed. Run the check again." }
                if (reuseCached) cache.get(snapshot, mode)?.takeIf { it.successful }?.let { return@withSlot it }
                val revision = cache.begin(snapshot)
                runCheck(adapter, snapshot, mode, revision) { result ->
                    check(!disposed && !project.isDisposed) { "Version checks are no longer available." }
                    check(project.service<VersionCheckerSettings>().state.enabled) { "Version checks were disabled during this check." }
                    check(adapter.isCurrentInBackground(project, snapshot)) { "Build files or settings changed during the check. Run it again." }
                    check(cache.revision(snapshot.context) == revision) { "Version checks were refreshed during this check. Run it again." }
                    check(cache.put(snapshot, revision, result, mode)) { "Repository results expired during this check. Run it again." }
                    CheckPerformance.record(CheckPerformance.Stage.RESULT_ACCEPTED, System.nanoTime(), result.candidates.size + result.notices.size)
                    if (mode == UpdateMode.MAJOR && !project.isDisposed) project.service<FileProblemRefresh>().request(snapshot.sourceFile)
                    if (!result.successful) throw result.failureCause ?: IllegalStateException(result.failure.orEmpty())
                    result
                }
            }
        } finally {
            statusChecks.remove(waiting)
            statusChanged(snapshot.sourceFile)
        }
    }

    private suspend fun <T> runCheck(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, mode: UpdateMode,
                                    revision: VersionResultCache.Revision, finish: suspend (UpdateReport) -> T): T {
        val owner = Any()
        val started = System.nanoTime()
        val first = AtomicBoolean()
        statusChecks[owner] = StatusCheck(snapshot.context,
            ScanToken(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, revision), running = true)
        statusChanged(snapshot.sourceFile)
        try {
            val report = CheckPerformance.measure(CheckPerformance.Stage.CHECK) {
                if (mode != UpdateMode.MAJOR || !adapter.capabilities.incrementalInspections)
                    return@measure adapter.check(project, snapshot, mode)
                adapter.checkIncrementally(project, snapshot, mode) { update ->
                    val valid = adapter.isCurrentInBackground(project, snapshot) && readAction {
                        !disposed && !project.isDisposed && project.service<VersionCheckerSettings>().state.enabled &&
                            !adapter.isOffline(project)
                    }
                    if (valid && cache.putProgress(adapter, snapshot, revision, owner, update)) {
                        val useful = update.report.candidates.size + update.report.notices.size
                        if (useful > 0 && first.compareAndSet(false, true))
                            CheckPerformance.record(CheckPerformance.Stage.FIRST_INSPECTION_RESULT, started, useful)
                        project.service<FileProblemRefresh>().request(snapshot.sourceFile)
                        statusChanged(snapshot.sourceFile)
                    }
                }
            }
            val completed = finish(report)
            val useful = report.candidates.size + report.notices.size
            if (useful > 0 && cache.get(snapshot, mode) === report && first.compareAndSet(false, true))
                CheckPerformance.record(CheckPerformance.Stage.FIRST_INSPECTION_RESULT, started, useful)
            return completed
        } finally {
            statusChecks.remove(owner)
            statusChanged(snapshot.sourceFile)
            if (cache.clearProgress(snapshot.context, owner) && !disposed && !project.isDisposed)
                project.service<FileProblemRefresh>().request(snapshot.sourceFile)
        }
    }

    private fun token(snapshot: BuildSnapshot) = ScanToken(snapshot.sourceFile, snapshot.fingerprint, snapshot.declarations, cache.begin(snapshot))

    private fun schedule(adapter: BuildSystemAdapter, snapshot: BuildSnapshot) {
        val token = token(snapshot)
        var job: Job? = null
        var superseded: Job? = null
        pending.compute(snapshot.context) { _, active ->
            if (active?.token == token) active else {
                superseded = active?.job
                val scheduled = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    scan(adapter, snapshot, token, showProgress = true)
                }
                job = scheduled
                PendingScan(token, scheduled)
            }
        }
        superseded?.cancel()
        job?.let { scheduled ->
            // A lazy job cancelled before it starts never enters scan's finally block.
            scheduled.invokeOnCompletion {
                pending.computeIfPresent(snapshot.context) { _, active -> if (active.job === scheduled) null else active }
                statusChanged(snapshot.sourceFile)
            }
            scheduled.start()
            statusChanged(snapshot.sourceFile)
        }
    }

    private suspend fun scan(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, token: ScanToken, showProgress: Boolean = false,
                             interactive: Boolean = false, failures: VersionCheckFailures? = null): Boolean {
        val interaction = if (CheckPerformance.enabled()) kotlinx.coroutines.withContext(Dispatchers.EDT) {
            project.service<DiagnosticVisibilityTrace>().interaction(snapshot.sourceFile)
        } ?: CheckPerformance.current() ?: CheckPerformance.start(CheckPerformance.Stage.INTERACTION_STARTED) else null
        return CheckPerformance.traced(interaction) { scanTraced(adapter, snapshot, token, showProgress, interactive, failures) }
    }

    private suspend fun scanTraced(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, token: ScanToken, showProgress: Boolean = false,
                             interactive: Boolean = false, failures: VersionCheckFailures? = null): Boolean {
        var published = false
        val queued = System.nanoTime()
        try {
            queue(adapter.id).withSlot(snapshot.sourceFile, interactive) {
                CheckPerformance.record(CheckPerformance.Stage.CHECK_QUEUE, queued)
                if (disposed || project.isDisposed || !project.service<VersionCheckerSettings>().state.enabled || adapter.isOffline(project) ||
                    cache.revision(snapshot.context) != token.revision || cache.get(snapshot) != null) return@withSlot
                if (!adapter.isCurrentInBackground(project, snapshot) ||
                    !readAction { !project.isDisposed && adapter.canCheckInBackground(project, snapshot) }) return@withSlot
                val finish: suspend (UpdateReport) -> Unit = { report ->
                    if (!disposed && !project.isDisposed && project.service<VersionCheckerSettings>().state.enabled &&
                        adapter.isCurrentInBackground(project, snapshot) && cache.put(snapshot, token.revision, report)) {
                        published = true
                        CheckPerformance.record(CheckPerformance.Stage.RESULT_ACCEPTED, System.nanoTime(), report.candidates.size + report.notices.size)
                        if (!report.successful) recordFailure(adapter, snapshot, report.failure.orEmpty(), report.failureCause, failures)
                        else project.getServiceIfCreated(VersionCheckFeedback::class.java)
                            ?.checked(VersionCheckTarget(adapter.id, snapshot.sourceFile))
                    }
                }
                if (showProgress) {
                    withBackgroundProgress(project, "Checking ${adapter.displayName} dependency versions", cancellable = true) {
                        runCheck(adapter, snapshot, UpdateMode.MAJOR, token.revision, finish)
                    }
                } else runCheck(adapter, snapshot, UpdateMode.MAJOR, token.revision, finish)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("${adapter.displayName} version check failed for ${snapshot.sourceFile}", failure)
            val completedCoverage = cache.inspectionProgress(adapter, snapshot)?.coverage.orEmpty()
            if (!disposed && !project.isDisposed && project.service<VersionCheckerSettings>().state.enabled && cache.put(snapshot, token.revision, UpdateReport(failure = failure.message ?: failure.javaClass.simpleName,
                    failureCause = failure, coverage = snapshot.skippedCoverage() + snapshot.declarations.filter {
                        it.id !in snapshot.skippedDeclarations
                    }.associate { it.id to (completedCoverage[it.id]
                        ?: DeclarationCoverage(DeclarationOutcome.FAILED, "Check stopped before a usable result was obtained.")) }))) {
                published = true
                recordFailure(adapter, snapshot, failure.message ?: failure.javaClass.simpleName, failure, failures)
            }
        } finally {
            pending.computeIfPresent(snapshot.context) { _, active -> if (active.token == token) null else active }
            statusChanged(snapshot.sourceFile)
            if (published && !project.isDisposed) project.service<FileProblemRefresh>().request(snapshot.sourceFile)
        }
        return published
    }

    private fun recordFailure(adapter: BuildSystemAdapter, snapshot: BuildSnapshot, message: String, cause: Exception?,
                              failures: VersionCheckFailures?) {
        val target = VersionCheckTarget(adapter.id, snapshot.sourceFile)
        if (failures != null) failures.add(target, adapter.displayName, message, cause)
        else if (!disposed && !project.isDisposed)
            project.service<VersionCheckFeedback>().automaticFailure(target, adapter.displayName, message, cause)
    }

}
