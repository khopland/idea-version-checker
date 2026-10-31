package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.awt.event.ActionEvent

/** Exercise the real coordinator with a provider having no Maven or XML dependencies. */
class BuildCoordinatorTest : BasePlatformTestCase() {
    private inner class TestAdapter(override val id: String, private val file: PsiFile) : BuildSystemAdapter {
        override val displayName = id
        override val capabilities = AdapterCapabilities()
        var current = true
        var offline = false
        var failure: String? = null
        var failureCause: Exception? = null
        var version = "1.1"
        var checkedAtNanos: Long? = null
        var validUntilNanos: Long? = null
        var beforeCheck: suspend () -> Unit = {}
        val checked = CopyOnWriteArrayList<String>()
        var metadataRefreshes = 0
        override fun invalidateMetadata(project: Project) { metadataRefreshes++ }
        private val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        private val buildSnapshot = BuildSnapshot(
            BuildContextId(id, file.virtualFile.parent.path, id), file.virtualFile.path,
            BuildFingerprint(mapOf(file.virtualFile.path to "1"), "$id:${System.identityHashCode(this)}"),
            listOf(VersionDeclaration(DeclarationId(file.virtualFile.path, "version"),
                ArtifactId(id, "library"), "1.0", "1.0"))
        )
        override fun supports(project: Project, selection: BuildSelection) =
            selection.scope == UpdateScope.WHOLE_PROJECT || selection.currentFile == file.virtualFile.path
        override fun isOffline(project: Project) = offline
        override fun snapshot(project: Project, file: VirtualFile) = buildSnapshot.takeIf { it.sourceFile == file.path }
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = current
        override suspend fun discover(project: Project, selection: BuildSelection) = listOf(buildSnapshot)
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
            checked += snapshot.sourceFile
            val result = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), version)), failure = failure,
                failureCause = failureCause, checkedAtNanos = checkedAtNanos, validUntilNanos = validUntilNanos)
            beforeCheck()
            return result
        }
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
            BulkUpdatePlan(listOf(object : VersionEdit {
                override val element = file
                override val expected = "1.0"
                override val latest = "1.1"
                override val location = file.virtualFile.path
                override fun isValid() = document.text == expected
                override fun apply() { document.setText(latest) }
            }), emptyList(), isCurrent = { current })
    }

    private fun plan(selection: UpdateScope = UpdateScope.WHOLE_PROJECT, file: VirtualFile? = null): BulkUpdatePlan {
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH, selection, file) }
        })
        return PlatformTestUtil.waitForFuture(future, 30_000)
    }

    fun testRefreshRechecksAnUnchangedFileAndRejectsAnOlderInFlightResult() {
        val file = myFixture.addFileToProject("build.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("refresh-test", file).apply { beforeCheck = { gate.await() } }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertNull(service.updates(adapter, snapshot))
        PlatformTestUtil.waitWithEventsDispatching("First lookup starts", { adapter.checked.size == 1 }, 10_000)
        adapter.version = "1.2"
        service.refresh(adapter.id, file.virtualFile)
        assertEquals("Metadata must be invalidated before queued work continues", 1, adapter.metadataRefreshes)
        gate.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("Fresh version published", {
            service.cached(snapshot)?.candidates?.single()?.version == "1.2"
        }, 10_000)
        assertEquals(2, adapter.checked.size)
        adapter.version = "1.3"
        service.refresh(adapter.id, file.virtualFile)
        PlatformTestUtil.waitWithEventsDispatching("Cached version refreshed", {
            service.cached(snapshot)?.candidates?.single()?.version == "1.3"
        }, 10_000)
        assertEquals(3, adapter.checked.size)
    }

    fun testCancelledRefreshDoesNotCacheAFailureAndCanBeRetried() {
        val file = myFixture.addFileToProject("build.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("cancel-test", file).apply { beforeCheck = { gate.await() } }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val job = service.refresh(listOf(adapter))
        PlatformTestUtil.waitWithEventsDispatching("Lookup starts", { adapter.checked.isNotEmpty() }, 10_000)
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("Lookup cancelled", { job.isCompleted }, 10_000)
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertNull(service.cached(snapshot))
        gate.complete(Unit)
        service.refresh(listOf(adapter))
        PlatformTestUtil.waitWithEventsDispatching("Lookup retried", { service.cached(snapshot) != null }, 10_000)
        assertTrue(service.cached(snapshot)!!.successful)
    }

    fun testDisablingDuringInspectionDiscardsLateResultAndAllowsResume() {
        val file = myFixture.addFileToProject("paused-scan/build.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("paused-scan-test", file).apply {
            beforeCheck = { withContext(NonCancellable) { gate.await() } }
        }
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val configurable = VersionCheckerConfigurable(project)
        try {
            val panel = configurable.createComponent()
            fun boxes(container: java.awt.Container): List<com.intellij.ui.components.JBCheckBox> =
                container.components.flatMap { component ->
                    (if (component is com.intellij.ui.components.JBCheckBox) listOf(component) else emptyList()) +
                        (if (component is java.awt.Container) boxes(component) else emptyList())
                }
            val enabled = boxes(panel as java.awt.Container).single { it.name == "versionChecker.enabled" }
            // Behavior checks should keep working when the visible settings wording changes.
            enabled.text = "Check dependencies"
            service.updates(adapter, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Inspection starts", { adapter.checked.size == 1 }, 10_000)
            enabled.isSelected = false
            configurable.apply()
            gate.complete(Unit)
            assertEquals(CheckPhase.PAUSED, service.status(adapter, snapshot).phase)
            assertNull(service.cached(snapshot))
            adapter.version = "1.2"
            enabled.isSelected = true
            configurable.apply()
            adapter.beforeCheck = {}
            service.updates(adapter, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Inspection resumes with a fresh result", {
                service.cached(snapshot)?.candidates?.singleOrNull()?.version == "1.2"
            }, 10_000)
            assertEquals(2, adapter.checked.size)
        } finally {
            gate.complete(Unit)
            project.service<VersionCheckerSettings>().state.enabled = true
            configurable.disposeUIResources()
        }
    }

    fun testDisablingDuringManualRefreshRejectsLateResult() {
        val file = myFixture.addFileToProject("paused-refresh/build.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("paused-refresh-test", file).apply {
            beforeCheck = { withContext(NonCancellable) { gate.await() } }
        }
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val job = service.refresh(listOf(adapter))
        try {
            PlatformTestUtil.waitWithEventsDispatching("Refresh starts", { adapter.checked.size == 1 }, 10_000)
            project.service<VersionCheckerSettings>().state.enabled = false
            service.pause()
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Refresh finishes", { job.isCompleted }, 10_000)
            assertNull(service.cached(snapshot))
        } finally {
            gate.complete(Unit)
            project.service<VersionCheckerSettings>().state.enabled = true
        }
    }

    fun testCurrentFileRefreshRunsBetweenProjectModulesWithoutWaitingForTheWholeScan() {
        val first = myFixture.addFileToProject("priority/first/build.txt", "1.0")
        val other = myFixture.addFileToProject("priority/other/build.txt", "1.0")
        val native = TestAdapter("priority-test", first)
        val firstSnapshot = native.snapshot(project, first.virtualFile)!!
        val otherSnapshot = firstSnapshot.copy(context = firstSnapshot.context.copy(resolutionId = other.virtualFile.path),
            sourceFile = other.virtualFile.path)
        val firstGate = CompletableDeferred<Unit>()
        val otherGate = CompletableDeferred<Unit>()
        val checked = CopyOnWriteArrayList<String>()
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun discover(project: Project, selection: BuildSelection) =
                listOf(firstSnapshot, otherSnapshot).filter { selection.scope == UpdateScope.WHOLE_PROJECT || it.sourceFile == selection.currentFile }
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                checked += snapshot.sourceFile
                if (checked.size == 1) firstGate.await()
                if (snapshot.sourceFile == otherSnapshot.sourceFile) otherGate.await()
                return UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), "1.2")))
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val whole = service.refresh(listOf(adapter))
        try {
            PlatformTestUtil.waitWithEventsDispatching("First project check starts", { checked.size == 1 }, 10_000)
            val current = service.refresh(listOf(adapter), first.virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("Current-file check queued", { service.queuedChecks(adapter.id) == 1 }, 10_000)
            firstGate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Current-file check finishes", { current.isCompleted }, 10_000)
            assertEquals("1.2", service.cached(firstSnapshot)!!.candidates.single().version)
            assertFalse("The unrelated module remains blocked", whole.isCompleted)
            assertEquals(listOf(firstSnapshot.sourceFile, firstSnapshot.sourceFile), checked.take(2))
        } finally {
            firstGate.complete(Unit); otherGate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Whole-project check finishes", { whole.isCompleted }, 10_000)
        }
    }

    fun testSupersededInspectionLeavesTheQueueBeforeNativeWorkStarts() {
        val blocker = myFixture.addFileToProject("superseded/blocker.txt", "1.0")
        val file = myFixture.addFileToProject("superseded/build.txt", "1.0")
        val native = TestAdapter("superseded-test", blocker)
        val blocking = native.snapshot(project, blocker.virtualFile)!!
        val old = blocking.copy(context = blocking.context.copy(resolutionId = file.virtualFile.path), sourceFile = file.virtualFile.path)
        var latest = old
        val gate = CompletableDeferred<Unit>()
        val checked = CopyOnWriteArrayList<BuildSnapshot>()
        val adapter = object : BuildSystemAdapter by native {
            override fun isCurrent(project: Project, snapshot: BuildSnapshot) = snapshot == blocking || snapshot == latest
            override suspend fun isCurrentInBackground(project: Project, snapshot: BuildSnapshot) =
                com.intellij.openapi.application.readAction { isCurrent(project, snapshot) }
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                checked += snapshot
                if (snapshot == blocking) gate.await()
                return UpdateReport()
            }
        }
        val service = project.service<VersionCheckService>()
        val running = service.refresh(listOf(adapter))
        try {
            PlatformTestUtil.waitWithEventsDispatching("Blocking check starts", { checked.size == 1 }, 10_000)
            service.updates(adapter, old)
            PlatformTestUtil.waitWithEventsDispatching("Old inspection queued", { service.queuedChecks(adapter.id) == 1 }, 10_000)
            latest = old.copy(fingerprint = old.fingerprint.copy(configuration = "changed"))
            service.updates(adapter, latest)
            PlatformTestUtil.waitWithEventsDispatching("Only the replacement inspection remains", { service.queuedChecks(adapter.id) == 1 }, 10_000)
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Replacement cached", { service.cached(latest) != null }, 10_000)
            assertEquals(listOf(blocking, latest), checked.toList())
            assertNull(service.cached(old))
        } finally { gate.complete(Unit); running.cancel() }
    }

    fun testChecksUseProviderBackgroundValidationOutsideReadActions() {
        val file = myFixture.addFileToProject("background-input/build.txt", "1.0")
        val native = TestAdapter("background-input-test", file)
        var validations = 0
        val adapter = object : BuildSystemAdapter by native {
            override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean =
                error("Coordinator must use provider background validation")
            override suspend fun isCurrentInBackground(project: Project, snapshot: BuildSnapshot): Boolean {
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                validations++
                return native.current
            }
        }
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        fun check(reuse: Boolean) = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runCatching { runBlocking { service.checkNow(adapter, snapshot, UpdateMode.PATCH, reuseCached = reuse) } }
            }), 10_000)
        assertTrue(check(false).getOrThrow().successful)
        assertEquals(2, validations)
        assertTrue(check(true).getOrThrow().successful)
        assertEquals(3, validations)
        service.invalidateForPreview(adapter, file.virtualFile)
        service.updates(adapter, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("Automatic check uses the background validator", {
            service.cached(snapshot) != null
        }, 10_000)
        assertEquals(5, validations)
        native.current = false
        assertTrue("A changed input must reject cached reuse", check(true).exceptionOrNull() is IllegalStateException)
    }

    fun testWholeProjectCombinesProvidersAndRetainsEveryStalePreviewGuard() {
        val first = myFixture.addFileToProject("one/build.txt", "1.0")
        val second = myFixture.addFileToProject("two/build.txt", "1.0")
        val adapters = listOf(TestAdapter("one", first), TestAdapter("two", second))
        adapters.forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val combined = plan()
        assertEquals(setOf(first.virtualFile.path, second.virtualFile.path), combined.changes.map { it.location }.toSet())
        adapters.last().current = false
        assertFalse(combined.apply(project))
        assertEquals("1.0", FileDocumentManager.getInstance().getDocument(first.virtualFile)!!.text)
        assertEquals("1.0", FileDocumentManager.getInstance().getDocument(second.virtualFile)!!.text)
        adapters.last().current = true
        assertTrue(plan().apply(project))
        assertEquals("1.1", FileDocumentManager.getInstance().getDocument(first.virtualFile)!!.text)
        assertEquals("1.1", FileDocumentManager.getInstance().getDocument(second.virtualFile)!!.text)
    }

    fun testPausedChecksRejectAHeldPlanBeforeProviderValidationAndAllowResume() {
        val file = myFixture.addFileToProject("paused-preview/build.txt", "1.0")
        val native = TestAdapter("paused-preview-test", file)
        var providerGuards = 0
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
                native.prepareUpdates(project, reports).guardedBy { providerGuards++; true }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val held = plan()
        val settings = project.service<VersionCheckerSettings>().state
        settings.enabled = false
        try {
            assertFalse(held.apply(project))
            assertEquals("Paused authorization must avoid final provider input reads", 0, providerGuards)
            assertEquals("1.0", file.text)
        } finally { settings.enabled = true }
        assertTrue(held.apply(project))
        assertEquals("Authorization runs before policy capture and again inside the write command", 2, providerGuards)
        assertEquals(1, native.checked.size)
    }

    fun testCurrentFileUsesItsProviderAndDoesNotConsultOtherOfflineProviders() {
        val first = myFixture.addFileToProject("one/build.txt", "1.0")
        val second = myFixture.addFileToProject("two/build.txt", "1.0")
        val active = TestAdapter("one", first)
        val offline = TestAdapter("two", second).apply { this.offline = true }
        listOf(active, offline).forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, first.virtualFile).build()
        val event = AnActionEvent.createFromDataContext("test", null, context)
        ReviewUpdatesAction().update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals(listOf(first.virtualFile.path), plan(UpdateScope.CURRENT_FILE, first.virtualFile).changes.map { it.location })
        assertTrue(offline.checked.isEmpty())
        assertTrue(BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, "unknown/file")).isEmpty())
    }

    fun testProviderFailurePreventsAWholeProjectPlanAndLeavesAllFilesUnchanged() {
        val first = myFixture.addFileToProject("one/build.txt", "1.0")
        val second = myFixture.addFileToProject("two/build.txt", "1.0")
        listOf(TestAdapter("one", first), TestAdapter("two", second).apply { failure = "Registry unavailable" })
            .forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching { runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH) } }
        })
        val result = PlatformTestUtil.waitForFuture(future, 30_000)
        assertEquals("Registry unavailable", result.exceptionOrNull()?.message)
        assertEquals("1.0", first.text)
        assertEquals("1.0", second.text)
    }

    fun testWarmPreviewsReuseOnlyTheirModeAndExplicitRefreshInvalidatesOlderPlans() {
        val file = myFixture.addFileToProject("cached/build.txt", "1.0")
        val adapter = TestAdapter("cached-test", file)
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<BulkUpdateService>()
        fun preview(mode: UpdateMode, fresh: Boolean = false) = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { service.createPlan(mode, UpdateScope.CURRENT_FILE, file.virtualFile, fresh) }
            }), 10_000)
        val initial = preview(UpdateMode.PATCH)
        preview(UpdateMode.PATCH)
        assertEquals(1, adapter.checked.size)
        assertEquals("Ordinary previews reuse results and metadata", 0, adapter.metadataRefreshes)
        preview(UpdateMode.MINOR)
        assertEquals("Patch results cannot answer a minor request", 2, adapter.checked.size)
        preview(UpdateMode.MINOR)
        assertEquals(2, adapter.checked.size)
        preview(UpdateMode.PATCH, fresh = true)
        assertEquals(3, adapter.checked.size)
        assertEquals(1, adapter.metadataRefreshes)
        assertFalse("A refresh invalidates the older preview before editing", initial.apply(project))
        assertEquals("1.0", file.text)
        preview(UpdateMode.MINOR)
        assertEquals("Current-file refresh invalidates all modes", 4, adapter.checked.size)
    }

    fun testPreparedPreviewPreservesTheEarliestActualDeadlineAcrossReusedResults() {
        val time = System.nanoTime()
        val minutes = java.util.concurrent.TimeUnit.MINUTES
        val first = TestAdapter("deadline-first", myFixture.addFileToProject("deadline/first.txt", "1.0")).apply {
            checkedAtNanos = time - minutes.toNanos(1)
            validUntilNanos = time + minutes.toNanos(1)
        }
        val second = TestAdapter("deadline-second", myFixture.addFileToProject("deadline/second.txt", "1.0")).apply {
            checkedAtNanos = time - minutes.toNanos(9)
            validUntilNanos = time + minutes.toNanos(2)
        }
        listOf(first, second).forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        fun prepare() = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { VersionPreviewPreparer(project).prepare(UpdateMode.PATCH, UpdateScope.WHOLE_PROJECT,
                    null, listOf(first, second), forceRefresh = false) }
            }), 10_000)
        val initial = prepare()
        assertEquals(0, initial.reusedReports)
        assertEquals(second.checkedAtNanos, initial.checkedAtNanos)
        assertEquals("The oldest observation need not expire first", first.validUntilNanos, initial.expiresAtNanos)
        val reused = prepare()
        assertEquals(2, reused.reusedReports)
        assertEquals(initial.checkedAtNanos, reused.checkedAtNanos)
        assertEquals(initial.expiresAtNanos, reused.expiresAtNanos)
        assertEquals(1, first.checked.size)
        assertEquals(1, second.checked.size)
    }

    fun testPreviewModeAndRefreshControlsKeepOneWindowBeforeApplying() {
        val file = myFixture.addFileToProject("preview-controls/build.txt", "1.0")
        val preparations = java.util.concurrent.atomic.AtomicInteger()
        val native = TestAdapter("preview-controls-test", file)
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan {
                preparations.incrementAndGet()
                return native.prepareUpdates(project, reports)
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        var shown = 0
        var review: BulkUpdateDialog? = null
        UiInterceptors.registerPossible(testRootDisposable,
            object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    shown++
                    review = component as BulkUpdateDialog
                }
            })
        project.service<BulkUpdateService>().preview(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, file.virtualFile)
        PlatformTestUtil.waitWithEventsDispatching("Initial preview ready", { review != null }, 10_000)
        val dialog = review!!
        try {
            dialog.filter.text = "build.txt"
            dialog.modeSelector.selectedItem = UpdateMode.MINOR
            PlatformTestUtil.waitWithEventsDispatching("Minor results replace the existing model", {
                preparations.get() == 2 && !dialog.checking
            }, 10_000)
            assertSame(dialog, review)
            assertFalse(dialog.isDisposed)
            assertEquals("build.txt", dialog.filter.text)
            assertEquals(0, native.metadataRefreshes)
            dialog.refreshAction.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "Refresh"))
            PlatformTestUtil.waitWithEventsDispatching("Fresh results replace the existing model", {
                preparations.get() == 3 && !dialog.checking
            }, 10_000)
            assertEquals(1, native.metadataRefreshes)
            assertEquals(UpdateMode.MINOR, dialog.modeSelector.selectedItem)
            assertEquals("1.0", file.text)
            dialog.performOKAction()
            val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
            PlatformTestUtil.waitWithEventsDispatching("Refreshed preview applied", { document.text == "1.1" }, 10_000)
            assertEquals(1, shown)
            assertEquals(3, preparations.get())
        } finally { if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testRejectedApplyRefreshesTheSameWindowAndPreservesChoicesUntilSuccessfulRetry() {
        val file = myFixture.addFileToProject("rejected-preview/build.txt", "1.0")
        val native = TestAdapter("rejected-preview-test", file)
        BuildSystemAdapter.EP.point.registerExtension(native, testRootDisposable)
        var shown = 0
        var review: BulkUpdateDialog? = null
        UiInterceptors.registerPossible(testRootDisposable,
            object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) { shown++; review = component as BulkUpdateDialog }
            })
        project.service<BulkUpdateService>().preview(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, file.virtualFile)
        PlatformTestUtil.waitWithEventsDispatching("Initial preview ready", { review != null }, 10_000)
        val dialog = review!!
        try {
            dialog.filter.text = "build"
            native.current = false
            dialog.performOKAction()
            PlatformTestUtil.waitWithEventsDispatching("Rejected plan finishes its fresh check", {
                native.metadataRefreshes == 1 && !dialog.checking
            }, 10_000)
            assertFalse(dialog.isDisposed)
            assertFalse(dialog.isOKActionEnabled)
            assertEquals("build", dialog.filter.text)
            assertEquals("1.0", file.text)
            native.current = true
            dialog.refreshAction.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "Refresh"))
            PlatformTestUtil.waitWithEventsDispatching("Fresh retry returns to the existing review", {
                native.metadataRefreshes == 2 && dialog.isOKActionEnabled
            }, 10_000)
            assertEquals(1, shown)
            assertEquals(setOf(0), dialog.model.selectedIndices)
            dialog.performOKAction()
            PlatformTestUtil.waitWithEventsDispatching("Write-safe retry applies and closes the review", {
                dialog.isDisposed
            }, 10_000)
            assertTrue(dialog.isDisposed)
            assertEquals("1.1", FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.text)
        } finally { if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testEarlyInspectionHintsDoNotAuthorizeAPreviewWhileTheCheckIsStillRunning() {
        val file = myFixture.addFileToProject("early-hints/build.txt", "1.0")
        val native = TestAdapter("early-hints-test", file)
        val snapshot = native.snapshot(project, file.virtualFile)!!
        val early = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), "1.2")))
        val gate = CompletableDeferred<Unit>()
        var checks = 0
        val adapter = object : BuildSystemAdapter by native {
            override val capabilities = AdapterCapabilities(incrementalInspections = true)
            override suspend fun checkIncrementally(project: Project, snapshot: BuildSnapshot, mode: UpdateMode,
                                                    publish: suspend (InspectionUpdate) -> Unit): UpdateReport {
                checks++
                publish(InspectionUpdate(early, snapshot.declarations.map { it.id }.toSet()))
                gate.await()
                return early
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        assertEquals(CheckPhase.UNCHECKED, service.status(adapter, snapshot).phase)
        assertEquals("Reading status must not start a native check", 0, checks)
        service.updates(adapter, snapshot)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Early inspection hint published", {
                service.updates(adapter, snapshot)?.candidates?.singleOrNull()?.version == "1.2"
            }, 10_000)
            assertNull("Only a complete check may populate the preview cache", service.cached(snapshot))
            assertEquals(CheckPhase.CHECKING, service.status(adapter, snapshot).phase)
            assertEquals(1, service.status(adapter, snapshot).updates)
            val preview = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.MAJOR, UpdateScope.CURRENT_FILE, file.virtualFile) }
            })
            PlatformTestUtil.waitWithEventsDispatching("Preview waits for the complete check", { service.queuedChecks(adapter.id) == 1 }, 10_000)
            assertFalse(preview.isDone)
            gate.complete(Unit)
            assertEquals(1, PlatformTestUtil.waitForFuture(preview, 10_000).changes.size)
            assertEquals("The preview reuses the final report", 1, checks)
            assertEquals("1.2", service.cached(snapshot)!!.candidates.single().version)
            PlatformTestUtil.waitWithEventsDispatching("Status becomes complete", {
                service.status(adapter, snapshot).phase == CheckPhase.CHECKED
            }, 10)
        } finally { gate.complete(Unit) }
    }

    fun testStatusExplainsBlockedChecksAndNeverReusesAChangedSnapshot() {
        val file = myFixture.addFileToProject("status/build.txt", "1.0")
        val native = TestAdapter("status-blocked-test", file)
        var saved = true
        val adapter = object : BuildSystemAdapter by native {
            override fun canCheckInBackground(project: Project, snapshot: BuildSnapshot) = saved
        }
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val service = project.service<VersionCheckService>()
        assertEquals(CheckPhase.UNCHECKED, service.status(adapter, snapshot).phase)
        saved = false
        assertEquals(CheckPhase.SAVE_REQUIRED, service.status(adapter, snapshot).phase)
        saved = true
        native.offline = true
        assertEquals(CheckPhase.OFFLINE, service.status(adapter, snapshot).phase)
        project.service<VersionCheckerSettings>().state.enabled = false
        try {
            assertEquals(CheckPhase.PAUSED, service.status(adapter, snapshot).phase)
        } finally { project.service<VersionCheckerSettings>().state.enabled = true }
        native.offline = false
        assertTrue(native.checked.isEmpty())
        val refresh = service.refresh(listOf(adapter), file.virtualFile)
        PlatformTestUtil.waitWithEventsDispatching("Check finishes", { refresh.isCompleted }, 10)
        assertEquals(CheckPhase.CHECKED, service.status(adapter, snapshot).phase)
        val edited = snapshot.copy(fingerprint = snapshot.fingerprint.copy(configuration = "edited"))
        assertEquals(CheckPhase.UNCHECKED, service.status(adapter, edited).phase)
        service.invalidateForPreview(adapter, file.virtualFile)
        assertEquals(CheckPhase.UNCHECKED, service.status(adapter, snapshot).phase)
    }

    fun testFailedStatusCanBeRetriedAndCancellationDoesNotBecomeAFailure() {
        val file = myFixture.addFileToProject("status-retry/build.txt", "1.0")
        val adapter = TestAdapter("status-retry-test", file).apply { failure = "private native failure" }
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        fun refresh() {
            val job = service.refresh(listOf(adapter), file.virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("Refresh finishes", { job.isCompleted }, 10)
        }
        refresh()
        val failed = service.status(adapter, snapshot)
        assertEquals(CheckPhase.FAILED, failed.phase)
        assertFalse("Repository error details stay out of status text", failed.tooltip.contains("private native failure"))
        adapter.failure = null
        refresh()
        assertEquals(CheckPhase.CHECKED, service.status(adapter, snapshot).phase)
        val gate = CompletableDeferred<Unit>()
        adapter.beforeCheck = { gate.await() }
        val job = service.refresh(listOf(adapter), file.virtualFile)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Check runs", {
                service.status(adapter, snapshot).phase == CheckPhase.CHECKING
            }, 10)
            job.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Check cancelled", { job.isCompleted }, 10)
            assertEquals(CheckPhase.UNCHECKED, service.status(adapter, snapshot).phase)
        } finally { gate.complete(Unit) }
    }

    fun testFailedStatusUsesSafeNativeOfflineAndAuthenticationAdviceWithoutQueryingAgain() {
        val file = myFixture.addFileToProject("native-status/build.txt", "1.0")
        val adapter = TestAdapter("native-status-test", file)
        val service = project.service<VersionCheckService>()
        for ((code, guidance) in listOf("ENOTCACHED" to "offline", "E401" to "credentials")) {
            adapter.failure = "private native output"
            adapter.failureCause = io.github.khopland.versionchecker.npm.NpmViewFailure(code, "private native output")
            val job = service.refresh(listOf(adapter), file.virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("Native failure completes", { job.isCompleted }, 10)
            val checks = adapter.checked.size
            repeat(3) {
                val status = service.status(adapter, adapter.snapshot(project, file.virtualFile)!!)
                assertEquals(CheckPhase.FAILED, status.phase)
                assertTrue(status.tooltip.contains(guidance))
                assertTrue(status.tooltip.contains("Check This File for Updates"))
                assertFalse(status.tooltip.contains("private native output"))
            }
            assertEquals(checks, adapter.checked.size)
        }
    }

    fun testStatusShowsQueuedFileWithoutConfusingItWithAnotherRunningFile() {
        val blocker = myFixture.addFileToProject("status-queue/blocker.txt", "1.0")
        val waiting = myFixture.addFileToProject("status-queue/waiting.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("status-queue-test", blocker).apply { beforeCheck = { gate.await() } }
        val first = adapter.snapshot(project, blocker.virtualFile)!!
        val second = first.copy(context = first.context.copy(resolutionId = waiting.virtualFile.path), sourceFile = waiting.virtualFile.path)
        val service = project.service<VersionCheckService>()
        service.updates(adapter, first)
        try {
            PlatformTestUtil.waitWithEventsDispatching("First file checks", {
                service.status(adapter, first).phase == CheckPhase.CHECKING
            }, 10)
            service.updates(adapter, second)
            assertEquals(CheckPhase.QUEUED, service.status(adapter, second).phase)
            assertEquals(CheckPhase.CHECKING, service.status(adapter, first).phase)
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Both files finish", {
                service.status(adapter, first).phase == CheckPhase.CHECKED && service.status(adapter, second).phase == CheckPhase.CHECKED
            }, 10)
        } finally { gate.complete(Unit) }
    }

    fun testCurrentFileSelectionAndVersionModesReachTheAdapterUnchanged() {
        val first = myFixture.addFileToProject("one/package.json", "old")
        val other = myFixture.addFileToProject("two/package.json", "old")
        val calls = mutableListOf<Pair<String, UpdateMode>>()
        val adapter = object : BuildSystemAdapter {
            override val id = "test-packages"
            override val displayName = "Test packages"
            override val capabilities = AdapterCapabilities()
            override fun supports(project: Project, selection: BuildSelection) = true
            override fun isOffline(project: Project) = false
            override fun snapshot(project: Project, file: VirtualFile) = BuildSnapshot(
                BuildContextId(id, file.parent.path, file.path), file.path,
                BuildFingerprint(mapOf(file.path to file.modificationStamp.toString()), "private-registry"),
                listOf(VersionDeclaration(DeclarationId(file.path, "dependencies.@scope/pkg"), ArtifactId("npm", "@scope/pkg"), "^1.2.3", "1.2.3", "1.2.8"))
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
            runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, first.virtualFile) }
        })
        val plan = PlatformTestUtil.waitForFuture(future, 30_000)
        assertEquals(listOf(first.virtualFile.path to UpdateMode.PATCH), calls)
        assertEquals(listOf("Needs package-manager lockfile preparation"), plan.skipped)
    }
    fun testStatusAndPreviewCoverageShareIgnorePolicyWithoutAnotherNativeQuery() {
        val file = myFixture.addFileToProject("coverage/build.txt", "1.0")
        val native = TestAdapter("npm", file)
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                native.check(project, snapshot, mode).copy(coverage = snapshot.declarations.associate {
                    it.id to DeclarationCoverage(DeclarationOutcome.CHECKED)
                })
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) =
                native.prepareUpdates(project, reports).let { plan ->
                    if (reports.values.all { it.candidates.isEmpty() }) plan.copy(changes = emptyList()) else plan
                }
        }
        val settings = project.service<VersionCheckerSettings>().state
        val original = settings.ignoredVersions
        val service = project.service<VersionCheckService>()
        fun prepare() = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { project.service<BulkUpdateService>().preparePreview(UpdateMode.MAJOR, UpdateScope.CURRENT_FILE,
                file.virtualFile, listOf(adapter), false) }
        }), 10_000)
        try {
            settings.ignoredVersions = "npm library = 1.1"
            val ignored = prepare()
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            assertTrue(ignored.plan.changes.isEmpty())
            assertEquals(CoverageSummary(checked = 1, ignored = 1), ignored.coverage)
            assertEquals(ignored.coverage, service.status(adapter, snapshot).coverage)
            assertEquals(0, service.status(adapter, snapshot).updates)
            settings.ignoredVersions = ""
            val restored = prepare()
            assertEquals(CoverageSummary(checked = 1), restored.coverage)
            assertEquals(restored.coverage, service.status(adapter, snapshot).coverage)
            assertEquals(1, restored.plan.changes.size)
            assertEquals(1, native.checked.size)
        } finally { settings.ignoredVersions = original }
    }

    fun testWholeProjectCoverageCountsSharedPhysicalDeclarationsOnceAndKeepsUnknownTotalsUnknown() {
        val file = myFixture.addFileToProject("shared-coverage/build.txt", "1.0")
        val native = TestAdapter("shared-coverage", file)
        val child = native.snapshot(project, file.virtualFile)!!
        val parent = child.copy(context = child.context.copy(resolutionId = "shared-owner"),
            declarations = child.declarations.map { it.copy(id = it.id.copy(location = "shared-owner"), sourceId = it.id) })
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun discover(project: Project, selection: BuildSelection) = listOf(parent, child)
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = UpdateReport(
                coverage = snapshot.declarations.associate { it.id to DeclarationCoverage(DeclarationOutcome.CHECKED) })
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>) = BulkUpdatePlan(emptyList(), emptyList())
        }
        fun prepare(adapters: List<BuildSystemAdapter>) = PlatformTestUtil.waitForFuture(
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().preparePreview(UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT,
                    null, adapters, false) }
            }), 10_000)
        assertEquals(CoverageSummary(checked = 1), prepare(listOf(adapter)).coverage)
        val unknown = TestAdapter("unknown-coverage", file)
        assertNull("A legacy provider must not silently turn unknown coverage into a complete total",
            prepare(listOf(adapter, unknown)).coverage)
    }

}
