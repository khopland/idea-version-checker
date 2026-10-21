package io.github.khopland.versionchecker

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.*

internal data class PreparedVersionPreview @JvmOverloads constructor(
    val plan: BulkUpdatePlan,
    val checkedAtNanos: Long,
    val reusedReports: Int,
    val totalReports: Int,
    val coverage: CoverageSummary? = null,
    val targetPlans: Map<UpdateMode, BulkUpdatePlan> = emptyMap(),
    /** Earliest actual result lease deadline; observation age cannot establish this value. */
    val expiresAtNanos: Long? = null
) {
    fun withTargetChoices(choices: Map<UpdateMode, PreparedVersionPreview>): PreparedVersionPreview {
        val loaded = choices.values + this
        return copy(
            checkedAtNanos = loaded.minOf { it.checkedAtNanos },
            targetPlans = choices.mapValues { it.value.plan },
            expiresAtNanos = loaded.takeIf { previews -> previews.all { it.expiresAtNanos != null } }
                ?.minOf { requireNotNull(it.expiresAtNanos) })
    }
}

/**
 * Collects provider plans and binds their preview to the observed results and settings.
 * UI presentation and applying edits belong to the caller; build semantics stay in adapters.
 */
internal class VersionPreviewPreparer(private val project: Project) {
    suspend fun prepare(
        mode: UpdateMode,
        updateScope: UpdateScope,
        currentFile: VirtualFile?,
        adapters: List<BuildSystemAdapter>,
        forceRefresh: Boolean,
        includeTargetChoices: Boolean = false
    ): PreparedVersionPreview {
        if (includeTargetChoices) {
            val primary = prepare(mode, updateScope, currentFile, adapters, forceRefresh)
            val narrower = UpdateMode.entries.filter { candidate ->
                candidate.ordinal < mode.ordinal && adapters.all { candidate in it.capabilities.updateModes }
            }.associateWith { candidate -> prepare(candidate, updateScope, currentFile, adapters, false) }
            return primary.withTargetChoices(narrower)
        }
        check(adapters.isNotEmpty()) { "Open a supported build file to update its versions" }
        val service = project.service<VersionCheckService>()
        val results = mutableListOf<Pair<BuildSnapshot, VersionResultCache.CachedResult>>()
        val coverage = DeclarationCoverageAccumulator()
        val policy = projectPolicy(project)
        check(policy.shared.diagnostic == null) { policy.shared.diagnostic.orEmpty() }
        var reused = 0
        val plans = adapters.map { adapter ->
            check(!adapter.isOffline(project)) { "${adapter.displayName} is offline" }
            check(mode in adapter.capabilities.updateModes) { "${adapter.displayName} does not support ${mode.label}" }
            if (forceRefresh) service.invalidateForPreview(adapter, if (updateScope == UpdateScope.CURRENT_FILE) currentFile else null)
            val snapshots = adapter.discover(project, BuildSelection(updateScope, currentFile?.path))
            check(snapshots.isNotEmpty()) { "No supported build files found for ${adapter.displayName}" }
            val unchecked = snapshots.filter { service.cached(it, mode)?.successful != true }
            val reports = adapter.withScan(project, unchecked, mode) {
                snapshots.associateWith { snapshot ->
                    if (service.cached(snapshot, mode)?.successful == true) reused++
                    val report = service.checkNow(adapter, snapshot, mode, reuseCached = true)
                    val result = service.cachedResult(snapshot, mode) ?: error("Repository results expired. Refresh version checks to retry.")
                    check(result.report === report) { "Version results changed during preparation. Refresh the preview." }
                    results += snapshot to result
                    VersionPresentationPolicy(adapter.id, policy.ignoredText, policy).filter(report).also { filtered ->
                        coverage.add(snapshot, filtered)
                    }
                }
            }
            CheckPerformance.measure(CheckPerformance.Stage.PREVIEW_PLAN, reports.size) { adapter.prepareUpdates(project, reports) }
        }
        val plan = BulkUpdatePlan.combine(plans).withProjectPolicy(project, policy).guardedBy {
            project.service<VersionCheckerSettings>().state.enabled &&
                policy.isCurrent(project) &&
                results.all { (snapshot, result) -> service.isCachedResultCurrent(snapshot, mode, result) }
        }
        return PreparedVersionPreview(plan, results.minOf { it.second.checkedAtNanos }, reused, results.size,
            coverage.summary(), expiresAtNanos = results.minOf { it.second.expiresAtNanos })
    }
}
