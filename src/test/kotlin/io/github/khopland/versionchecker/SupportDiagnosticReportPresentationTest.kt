package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.UiInterceptors
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.RowSorter
import javax.swing.SortOrder

class SupportDiagnosticReportPresentationTest : BasePlatformTestCase() {
    private val clock = TimeUnit.SECONDS.toNanos(1000)
    private fun row(name: String, adapter: String = "npm") = DependencyReviewRow(adapter, project.basePath!!,
        DeclarationId("${project.basePath}/packages/app/package.json", "dependencies/$name"), name, "1.0.0", "1.0.1",
        VersionChangeKind.PATCH, DependencyReviewOutcome.UPDATE, "private-local-diagnostic", UpdateMode.PATCH, false,
        checkedAtNanos = clock - TimeUnit.SECONDS.toNanos(10), expiresAtNanos = clock + TimeUnit.SECONDS.toNanos(20))

    private fun panel(block: (DependencyToolWindowPanel, AtomicInteger, AtomicInteger) -> Unit) {
        val providerCalls = AtomicInteger()
        val reads = AtomicInteger()
        val trap = object : BuildSystemAdapter {
            override val id = "support-report-native-trap"
            override val displayName = id
            override val capabilities = AdapterCapabilities()
            override fun supports(project: Project, selection: BuildSelection) = true
            override fun isOffline(project: Project) = false
            override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = null
            override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
            override fun invalidateMetadata(project: Project) { providerCalls.incrementAndGet() }
            override suspend fun discover(project: Project, selection: BuildSelection): List<BuildSnapshot> {
                providerCalls.incrementAndGet(); return emptyList()
            }
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                providerCalls.incrementAndGet(); return UpdateReport(emptyList())
            }
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan {
                providerCalls.incrementAndGet(); return BulkUpdatePlan(emptyList(), emptyList())
            }
        }
        BuildSystemAdapter.EP.point.registerExtension(trap, testRootDisposable)
        val panel = DependencyToolWindowPanel(project, now = { clock }, activity = { false }) { reads.incrementAndGet(); emptyList() }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Initial passive snapshot ready", {
                reads.get() > 0 && panel.summaryText().isNotEmpty()
            }, 10_000)
            block(panel, reads, providerCalls)
        } finally { Disposer.dispose(panel) }
    }

    fun testCaptureUsesDisplayedSortedSnapshotAndScalarSettingsWithoutRequeryingOrRenewal() = panel { panel, reads, providerCalls ->
        val options = project.service<VersionCheckerSettings>()
        val original = options.state.copy()
        try {
            panel.presentRows(listOf(row("alpha"), row("beta"), row("hidden", "gradle")))
            panel.provider.selectedItem = "npm"
            panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(3, SortOrder.DESCENDING))
            panel.table.setRowSelectionInterval(0, 0)
            val beforeReads = reads.get()
            options.state.mavenMinimumReleaseAgeHours = 3
            val snapshot = panel.captureSupportReport()
            val captured = snapshot.json()
            panel.clearFilters()
            panel.presentRows(listOf(row("new-result")))
            options.state.mavenMinimumReleaseAgeHours = 1
            assertEquals(captured, snapshot.json())
            val document = JsonParser.parseString(captured).asJsonObject
            assertTrue(document["filtersActiveAtCapture"].asBoolean)
            assertEquals(3, document["totalCachedRowsAtCapture"].asInt)
            assertEquals(2, document["displayedRowsAtCapture"].asInt)
            assertEquals(listOf("beta", "alpha"), document.getAsJsonArray("results").map { it.asJsonObject["artifact"].asString })
            assertEquals(3, document.getAsJsonObject("settings")["mavenMinimumReleaseAgeHours"].asInt)
            assertEquals(10_000L, document.getAsJsonArray("results")[0].asJsonObject["observationAgeMillis"].asLong)
            assertEquals(20_000L, document.getAsJsonArray("results")[0].asJsonObject["cacheExpiresInMillis"].asLong)
            assertEquals(beforeReads, reads.get())
            assertEquals(0, providerCalls.get())
        } finally { options.loadState(original) }
    }

    fun testToolbarOpensReadOnlyFrozenPreviewAndSaveWritesExactlyTheReviewedJson() = panel { panel, reads, providerCalls ->
        panel.presentRows(listOf(row("reviewed-package")))
        val beforeReads = reads.get()
        var shown: SupportDiagnosticReportDialog? = null
        UiInterceptors.registerPossible(testRootDisposable, object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
            override fun doIntercept(component: DialogWrapper) { shown = component as SupportDiagnosticReportDialog }
        })
        panel.supportReportAction.actionPerformed(AnActionEvent.createFromDataContext("test", null, DataContext.EMPTY_CONTEXT))
        PlatformTestUtil.waitWithEventsDispatching("Support report preview is visible", { shown != null }, 10_000)
        val preview = shown!!
        val target = Files.createTempFile("version-checker-support-test-", ".json")
        try {
            assertFalse(preview.preview.isEditable)
            assertEquals("Sanitized support report JSON", preview.preview.accessibleContext.accessibleName)
            assertEquals(preview.report, preview.preview.text)
            panel.presentRows(listOf(row("later-result")))
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { preview.saveTo(target) } })
            PlatformTestUtil.waitForFuture(future, 10_000)
            val saved = Files.readString(target)
            assertEquals(preview.preview.text, saved)
            assertTrue(saved.contains("reviewed-package"))
            assertFalse(saved.contains("later-result"))
            assertFalse(saved.contains("private-local-diagnostic"))
            assertEquals(beforeReads, reads.get())
            assertEquals(0, providerCalls.get())
        } finally { preview.close(DialogWrapper.CANCEL_EXIT_CODE); Files.deleteIfExists(target) }
    }
}
