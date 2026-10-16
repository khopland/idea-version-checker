package io.github.khopland.versionchecker

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DependencyReviewActivityTest : BasePlatformTestCase() {
    private class GatedDiscovery : BuildSystemAdapter {
        override val id = "review-activity"
        override val displayName = "Review activity fixture"
        override val capabilities = AdapterCapabilities()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val discoveries = AtomicInteger()
        val checks = AtomicInteger()
        var failure: Exception? = null
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = null
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> {
            discoveries.incrementAndGet()
            entered.complete(Unit)
            release.await()
            failure?.let { throw it }
            return emptyList()
        }
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
            checks.incrementAndGet()
            error("An activity read must not check dependencies")
        }
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
            BulkUpdatePlan(emptyList(), emptyList())
    }

    private fun isolatedService(): VersionCheckService {
        // Light-platform tests can reuse a project and its cached service observations.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = VersionCheckService(project, scope)
        Disposer.register(testRootDisposable) { scope.cancel(); service.dispose() }
        return service
    }

    private fun exerciseDiscovery(fail: Boolean = false, cancel: Boolean = false) {
        val service = isolatedService()
        val adapter = GatedDiscovery()
        if (fail) adapter.failure = IllegalStateException("Fixture discovery failed")
        val states = CopyOnWriteArrayList<Boolean>()
        project.messageBus.connect(testRootDisposable).subscribe(VersionCheckStatusListener.TOPIC,
            VersionCheckStatusListener { source -> if (source == null) states += service.hasActiveChecks })
        repeat(5) { assertFalse(service.hasActiveChecks) }
        assertEquals(0, adapter.discoveries.get())
        assertEquals(0, adapter.checks.get())
        val refresh = service.refresh(listOf(adapter))
        try {
            assertTrue("Refresh is active before discovery can publish any result", service.hasActiveChecks)
            PlatformTestUtil.waitWithEventsDispatching("Discovery entered", { adapter.entered.isCompleted }, 10)
            repeat(5) { assertTrue(service.hasActiveChecks) }
            assertTrue(service.observations().isEmpty())
            assertEquals(1, adapter.discoveries.get())
            assertEquals(0, adapter.checks.get())
            assertTrue("Start event exposes active state", states.contains(true))
            if (cancel) refresh.cancel() else adapter.release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Refresh activity finished", {
                refresh.isCompleted && !service.hasActiveChecks && states.lastOrNull() == false
            }, 10)
            assertEquals(1, adapter.discoveries.get())
            assertEquals(0, adapter.checks.get())
        } finally {
            adapter.release.complete(Unit)
            refresh.cancel()
        }
    }

    fun testDisposedServiceDoesNotReportActiveDiscovery() {
        val service = isolatedService()
        val adapter = GatedDiscovery()
        val refresh = service.refresh(listOf(adapter))
        try {
            PlatformTestUtil.waitWithEventsDispatching("Discovery entered", { adapter.entered.isCompleted }, 10)
            assertTrue(service.hasActiveChecks)
            service.dispose()
            repeat(5) { assertFalse(service.hasActiveChecks) }
            assertEquals(1, adapter.discoveries.get())
            assertEquals(0, adapter.checks.get())
        } finally {
            refresh.cancel()
            adapter.release.complete(Unit)
        }
    }

    fun testPassiveActivityIncludesDiscoveryAndSignalsCompletion() = exerciseDiscovery()
    fun testDiscoveryFailureClearsActivityAndSignalsCompletion() = exerciseDiscovery(fail = true)
    fun testCancelledDiscoveryClearsActivityAndSignalsCompletion() = exerciseDiscovery(cancel = true)
}
