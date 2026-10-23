package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

class InspectionInputCacheTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    override fun setUp() { super.setUp(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    override fun tearDown() { try { scope.cancel() } finally { super.tearDown() } }

    private fun adapter(name: String = "test", prepare: suspend (VirtualFile) -> (() -> BuildSnapshot?)) = object : BuildSystemAdapter {
        override val id = name
        override val displayName = name
        override val capabilities = AdapterCapabilities()
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = error("Synchronous input capture")
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun prepareInspectionSnapshot(project: Project, file: VirtualFile) = prepare(file)
        override suspend fun discover(project: Project, selection: BuildSelection) = emptyList<BuildSnapshot>()
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = UpdateReport()
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) = BulkUpdatePlan(emptyList(), emptyList())
    }
    private fun snapshot(file: VirtualFile, text: String) = BuildSnapshot(BuildContextId("test", "root", file.path),
        file.path, BuildFingerprint(mapOf(file.path to text), "test"), emptyList())

    fun testFirstVisitorReturnsWithoutWaitingAndRepeatedVisitorsShareCaptureWithLiveEditorOverlay() {
        val file = myFixture.addFileToProject("capture.txt", "original")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val captures = AtomicInteger(); val notifications = AtomicInteger()
        val provider = adapter { virtual ->
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            captures.incrementAndGet(); started.complete(Unit); release.await()
            val reader = { snapshot(virtual, file.text) }
            reader
        }
        val cache = InspectionInputCache(project, scope, { notifications.incrementAndGet() })
        repeat(100) { assertNull(cache.snapshot(provider, file.virtualFile)) }
        PlatformTestUtil.waitWithEventsDispatching("capture started", { started.isCompleted }, 5_000)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)!!.setText("unsaved")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        release.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("capture ready", { notifications.get() == 1 }, 5_000)
        assertEquals("unsaved", cache.snapshot(provider, file.virtualFile)!!.fingerprint.files[file.virtualFile.path])
        assertEquals(1, captures.get())
        myFixture.addFileToProject("unrelated.txt", "another file")
        assertNotNull("Unrelated VFS activity must not restart external capture", cache.snapshot(provider, file.virtualFile))
        assertEquals(1, captures.get())
        cache.dispose(); assertEquals(0, cache.size())
    }

    fun testExpiryReleasesCaptureWithoutPollingAndNextVisitorRecaptures() {
        val file = myFixture.addFileToProject("expiry.txt", "text").virtualFile
        val captures = AtomicInteger(); val notifications = AtomicInteger()
        // Keep each capture pending until its lookup has asserted the non-blocking result.
        val releaseCapture = Channel<Unit>(Channel.UNLIMITED)
        val provider = adapter { virtual ->
            captures.incrementAndGet()
            releaseCapture.receive()
            val reader = { snapshot(virtual, "text") }
            reader
        }
        val cache = InspectionInputCache(project, scope, { notifications.incrementAndGet() }, lifetimeMillis = 100)
        assertNull(cache.snapshot(provider, file))
        releaseCapture.trySend(Unit).getOrThrow()
        PlatformTestUtil.waitWithEventsDispatching("capture expired", { notifications.get() == 1 && cache.size() == 0 }, 5_000)
        assertEquals(1, captures.get())
        assertNull(cache.snapshot(provider, file))
        releaseCapture.trySend(Unit).getOrThrow()
        PlatformTestUtil.waitWithEventsDispatching("new capture ready", { notifications.get() == 2 }, 5_000)
        assertEquals(2, captures.get()); cache.dispose()
    }

    fun testUnavailableSnapshotDoesNotRestartHighlightingOrCreateAnAutomaticCaptureLoop() {
        val file = myFixture.addFileToProject("unavailable.txt", "text").virtualFile
        val captures = AtomicInteger(); val notifications = AtomicInteger()
        val available = java.util.concurrent.atomic.AtomicBoolean()
        val releaseCapture = Channel<Unit>(Channel.UNLIMITED)
        val provider = adapter { virtual ->
            captures.incrementAndGet()
            releaseCapture.receive()
            val reader = { if (available.get()) snapshot(virtual, "text") else null }
            reader
        }
        val cache = InspectionInputCache(project, scope, { notifications.incrementAndGet() }, lifetimeMillis = 100)
        assertNull(cache.snapshot(provider, file))
        releaseCapture.trySend(Unit).getOrThrow()
        PlatformTestUtil.waitWithEventsDispatching("unavailable capture expired", { captures.get() == 1 && cache.size() == 0 }, 5_000)
        assertEquals(0, notifications.get())
        assertEquals(1, captures.get())
        available.set(true)
        assertNull(cache.snapshot(provider, file))
        releaseCapture.trySend(Unit).getOrThrow()
        PlatformTestUtil.waitWithEventsDispatching("supported capture ready", { notifications.get() == 1 }, 5_000)
        assertNotNull(cache.snapshot(provider, file))
        cache.dispose()
    }

    fun testPendingCapturesAreBoundedAndCancelledEvictionInvalidationAndDisposalCannotPublishLateReaders() {
        val files = (1..3).map { myFixture.addFileToProject("bounded$it.txt", "text").virtualFile }
        val started = AtomicInteger(); val cancelled = AtomicInteger(); val notifications = AtomicInteger()
        val provider = adapter { started.incrementAndGet(); try { awaitCancellation() } finally { cancelled.incrementAndGet() } }
        val cache = InspectionInputCache(project, scope, { notifications.incrementAndGet() }, capacity = 2)
        files.take(2).forEach { assertNull(cache.snapshot(provider, it)) }
        PlatformTestUtil.waitWithEventsDispatching("two captures started", { started.get() == 2 }, 5_000)
        assertNull(cache.snapshot(provider, files.last()))
        PlatformTestUtil.waitWithEventsDispatching("old capture cancelled", { cancelled.get() == 1 && started.get() == 3 }, 5_000)
        assertEquals(2, cache.size())
        cache.invalidate(provider.id, files[1].path)
        PlatformTestUtil.waitWithEventsDispatching("source capture cancelled", { cancelled.get() == 2 }, 5_000)
        assertEquals(1, cache.size())
        cache.dispose()
        PlatformTestUtil.waitWithEventsDispatching("all captures cancelled", { cancelled.get() == 3 }, 5_000)
        assertEquals(0, cache.size()); assertEquals(0, notifications.get())
        assertNull(cache.snapshot(provider, files.first()))
    }
}
