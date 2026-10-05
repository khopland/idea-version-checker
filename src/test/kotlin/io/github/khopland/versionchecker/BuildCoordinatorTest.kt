package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable

/** Exercise the real coordinator with a provider having no Maven or XML dependencies. */
class BuildCoordinatorTest : BasePlatformTestCase() {
    fun testCurrentFileSelectionAndVersionModesReachTheAdapterUnchanged() {
        val first = myFixture.addFileToProject("one/package.json", "old")
        val other = myFixture.addFileToProject("two/package.json", "old")
        val calls = mutableListOf<Pair<String, UpdateMode>>()
        val adapter = object : BuildSystemAdapter {
            override val id = "test-packages"
            override val displayName = "Test packages"
            override val currentFileLabel = "Current Manifest"
            override val capabilities = AdapterCapabilities(setOf(ArtifactRole.DEPENDENCY))
            override fun supports(project: Project, selection: BuildSelection) = true
            override fun isOffline(project: Project) = false
            override fun snapshot(project: Project, file: VirtualFile) = BuildSnapshot(
                BuildContextId(id, file.parent.path, file.path), file.path,
                BuildFingerprint(mapOf(file.path to file.modificationStamp.toString()), "private-registry"),
                listOf(VersionDeclaration(DeclarationId(file.path, "dependencies.@scope/pkg"), ArtifactId("npm", "@scope/pkg"), ArtifactRole.DEPENDENCY, "^1.2.3", "1.2.3", "1.2.8"))
            )
            override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
            override suspend fun discover(project: Project, selection: BuildSelection) = listOf(first, other)
                .filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.virtualFile.path == selection.currentFile }
                .map { snapshot(project, it.virtualFile) }
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                calls += snapshot.sourceFile to mode
                return UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), "1.2.9")))
            }
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan {
                assertEquals(setOf(first.virtualFile.path), reports.keys.map { it.sourceFile }.toSet())
                assertEquals("^1.2.3", reports.values.single().candidates.single().declaration.selector)
                return BulkUpdatePlan(emptyList(), listOf("Needs package-manager lockfile preparation"))
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, first.virtualFile, adapter.id) }
        })
        val plan = PlatformTestUtil.waitForFuture(future, 30_000)
        assertEquals(listOf(first.virtualFile.path to UpdateMode.PATCH), calls)
        assertEquals(listOf("Needs package-manager lockfile preparation"), plan.skipped)
    }
}
