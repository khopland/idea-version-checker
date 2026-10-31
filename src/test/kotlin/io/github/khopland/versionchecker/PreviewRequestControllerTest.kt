package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.UpdateScope
import kotlinx.coroutines.*

class PreviewRequestControllerTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private val delivered = mutableListOf<PreviewRecheckRequest>()
    private val failures = mutableListOf<String>()
    private val first = PreviewRecheckRequest(UpdateMode.entries.first(), UpdateScope.WHOLE_PROJECT, false)
    private val second = PreviewRecheckRequest(UpdateMode.entries.last(), UpdateScope.CURRENT_FILE, true,
        CheckPerformance.Interaction(42, System.nanoTime()))
    private val prepared = PreviewRecheckResult(
        PreparedVersionPreview(BulkUpdatePlan(emptyList(), emptyList()), 1L, 0, 1), emptyList(), true)

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Disposer.register(testRootDisposable) { scope.cancel() }
    }

    private fun controller(load: suspend (PreviewRecheckRequest) -> PreviewRecheckResult): PreviewRequestController =
        PreviewRequestController(scope, { true }, { request ->
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertEquals(request.interaction, CheckPerformance.current())
            load(request)
        },
            { request, result ->
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                assertEquals(request.interaction, CheckPerformance.current())
                assertSame(prepared, result)
                delivered += request
            }, { message ->
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                failures += message
            }).also { Disposer.register(testRootDisposable, it) }

    private fun waitFor(message: String, predicate: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, predicate, 10)

    fun testSupersededFailureCannotClearTheNewRequestOrReplaceItsSelection() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val releaseLatest = CompletableDeferred<Unit>()
        val requests = controller { request ->
            if (request == first) withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                throw IllegalStateException("superseded failure")
            }
            releaseLatest.await()
            prepared
        }
        val old = requests.request(first)!!
        waitFor("First request started") { started.isCompleted }
        val latest = requests.request(second)!!
        release.complete(Unit)
        waitFor("Superseded request finished") { old.isCompleted }
        assertEmpty(delivered)
        assertEmpty(failures)
        releaseLatest.complete(Unit)
        waitFor("Latest selection delivered") { latest.isCompleted }
        assertEquals(listOf(second), delivered)
    }

    fun testCancellationRestoresReviewAndAllowsAReplacementRequest() {
        val started = CompletableDeferred<Unit>()
        val requests = controller { request ->
            if (request == first) {
                started.complete(Unit)
                awaitCancellation()
            }
            prepared
        }
        val cancelled = requests.request(first)!!
        waitFor("Request started") { started.isCompleted }
        cancelled.cancel()
        waitFor("Cancellation delivered") { cancelled.isCompleted }
        assertEquals(listOf("Check cancelled."), failures)
        assertEmpty(delivered)
        val retry = requests.request(second)!!
        waitFor("Retry delivered") { retry.isCompleted }
        assertEquals(listOf(second), delivered)
    }

    fun testFailedCheckKeepsOldPreviewAndSuccessfulRetryCarriesNewModeAndScope() {
        val requests = controller { request ->
            if (request == first) throw IllegalStateException("private native output")
            prepared
        }
        val failed = requests.request(first)!!
        waitFor("Failure delivered") { failed.isCompleted }
        assertEmpty(delivered)
        assertEquals(listOf("Could not check dependency versions. Check the build tool and repository settings."), failures)
        val retry = requests.request(second)!!
        waitFor("Retry delivered") { retry.isCompleted }
        assertEquals(listOf(second), delivered)
    }

    fun testDisposalRejectsLateNonCancellableResultAndFurtherRequests() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = controller {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                prepared
            }
        }
        val pending = requests.request(first)!!
        waitFor("Request started") { started.isCompleted }
        Disposer.dispose(requests)
        assertNull(requests.request(second))
        release.complete(Unit)
        waitFor("Disposed request finished") { pending.isCompleted }
        assertEmpty(delivered)
        assertEmpty(failures)
    }
}
