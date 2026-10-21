package io.github.khopland.versionchecker

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.Disposer
import io.github.khopland.versionchecker.core.VersionVerificationOutcome
import io.github.khopland.versionchecker.maven.MavenUpdateVerificationService
import kotlinx.coroutines.*
import io.github.khopland.versionchecker.followup.NativeFileChange
import io.github.khopland.versionchecker.followup.NativeFileChanges

class NativeFileReviewTest : BasePlatformTestCase() {
    fun testNativeReviewsAreReadOnlyHistoricalAndSeparateFromDeclarations() {
        val store = UpdateSessionStore()
        val token = store.start(50, "Declaration changes", setOf("npm"))
        val step = store.begin(token, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Preparing")!!
        val captured = NativeFileChanges(listOf(NativeFileChange("/project/package-lock.json", null, "{\"version\":2}")))
        store.update(step, UpdateStepStatus.COMPLETED, "Command completed", captured)
        val snapshot = store.get(token)!!
        val request = UpdateSessionService.nativeRequests(project, token, snapshot.steps).single()
        assertEquals(50L, snapshot.diffToken)
        assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        assertTrue(request.title!!.contains("session #$token step #${step.step}"))
        assertTrue(request.title!!.contains("concurrent writes may be included"))
        assertEquals("", (request.contents[0] as DocumentContent).document.text)
        assertEquals("{\"version\":2}", (request.contents[1] as DocumentContent).document.text)
        assertEquals("Before command (file absent)", request.contentTitles[0])
        assertTrue(snapshot.verification.isEmpty())
    }
    fun testOpenSummaryRefreshesOnlyItsOriginalSessionWithoutStartingNativeWork() {
        val store = UpdateSessionStore(2)
        val first = store.start(10, "First declarations", setOf("npm"))
        val panel = UpdateSessionSummaryPanel(testRootDisposable, { store.get(first) })
        val second = store.start(11, "Second declarations", setOf("npm"))
        val step = store.begin(first, "npm", UpdateStepKind.NPM_LOCKFILE_SYNC, "Previewing lockfile command")!!
        store.update(step, UpdateStepStatus.RUNNING, "Original command running")
        PlatformTestUtil.waitWithEventsDispatching("Session status should refresh while open",
            { panel.text.text.contains("Original command running") }, 5_000)
        assertTrue(panel.text.text.contains("First declarations"))
        assertFalse(panel.text.text.contains("Second declarations"))
        assertTrue(store.get(second)!!.steps.isEmpty())
        assertFalse(panel.text.isEditable)
        store.start(null, "Third declarations", setOf("maven"))
        PlatformTestUtil.waitWithEventsDispatching("Expired session should retire its live summary",
            { panel.text.text.contains("No retained update session") }, 5_000)
    }

    fun testCancelledVersionObservationReportsATypedOutcomeBeforeStarting() {
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.IO)
        val owner = MavenUpdateVerificationService(project, scope)
        Disposer.register(testRootDisposable, owner)
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<VersionVerificationOutcome>()
        owner.verify(AppliedVersionChanges(emptyList()), outcomes::add)
        PlatformTestUtil.waitWithEventsDispatching("Unstarted observation should report cancellation",
            { outcomes.isNotEmpty() }, 5_000)
        assertEquals(1, outcomes.size)
        assertEquals(VersionVerificationOutcome.Status.CANCELLED, outcomes.single().status)
        assertTrue(outcomes.single().message.startsWith("Unknown:"))
        scope.cancel()
    }

}
