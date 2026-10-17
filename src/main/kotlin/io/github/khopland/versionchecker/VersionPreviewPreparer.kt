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
    val coverage: CoverageSummary? = null
)

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
        forceRefresh: Boolean
    ): PreparedVersionPreview {
        check(adapters.isNotEmpty()) { "Open a supported build file to update its versions" }
        val service = project.service<VersionCheckService>()
        val results = mutableListOf<Pair<BuildSnapshot, VersionResultCache.CachedResult>>()
        val coverage = DeclarationCoverageAccumulator()
        val ignoredPolicy = project.service<VersionCheckerSettings>().state.ignoredVersions
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
                    report.withoutIgnored(adapter.id, project.service<VersionCheckerSettings>().state).also { filtered ->
                        coverage.add(snapshot, filtered)
                    }
                }
            }
            CheckPerformance.measure(CheckPerformance.Stage.PREVIEW_PLAN, reports.size) { adapter.prepareUpdates(project, reports) }
        }
        val plan = BulkUpdatePlan.combine(plans).guardedBy {
            project.service<VersionCheckerSettings>().state.enabled &&
                project.service<VersionCheckerSettings>().state.ignoredVersions == ignoredPolicy &&
                results.all { (snapshot, result) -> service.isCachedResultCurrent(snapshot, mode, result) }
        }
        return PreparedVersionPreview(plan, results.minOf { it.second.checkedAtNanos }, reused, results.size,
            coverage.summary())
    }
}
