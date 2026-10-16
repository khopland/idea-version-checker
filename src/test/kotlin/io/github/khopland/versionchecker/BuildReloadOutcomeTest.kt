package io.github.khopland.versionchecker

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.followup.BuildReloadOutcome
import io.github.khopland.versionchecker.followup.BuildReloadService
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BuildReloadOutcomeTest : BasePlatformTestCase() {
    private class Adapter : BuildSystemAdapter {
        override val id = "reload-outcome-${System.identityHashCode(this)}"
        override val displayName = "Fixture Maven"
        override val capabilities = AdapterCapabilities()
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reloads = AtomicInteger()
        val saves = AtomicInteger()
        var accepted = true
        var failure: Exception? = null
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = null
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
            saves.incrementAndGet()
            saving.complete(Unit)
            release.await()
            return emptySet()
        }
        override fun reloadProject(project: Project): Boolean {
            reloads.incrementAndGet()
            failure?.let { throw it }
            return accepted
        }
        override suspend fun discover(project: Project, selection: BuildSelection) = error("Reload must not check dependencies")
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = error("Reload must not check dependencies")
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) = BulkUpdatePlan(emptyList(), emptyList())
    }

    private fun exercise(expected: BuildReloadOutcome.Phase, accepted: Boolean = true, failure: Exception? = null, cancelSaving: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = BuildReloadService(project, scope)
        val adapter = Adapter().apply { this.accepted = accepted; this.failure = failure }
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.title == "Build reload") notifications += notification }
        })
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val wasTrusted = TrustedProjects.isProjectTrusted(project)
        TrustedProjects.setProjectTrusted(project, true)
        try {
            repeat(5) { assertNull(owner.outcome(adapter.id)) }
            assertEquals(0, adapter.saves.get())
            assertEquals(0, adapter.reloads.get())
            owner.reload(adapter.id)
            PlatformTestUtil.waitWithEventsDispatching("Saving entered", { adapter.saving.isCompleted }, 10)
            assertEquals(BuildReloadOutcome.Phase.SAVING, owner.outcome(adapter.id)?.phase)
            repeat(5) { owner.outcome(adapter.id); owner.reload(adapter.id) }
            assertEquals("Duplicate clicks must not start more saves", 1, adapter.saves.get())
            assertEquals(0, adapter.reloads.get())
            if (cancelSaving) scope.cancel() else adapter.release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Terminal reload outcome", {
                owner.outcome(adapter.id)?.phase == expected
            }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Terminal reload notification", { notifications.isNotEmpty() }, 10)
            val outcome = owner.outcome(adapter.id)!!
            assertEquals(if (cancelSaving) 0 else 1, adapter.reloads.get())
            assertTrue(outcome.message.contains(adapter.displayName))
            assertTrue(outcome.message.contains("Dependency verification was not performed"))
            assertEquals(outcome.message, notifications.last().content)
            if (expected == BuildReloadOutcome.Phase.REQUESTED)
                assertTrue(outcome.message.contains("completion was not observed"))
            assertEquals(!cancelSaving, outcome.requestAttempted)
            assertFalse(outcome.message.contains("native private details"))
            if (expected == BuildReloadOutcome.Phase.FAILED) assertTrue(outcome.message.contains("may have started"))
            owner.dispose()
            assertNull(owner.outcome(adapter.id))
            assertTrue(notifications.all { it.isExpired })
            owner.reload(adapter.id)
            assertEquals(1, adapter.saves.get())
        } finally {
            adapter.release.complete(Unit)
            owner.dispose()
            scope.cancel()
            TrustedProjects.setProjectTrusted(project, wasTrusted)
        }
    }

    fun testAcceptedReloadIsOnlyARequestNotVerifiedSynchronization() = exercise(BuildReloadOutcome.Phase.REQUESTED)
    fun testUnavailableReloadDoesNotClaimRequestSucceeded() = exercise(BuildReloadOutcome.Phase.UNAVAILABLE, accepted = false)
    fun testProviderFailureReportsFailedFollowUp() = exercise(BuildReloadOutcome.Phase.FAILED, failure = IllegalStateException("native private details"))
    fun testNativeCancellationDoesNotClaimNoReloadWasAttempted() = exercise(BuildReloadOutcome.Phase.CANCELLED, failure = ProcessCanceledException())
    fun testCancellationWhileSavingReportsNoReloadRequest() = exercise(BuildReloadOutcome.Phase.CANCELLED, cancelSaving = true)

    fun testMissingAdapterReportsUnavailableInsteadOfReturningSilently() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = BuildReloadService(project, scope)
        val wasTrusted = TrustedProjects.isProjectTrusted(project)
        TrustedProjects.setProjectTrusted(project, true)
        try {
            owner.reload("missing-fixture-provider")
            PlatformTestUtil.waitWithEventsDispatching("Missing provider outcome", {
                owner.outcome("missing-fixture-provider")?.phase == BuildReloadOutcome.Phase.UNAVAILABLE
            }, 10)
            assertTrue(owner.outcome("missing-fixture-provider")!!.message.contains("unavailable"))
        } finally {
            owner.dispose()
            scope.cancel()
            TrustedProjects.setProjectTrusted(project, wasTrusted)
        }
    }
}
