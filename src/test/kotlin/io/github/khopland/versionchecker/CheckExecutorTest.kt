package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

class CheckExecutorTest : BasePlatformTestCase() {
    private class TestAdapter(override val id: String, private val snapshots: List<BuildSnapshot>) : BuildSystemAdapter {
        override val displayName = id
        override val capabilities = AdapterCapabilities()
        val checked = CopyOnWriteArrayList<BuildSnapshot>()
        var beforeCheck: suspend () -> Unit = {}
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override fun snapshot(project: Project, file: VirtualFile) = snapshots.find { it.sourceFile == file.path }
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun discover(project: Project, selection: BuildSelection) = snapshots
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
            checked += snapshot
            beforeCheck()
            return UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), "1.1")))
        }
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
            BulkUpdatePlan(emptyList(), emptyList())
    }

    private fun snapshot(adapter: String, name: String): BuildSnapshot {
        val file = myFixture.addFileToProject("$adapter/$name", "1.0").virtualFile
        return BuildSnapshot(BuildContextId(adapter, file.parent.path, file.path), file.path,
            BuildFingerprint(mapOf(file.path to "1"), adapter),
            listOf(VersionDeclaration(DeclarationId(file.path, "version"), ArtifactId(adapter, "library"), "1.0", "1.0")))
    }

    private fun <T> background(block: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { block() } }), 10_000)

    fun testDisposeCancelsQueuedInspectionsAndRejectsLateAndNewWork() {
        val first = snapshot("dispose-test", "first.txt")
        val second = snapshot("dispose-test", "second.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = CheckExecutor(project, scope) {}
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("dispose-test", listOf(first, second)).apply {
            beforeCheck = { withContext(NonCancellable) { gate.await() } }
        }
        try {
            executor.updates(adapter, first)
            PlatformTestUtil.waitWithEventsDispatching("First check starts", { adapter.checked.size == 1 }, 10)
            executor.updates(adapter, second)
            PlatformTestUtil.waitWithEventsDispatching("Second check queued", { executor.queuedChecks(adapter.id) == 1 }, 10)
            executor.dispose()
            PlatformTestUtil.waitWithEventsDispatching("Disposed queued check leaves", { executor.queuedChecks(adapter.id) == 0 }, 10)
            assertNull(executor.updates(adapter, second))
            assertFalse(background { executor.scanRefresh(adapter, second, true, VersionCheckFailures()) })
            assertTrue(background { runCatching { executor.checkNow(adapter, second, UpdateMode.MAJOR) } }.isFailure)
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Late check leaves", { !executor.isBusy }, 10)
            assertNull(executor.cached(first))
            assertNull(executor.cached(second))
            assertEquals(listOf(first), adapter.checked.toList())
        } finally {
            gate.complete(Unit)
            executor.dispose()
            scope.cancel()
        }
    }

    fun testProviderInvalidationPreservesOtherProvidersAndPauseCanResume() {
        val first = snapshot("first-provider", "build.txt")
        val second = snapshot("second-provider", "build.txt")
        val one = TestAdapter("first-provider", listOf(first))
        val two = TestAdapter("second-provider", listOf(second))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = CheckExecutor(project, scope) {}
        try {
            background { executor.checkNow(one, first, UpdateMode.MAJOR) }
            background { executor.checkNow(two, second, UpdateMode.MAJOR) }
            repeat(20) {
                assertEquals(2, executor.observations().size)
                assertEquals(1, one.checked.size)
                assertEquals(1, two.checked.size)
            }
            val retained = executor.cachedResult(second, UpdateMode.MAJOR)!!
            executor.invalidate(one.id)
            assertNull(executor.cached(first))
            assertTrue(executor.isCachedResultCurrent(second, UpdateMode.MAJOR, retained))
            executor.pause()
            assertNull(executor.cached(second))
            assertEmpty(executor.observations())
            background { executor.checkNow(two, second, UpdateMode.MAJOR) }
            assertTrue(executor.cached(second)!!.successful)
            assertEquals(2, two.checked.size)
        } finally {
            executor.dispose()
            scope.cancel()
        }
    }
}
