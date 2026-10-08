package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import java.util.concurrent.Callable
import javax.swing.JComponent
import kotlin.time.Duration.Companion.milliseconds

class BulkPreviewLifetimeTest : BasePlatformTestCase() {
    private lateinit var previews: BulkUpdateService

    override fun setUp() {
        super.setUp()
        // Light fixtures reuse their project. Give each disposal test its own service and scope.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        previews = BulkUpdateService(project, scope)
        Disposer.register(testRootDisposable, previews)
        Disposer.register(testRootDisposable) { scope.cancel() }
    }

    private class OpenDialog(project: com.intellij.openapi.project.Project) : DialogWrapper(project) {
        init { init() }
        override fun createCenterPanel(): JComponent? = null
        override fun show() = Unit
    }

    private class TestDialog(project: com.intellij.openapi.project.Project, val onShow: (TestDialog) -> Boolean) : DialogWrapper(project) {
        init { init() }
        override fun createCenterPanel(): JComponent? = null
        override fun show() { close(if (onShow(this)) OK_EXIT_CODE else CANCEL_EXIT_CODE) }
    }

    fun testServiceDisposalClosesAnOpenDialogAndRejectsItsAcceptance() {
        val service = previews
        lateinit var dialog: TestDialog
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking {
                service.showDialog {
                    TestDialog(project) {
                        assertFalse(it.isDisposed)
                        Disposer.dispose(service)
                        assertTrue(it.isDisposed)
                        // Even an OK response returning after unload must not apply a plan.
                        true
                    }.also { dialog = it }
                }
            }
        })
        assertFalse(PlatformTestUtil.waitForFuture(future, 10_000))
        assertTrue(dialog.isDisposed)
        assertEquals(DialogWrapper.CANCEL_EXIT_CODE, dialog.exitCode)
        assertFalse(project.isDisposed)
    }

    fun testCancelledQueuedPreviewDoesNotConstructADialog() {
        val service = previews
        var constructed = false
        // The test holds EDT. Start a queued UI callback from an IO worker, then cancel it.
        val queued = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            queued.complete(Unit)
            service.showDialog { constructed = true; TestDialog(project) { true } }
        }
        runBlocking { withTimeout(10_000.milliseconds) { queued.await() } }
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("Queued preview cancelled", { job.isCompleted }, 10)
        scope.cancel()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(constructed)
    }

    fun testAcceptedDialogIsDisposedAfterPresentation() {
        val service = previews
        lateinit var dialog: TestDialog
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { service.showDialog { TestDialog(project) { true }.also { dialog = it } } }
        })
        assertTrue(PlatformTestUtil.waitForFuture(future, 10_000))
        assertTrue(dialog.isDisposed)
    }

    fun testDisposalClosesAllPendingNonModalDialogs() {
        val service = previews
        val dialogs = mutableListOf<OpenDialog>()
        val futures = List(2) {
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { service.showDialog { OpenDialog(project).also { dialogs += it } } }
            })
        }
        PlatformTestUtil.waitWithEventsDispatching("Both dialogs shown", { dialogs.size == 2 }, 10)
        assertTrue(futures.none { it.isDone })
        Disposer.dispose(service)
        assertTrue(dialogs.all { it.isDisposed && it.exitCode == DialogWrapper.CANCEL_EXIT_CODE })
        futures.forEach { assertFalse(PlatformTestUtil.waitForFuture(it, 10_000)) }
    }

    fun testCancellingAPendingResponseClosesItsDialog() {
        val service = previews
        var dialog: OpenDialog? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = scope.launch { service.showDialog { OpenDialog(project).also { dialog = it } } }
            PlatformTestUtil.waitWithEventsDispatching("Dialog shown", { dialog != null }, 10)
            job.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Dialog closed", { dialog!!.isDisposed && job.isCompleted }, 10)
            assertEquals(DialogWrapper.CANCEL_EXIT_CODE, dialog!!.exitCode)
        } finally {
            scope.cancel()
        }
    }
}
