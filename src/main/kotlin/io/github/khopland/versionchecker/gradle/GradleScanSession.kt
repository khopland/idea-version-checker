package io.github.khopland.versionchecker.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.khopland.versionchecker.UpdateMode
import io.github.khopland.versionchecker.core.BuildSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Results live only inside one scan. No sharing across modes, Refresh generations or linked builds. */
internal class GradleScanSession(
    scope: CoroutineScope,
    snapshots: List<BuildSnapshot>,
    private val mode: UpdateMode,
    private val generation: () -> Long,
    private val now: () -> Long = System::nanoTime,
    load: suspend (List<BuildSnapshot>) -> Map<BuildSnapshot, Map<String, GradleVersionLookup.Result>>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GradleScanSession> {
        const val MAX_FILES = 32
        const val MAX_DECLARATIONS = 2048

        /** Bound the work before yielding to another file/interactive request in the shared queue. */
        internal fun batches(snapshots: List<BuildSnapshot>): List<List<BuildSnapshot>> =
            snapshots.distinct().groupBy { it.context.root to it.fingerprint }.values.flatMap { compatible ->
                val batches = mutableListOf<List<BuildSnapshot>>()
                var batch = mutableListOf<BuildSnapshot>()
                var declarations = 0
                for (snapshot in compatible) {
                    if (batch.isNotEmpty() && (batch.size == MAX_FILES || declarations + snapshot.declarations.size > MAX_DECLARATIONS)) {
                        batches += batch
                        batch = mutableListOf()
                        declarations = 0
                    }
                    batch += snapshot
                    declarations += snapshot.declarations.size
                }
                if (batch.isNotEmpty()) batches += batch
                batches
            }
    }

    private val originalGeneration = generation()
    @Volatile private var closed = false
    private data class CheckedBatch(val results: Map<BuildSnapshot, Map<String, GradleVersionLookup.Result>>, val checkedAtNanos: Long)
    private val results = batches(snapshots).flatMap { batch ->
        val lookup = scope.async(start = CoroutineStart.LAZY) {
            val started = now()
            CheckedBatch(load(batch), started)
        }
        batch.map { it to lookup }
    }.toMap()

    suspend fun check(snapshot: BuildSnapshot, mode: UpdateMode): GradleVersionLookup.Checked? {
        currentCoroutineContext().ensureActive()
        if (closed || mode != this.mode || generation() != originalGeneration) return null
        val lookup = results[snapshot] ?: return null
        val checked = try { lookup.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cancelled: ProcessCanceledException) { throw cancelled }
            // One failing source must not hide independently checkable files in the same batch.
            // The caller falls back to the original native per-file path.
            catch (_: Exception) { return null }
        currentCoroutineContext().ensureActive()
        // A Refresh while the native task ran invalidates every remaining result in this session.
        return if (!closed && generation() == originalGeneration) checked.results[snapshot]?.let {
            GradleVersionLookup.Checked(it, checked.checkedAtNanos).takeIf { result -> result.expiresAtNanos > now() }
        } else null
    }

    fun close() {
        closed = true
        results.values.toSet().forEach { it.cancel() }
    }
}

internal suspend fun <T> withGradleScanSession(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode,
                                            action: suspend () -> T): T = supervisorScope {
    val inputs = project.service<GradleProjectCache>()
    val session = GradleScanSession(this, snapshots, mode, { inputs.metadataGeneration }) {
        GradleVersionLookup.checkBatch(project, it, mode)
    }
    try { withContext(session) { action() } } finally { session.close() }
}
