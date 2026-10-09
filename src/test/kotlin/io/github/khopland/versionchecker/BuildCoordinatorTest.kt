package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
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
        var version = "1.1"
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
            val result = UpdateReport(listOf(UpdateCandidate(snapshot.declarations.single(), version)), failure = failure)
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

    fun testScheduledRefreshBypassesCacheButSkipsOfflineAndDisabledChecks() {
        val file = myFixture.addFileToProject("build.txt", "1.0")
        val adapter = TestAdapter("schedule-test", file)
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        fun refresh() = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { service.refreshScheduled() }
        }), 10_000)
        refresh()
        assertEquals("1.1", service.cached(snapshot)!!.candidates.single().version)
        adapter.version = "1.2"
        refresh()
        assertEquals("1.2", service.cached(snapshot)!!.candidates.single().version)
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.setText("unsaved version")
        }
        refresh()
        assertEquals("Scheduled checks must skip unsaved build files", 2, adapter.checked.size)
        FileDocumentManager.getInstance().saveAllDocuments()
        adapter.offline = true
        refresh()
        assertEquals(2, adapter.checked.size)
        adapter.offline = false
        project.service<VersionCheckerSettings>().state.enabled = false
        try {
            refresh()
            assertEquals(2, adapter.checked.size)
        } finally {
            project.service<VersionCheckerSettings>().state.enabled = true
        }
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

    fun testScheduledRefreshDoesNotInvalidateAnActiveInspectionCheck() {
        val file = myFixture.addFileToProject("build.txt", "1.0")
        val gate = CompletableDeferred<Unit>()
        val adapter = TestAdapter("busy-schedule-test", file).apply { beforeCheck = { gate.await() } }
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        service.updates(adapter, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("Inspection lookup starts", { adapter.checked.isNotEmpty() }, 10_000)
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { service.refreshScheduled() }
        }), 10_000)
        gate.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("Inspection lookup published", { service.cached(snapshot) != null }, 10_000)
        assertEquals(1, adapter.checked.size)
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

    fun testCurrentFileUsesItsProviderAndDoesNotConsultOtherOfflineProviders() {
        val first = myFixture.addFileToProject("one/build.txt", "1.0")
        val second = myFixture.addFileToProject("two/build.txt", "1.0")
        val active = TestAdapter("one", first)
        val offline = TestAdapter("two", second).apply { this.offline = true }
        listOf(active, offline).forEach { BuildSystemAdapter.EP.point.registerExtension(it, testRootDisposable) }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, first.virtualFile).build()
        val event = AnActionEvent.createFromDataContext("test", null, context)
        CurrentFilePatchUpdateAction().update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        PatchUpdateAction().update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
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

    fun testPreviewModeAndRefreshControlsRebuildBeforeApplying() {
        val file = myFixture.addFileToProject("preview-controls/build.txt", "1.0")
        val adapter = TestAdapter("preview-controls-test", file)
        BuildSystemAdapter.EP.point.registerExtension(adapter, testRootDisposable)
        var shown = 0
        fun intercept(action: (BulkUpdateDialog) -> Unit) {
            UiInterceptors.registerPossible(testRootDisposable,
                object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                    override fun doIntercept(component: DialogWrapper) {
                        shown++
                        action(component as BulkUpdateDialog)
                    }
                })
        }
        intercept { patch ->
            assertEquals(UpdateMode.PATCH, patch.modeSelector.selectedItem)
            assertEquals("1.0", file.text)
            intercept { minor ->
                assertEquals(UpdateMode.MINOR, minor.modeSelector.selectedItem)
                assertEquals(2, adapter.checked.size)
                assertEquals(0, adapter.metadataRefreshes)
                intercept { refreshed ->
                    assertEquals(UpdateMode.MINOR, refreshed.modeSelector.selectedItem)
                    assertEquals(3, adapter.checked.size)
                    assertEquals(1, adapter.metadataRefreshes)
                    assertEquals("1.0", file.text)
                    refreshed.performOKAction()
                }
                minor.refreshAction.actionPerformed(ActionEvent(minor, ActionEvent.ACTION_PERFORMED, "Refresh"))
            }
            patch.modeSelector.selectedItem = UpdateMode.MINOR
        }
        project.service<BulkUpdateService>().preview(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, file.virtualFile)
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        PlatformTestUtil.waitWithEventsDispatching("Refreshed preview applied", { document.text == "1.1" }, 10_000)
        assertEquals(3, shown)
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
        service.updates(adapter, snapshot)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Early inspection hint published", {
                service.updates(adapter, snapshot)?.candidates?.singleOrNull()?.version == "1.2"
            }, 10_000)
            assertNull("Only a complete check may populate the preview cache", service.cached(snapshot))
            val preview = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.MAJOR, UpdateScope.CURRENT_FILE, file.virtualFile) }
            })
            PlatformTestUtil.waitWithEventsDispatching("Preview waits for the complete check", { service.queuedChecks(adapter.id) == 1 }, 10_000)
            assertFalse(preview.isDone)
            gate.complete(Unit)
            assertEquals(1, PlatformTestUtil.waitForFuture(preview, 10_000).changes.size)
            assertEquals("The preview reuses the final report", 1, checks)
            assertEquals("1.2", service.cached(snapshot)!!.candidates.single().version)
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
}
