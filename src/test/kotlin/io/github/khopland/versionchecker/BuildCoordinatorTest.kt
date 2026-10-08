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
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable

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
        private val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        private val buildSnapshot = BuildSnapshot(
            BuildContextId(id, file.virtualFile.parent.path, id), file.virtualFile.path,
            BuildFingerprint(mapOf(file.virtualFile.path to "1"), id),
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
