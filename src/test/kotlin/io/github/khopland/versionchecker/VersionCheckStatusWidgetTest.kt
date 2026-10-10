package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class VersionCheckStatusWidgetTest : BasePlatformTestCase() {
    private fun adapter(file: VirtualFile, providerId: String? = null, expiry: (() -> Long)? = null): BuildSystemAdapter {
        return object : BuildSystemAdapter {
            override val id = providerId ?: "status-widget-${System.identityHashCode(this)}"
            override val displayName = "Test"
            override val capabilities = AdapterCapabilities()
            override fun supports(project: Project, selection: BuildSelection) = selection.currentFile == file.path
            override fun isOffline(project: Project) = false
            override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot {
                assertFalse("Status snapshots must run off the UI thread", ApplicationManager.getApplication().isDispatchThread)
                return BuildSnapshot(BuildContextId(id, file.parent.path, file.path), file.path,
                    BuildFingerprint(mapOf(file.path to file.modificationStamp.toString()), id), emptyList())
            }
            override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
            override suspend fun discover(project: Project, selection: BuildSelection) = emptyList<BuildSnapshot>()
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                nativeChecks.incrementAndGet()
                return UpdateReport(validUntilNanos = expiry?.invoke())
            }
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
                BulkUpdatePlan(emptyList(), emptyList())
        }
    }

    private val nativeChecks = AtomicInteger()
    private fun widget(): Pair<VersionCheckStatusWidget, AtomicInteger> {
        val updates = AtomicInteger()
        val bar = Proxy.newProxyInstance(StatusBar::class.java.classLoader, arrayOf(StatusBar::class.java)) { _, method, _ ->
            when (method.name) {
                "updateWidget" -> { updates.incrementAndGet(); null }
                "getProject" -> project
                else -> null
            }
        } as StatusBar
        // Deliberately supply EDT: the widget must still move snapshots to a background dispatcher.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        Disposer.register(testRootDisposable) { scope.cancel() }
        val widget = VersionCheckStatusWidgetFactory().createWidget(project, scope) as VersionCheckStatusWidget
        Disposer.register(testRootDisposable, widget)
        widget.install(bar)
        return widget to updates
    }

    fun testSelectionEventsUpdateStatusWithoutStartingChecksAndDisposeStopsUpdates() {
        val build = myFixture.addFileToProject("widget/build.txt", "1.0").virtualFile
        val source = myFixture.addFileToProject("widget/source.txt", "source").virtualFile
        BuildSystemAdapter.EP.point.registerExtension(adapter(build), testRootDisposable)
        FileEditorManager.getInstance(project).openFile(build, true)
        val (widget, updates) = widget()
        PlatformTestUtil.waitWithEventsDispatching("Selected build status", {
            widget.status?.phase == CheckPhase.UNCHECKED
        }, 10)
        assertEquals(0, nativeChecks.get())
        FileEditorManager.getInstance(project).openFile(source, true)
        PlatformTestUtil.waitWithEventsDispatching("Unsupported file hides status", { widget.status == null }, 10)
        assertEquals("", widget.getText())
        FileEditorManager.getInstance(project).openFile(build, true)
        PlatformTestUtil.waitWithEventsDispatching("Returning to build restores status", {
            widget.status?.phase == CheckPhase.UNCHECKED
        }, 10)
        assertEquals(0, nativeChecks.get())
        Disposer.dispose(widget)
        val count = updates.get()
        project.service<VersionCheckService>().statusChanged(build.path)
        widget.requestUpdate()
        assertEquals(count, updates.get())
        assertEquals("", widget.getText())
    }

    fun testStatusExpiresAtOriginalReportDeadlineWithoutARepositoryQuery() {
        val build = myFixture.addFileToProject("widget-expiry/build.txt", "1.0").virtualFile
        val adapter = adapter(build) { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500) }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        FileEditorManager.getInstance(project).openFile(build, true)
        val (widget, _) = widget()
        val service = project.service<VersionCheckService>()
        val future = ApplicationManager.getApplication().executeOnPooledThread(java.util.concurrent.Callable {
            kotlinx.coroutines.runBlocking {
                val snapshot = com.intellij.openapi.application.readAction { adapter.snapshot(project, build)!! }
                service.checkNow(adapter, snapshot, UpdateMode.MAJOR)
            }
        })
        PlatformTestUtil.waitForFuture(future, 10_000)
        PlatformTestUtil.waitWithEventsDispatching("Complete result shown", {
            widget.status?.phase == CheckPhase.CHECKED
        }, 10)
        PlatformTestUtil.waitWithEventsDispatching("Original expiry clears complete status", {
            widget.status?.phase == CheckPhase.UNCHECKED
        }, 10)
        assertEquals("The widget must not refresh native results on expiry", 1, nativeChecks.get())
    }

    fun testPartialCountsExpireWhileTheNativeCheckRemainsRunning() {
        val build = myFixture.addFileToProject("widget-partial/build.txt", "1.0").virtualFile
        val native = adapter(build)
        val declaration = VersionDeclaration(DeclarationId(build.path, "library"), ArtifactId(native.id, "library"), "1.0", "1.0")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : BuildSystemAdapter by native {
            override val capabilities = AdapterCapabilities(incrementalInspections = true)
            override fun snapshot(project: Project, file: VirtualFile) = native.snapshot(project, file)!!.copy(declarations = listOf(declaration))
            override fun inspectionSnapshot(project: Project, file: VirtualFile) = snapshot(project, file)
            override suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                                    publish: suspend (InspectionUpdate) -> Unit): UpdateReport {
                nativeChecks.incrementAndGet()
                publish(InspectionUpdate(UpdateReport(listOf(UpdateCandidate(declaration, "1.1")),
                    validUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)), setOf(declaration.id)))
                gate.await()
                return UpdateReport()
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        FileEditorManager.getInstance(project).openFile(build, true)
        val (widget, _) = widget()
        val service = project.service<VersionCheckService>()
        val snapshot = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(
            java.util.concurrent.Callable { com.intellij.openapi.application.ReadAction.compute<BuildSnapshot, RuntimeException> {
                adapter.snapshot(project, build)
            } }), 10_000)
        service.updates(adapter, snapshot)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Partial result shown", {
                widget.status?.phase == CheckPhase.CHECKING && widget.status?.updates == 1
            }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Expired partial count disappears during the check", {
                widget.status?.phase == CheckPhase.CHECKING && widget.status?.updates == 0
            }, 10)
            assertEquals(1, nativeChecks.get())
        } finally {
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Check finishes", { service.cached(snapshot) != null }, 10)
        }
    }

    fun testIgnorePolicyMatchesHintsDuringAndAfterChecksAndCanBeReversedWithoutQueries() {
        val build = myFixture.addFileToProject("widget-ignored/build.txt", "1.0").virtualFile
        val native = adapter(build, "npm")
        val artifact = ArtifactId("npm", "@scope/library")
        val declarations = listOf("dependencies/alias", "devDependencies/library").map {
            VersionDeclaration(DeclarationId(build.path, it), artifact, "1.0", "1.0")
        }
        val report = UpdateReport(declarations.map { UpdateCandidate(it, "1.1") },
            listOf(UpdateNotice(declarations.first(), NoticeKind.MANUAL_REVIEW, "Keep this notice")))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val adapter = object : BuildSystemAdapter by native {
            override val capabilities = AdapterCapabilities(incrementalInspections = true)
            override fun snapshot(project: Project, file: VirtualFile) = native.snapshot(project, file)!!.copy(declarations = declarations)
            override fun inspectionSnapshot(project: Project, file: VirtualFile) = snapshot(project, file)
            override suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                                    publish: suspend (InspectionUpdate) -> Unit): UpdateReport {
                nativeChecks.incrementAndGet()
                publish(InspectionUpdate(report, declarations.map { it.id }.toSet()))
                gate.await()
                return report
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        FileEditorManager.getInstance(project).openFile(build, true)
        val options = project.service<VersionCheckerSettings>().state
        val previousIgnores = options.ignoredVersions
        Disposer.register(testRootDisposable) { options.ignoredVersions = previousIgnores }
        options.ignoredVersions = "npm @scope/library = 1.1"
        val (widget, _) = widget()
        val service = project.service<VersionCheckService>()
        val snapshot = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(
            java.util.concurrent.Callable { com.intellij.openapi.application.ReadAction.compute<BuildSnapshot, RuntimeException> {
                adapter.snapshot(project, build)
            } }), 10_000)
        service.updates(adapter, snapshot)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Ignored partial updates are hidden; notices remain", {
                widget.status?.phase == CheckPhase.CHECKING && widget.status?.updates == 0 && widget.status?.notices == 1
            }, 10)
            assertTrue(service.updates(adapter, snapshot)!!.candidates.isEmpty())
            options.ignoredVersions = ""
            service.statusChanged(build.path)
            PlatformTestUtil.waitWithEventsDispatching("Removing the ignore restores partial updates", {
                widget.status?.phase == CheckPhase.CHECKING && widget.status?.updates == 2
            }, 10)
            assertEquals(2, service.updates(adapter, snapshot)!!.candidates.size)
            options.ignoredVersions = "npm @scope/library = 1.1"
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Complete status uses the same presentation policy", {
                widget.status?.phase == CheckPhase.CHECKED && widget.status?.updates == 0 && widget.status?.notices == 1
            }, 10)
            assertEquals("Versions: needs review", widget.getText())
            assertSame("Raw cached results must retain ignored updates", report, service.cached(snapshot))
            val expiry = widget.status!!.expiresAtNanos
            options.ignoredVersions = "npm @scope/library = 1.2"
            service.statusChanged(build.path)
            PlatformTestUtil.waitWithEventsDispatching("A different release does not hide updates", {
                widget.status?.phase == CheckPhase.CHECKED && widget.status?.updates == 2
            }, 10)
            assertEquals(expiry, widget.status!!.expiresAtNanos)
            assertEquals("Changing presentation must not query the registry", 1, nativeChecks.get())
        } finally {
            options.ignoredVersions = previousIgnores
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Check finishes", { service.cached(snapshot) != null }, 10)
        }
    }
}
