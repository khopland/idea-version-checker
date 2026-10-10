package io.github.khopland.versionchecker.core

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.BulkUpdatePlan
import io.github.khopland.versionchecker.UpdateMode

/** Internal adapter contract. All discovery/PSI/repository semantics remain in the adapter. */
internal interface BuildSystemAdapter {
    val id: String
    val displayName: String
    val capabilities: AdapterCapabilities
    fun supports(project: Project, selection: BuildSelection): Boolean
    fun isOffline(project: Project): Boolean
    /** Called on EDT before discovery; includes sibling/configuration inputs needed by native tools. */
    fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> = emptySet()
    fun snapshot(project: Project, file: VirtualFile): BuildSnapshot?
    /** Inspections may retain warnings from unsaved editor text while native checks wait for save. */
    fun inspectionSnapshot(project: Project, file: VirtualFile): BuildSnapshot? = snapshot(project, file)
    fun canCheckInBackground(project: Project, snapshot: BuildSnapshot): Boolean = true
    fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean
    suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot>
    /** Provider-owned, short-lived native resources/results for one ordered scan. */
    suspend fun <T> withScan(project: Project, snapshots: List<BuildSnapshot>, mode: UpdateMode,
                            action: suspend () -> T): T = action()
    suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport
    /** Deltas can display individually checked hints; only the final return value authorizes a preview. */
    suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                   publish: suspend (InspectionUpdate) -> Unit): UpdateReport = check(project, snapshot, mode)
    /** Presentation only; retained warnings must never satisfy a fresh check or bulk-preview guard. */
    fun retainInspectionReport(previous: BuildSnapshot, report: UpdateReport, current: BuildSnapshot): UpdateReport? = null
    /** Called under a read action; timers must not resolve against files whose edits are unsaved. */
    fun hasUnsavedResolutionInputs(project: Project, snapshot: BuildSnapshot, unsavedPaths: Set<String>): Boolean =
        snapshot.fingerprint.files.keys.any { it in unsavedPaths }
    /** Start a fresh native metadata generation before a manual/scheduled scan or bulk preview. */
    fun invalidateMetadata(project: Project) = Unit
    suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan

    companion object {
        val EP = ExtensionPointName.create<BuildSystemAdapter>("io.github.khopland.version-checker.buildSystemAdapter")
        fun matching(project: Project, selection: BuildSelection): List<BuildSystemAdapter> =
            EP.extensionList.filter { selection.scope in it.capabilities.updateScopes && it.supports(project, selection) }
        fun find(id: String): BuildSystemAdapter? = EP.extensionList.singleOrNull { it.id == id }
    }
}

/** Nest provider scopes without changing selected-file/interactive ordering in the coordinator. */
internal suspend fun <T> withBuildScans(project: Project, scans: List<Pair<BuildSystemAdapter, BuildSnapshot>>,
                                      mode: UpdateMode, action: suspend () -> T): T {
    val groups = scans.groupBy({ it.first }, { it.second }).entries.toList()
    suspend fun enter(index: Int): T = if (index == groups.size) action() else {
        val (adapter, snapshots) = groups[index]
        adapter.withScan(project, snapshots, mode) { enter(index + 1) }
    }
    return enter(0)
}
