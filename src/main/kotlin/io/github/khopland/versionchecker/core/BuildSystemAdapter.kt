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
    fun snapshot(project: Project, file: VirtualFile): BuildSnapshot?
    fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean
    suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot>
    suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport
    /** Start a fresh native metadata generation before a manual/scheduled scan or bulk preview. */
    fun invalidateMetadata(project: Project) = Unit
    suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan

    companion object {
        val EP = ExtensionPointName.create<BuildSystemAdapter>("io.github.khopland.version-checker.buildSystemAdapter")
        fun matching(project: Project, selection: BuildSelection): List<BuildSystemAdapter> =
            EP.extensionList.filter { it.supports(project, selection) }
        fun find(id: String): BuildSystemAdapter? = EP.extensionList.singleOrNull { it.id == id }
    }
}
