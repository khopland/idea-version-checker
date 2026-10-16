package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.RowSorter
import javax.swing.SortOrder

class DependencyReviewPresentationTest : BasePlatformTestCase() {
    private val clock = TimeUnit.SECONDS.toNanos(1_000)
    private fun observed(module: String, location: String = "dependency:lib", ageSeconds: Long = 60): DependencyReviewRow {
        val source = "/project/$module/build.gradle.kts"
        val declaration = VersionDeclaration(DeclarationId(source, location), ArtifactId("g", "library"), "1.0", "1.0",
            sourceId = DeclarationId("/project/gradle/libs.versions.toml", "versions/shared"))
        val snapshot = BuildSnapshot(BuildContextId("gradle", "/project", source), source,
            BuildFingerprint(emptyMap(), "private-configuration"), listOf(declaration), coverageDescription = "Selected module")
        return DependencyReviewRows.from(listOf(VersionResultCache.Observation(snapshot,
            UpdateReport(listOf(UpdateCandidate(declaration, "1.1"))), UpdateMode.MAJOR, false,
            clock - TimeUnit.SECONDS.toNanos(ageSeconds), clock + TimeUnit.MINUTES.toNanos(5)))).single()
    }

    private fun panel(activity: () -> Boolean = { false }, block: (DependencyToolWindowPanel, AtomicInteger) -> Unit) {
        val reads = AtomicInteger()
        val panel = DependencyToolWindowPanel(project, now = { clock }, activity = activity) {
            reads.incrementAndGet(); emptyList()
        }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Initial presentation ready", {
                reads.get() > 0 && panel.summaryText().isNotEmpty()
            }, 10_000)
            block(panel, reads)
        } finally { Disposer.dispose(panel) }
    }

    fun testSharedPhysicalSourceKeepsModuleAndDeclarationSelectionDistinct() = panel { panel, _ ->
        val first = observed("first")
        val second = observed("second")
        val alias = first.copy(origin = first.origin!!.copy(declaration = first.origin.declaration.copy(location = "dependency:alias")))
        assertEquals(first.source, second.source)
        assertEquals(first.artifact, second.artifact)
        assertFalse(first.identity() == second.identity())
        assertFalse(first.identity() == alias.identity())
        panel.presentRows(listOf(first, second, alias))
        panel.table.setRowSelectionInterval(1, 1)
        panel.presentRows(listOf(alias, second.copy(available = "1.2"), first))
        assertEquals(second.identity(), panel.selectedRow()!!.identity())
        assertTrue(panel.details.text.contains("Check source: /project/second/build.gradle.kts"))
        assertTrue(panel.details.text.contains("Coverage scope: Selected module"))
        panel.search.text = "second/build.gradle"
        assertEquals(listOf(second.identity()), panel.visibleRows().map { it.identity() })
        panel.clearFilters()
        panel.presentRows(listOf(first, alias))
        assertNull("Another module sharing the target cannot replace the selected row", panel.selectedRow())
        panel.table.setRowSelectionInterval(1, 1)
        assertTrue(panel.details.text.contains("Check declaration: dependency:alias"))
    }

    fun testObservationColumnSortsNumericallyAndDetailsExplainFreshness() = panel { panel, reads ->
        val young = observed("young", ageSeconds = 9)
        val old = observed("old", ageSeconds = 120)
        val unknown = observed("unknown").copy(checkedAtNanos = null, expiresAtNanos = null)
        panel.presentRows(listOf(young, old, unknown))
        panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(10, SortOrder.DESCENDING))
        assertEquals(listOf(old.identity(), young.identity(), unknown.identity()), panel.visibleRows().map { it.identity() })
        panel.table.setRowSelectionInterval(0, 0)
        assertTrue(panel.details.text.contains("Observed: 2m ago"))
        assertTrue(panel.details.text.contains("Cache expiry: Expires in 5m"))
        assertTrue(panel.details.text.contains("do not prove unchanged build inputs"))
        assertEquals(120_000L, panel.model.getValueAt(1, 10))
        assertNull(panel.model.getValueAt(2, 10))
        val column = panel.table.convertColumnIndexToView(10)
        val renderer = panel.table.getCellRenderer(2, column)
            .getTableCellRendererComponent(panel.table, null, false, false, 2, column) as javax.swing.JLabel
        assertEquals("Unknown", renderer.text)
        val before = reads.get()
        panel.search.text = "young"
        assertEquals(1, panel.visibleRows().size)
        assertEquals(before, reads.get())
    }

    fun testActivityAndPausedMessagesUsePassiveStateWithoutInventingExpiry() {
        var active = true
        panel(activity = { active }) { panel, reads ->
            assertTrue(panel.summaryText().startsWith("Checking dependencies."))
            assertFalse(panel.summaryText().contains("Refresh Whole Project"))
            active = false
            val before = reads.get()
            panel.presentRows(emptyList())
            assertTrue(panel.summaryText().startsWith("No cached results available"))
            assertFalse(panel.summaryText().contains("Expired"))
            val options = project.getService(VersionCheckerSettings::class.java).state
            val wasEnabled = options.enabled
            try {
                options.enabled = false
                panel.presentRows(emptyList())
                assertTrue(panel.summaryText().startsWith("Checks paused."))
            } finally { options.enabled = wasEnabled }
            assertEquals(before, reads.get())
        }
    }

    fun testExportRedactsCheckSourceAndNeverSerializesContextOrConfiguration() {
        val row = observed("module").copy(origin = observed("module").origin!!.copy(
            context = BuildContextId("gradle", "/private/root", "private-resolution-key"),
            checkSourceFile = "/Users/private/project/build.gradle.kts"))
        val json = DependencyReviewExport.json(listOf(row), "/project", Instant.EPOCH, clock)
        val result = JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("<outside-project>", result.get("checkSourceFile").asString)
        for (private in listOf("private-resolution-key", "/Users/private", "private-configuration", "/private/root"))
            assertFalse(private, json.contains(private))
        val local = JsonParser.parseString(DependencyReviewExport.json(listOf(observed("module")), "/project", Instant.EPOCH, clock))
            .asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("module/build.gradle.kts", local.get("checkSourceFile").asString)
    }

    fun testAgeSortingUsesOneClockSampleUntilPresentationRefresh() {
        var time = clock
        val model = DependencyReviewTableModel("/project") { time }
        val row = observed("module", ageSeconds = 9)
        model.replace(listOf(row))
        assertEquals(9_000L, model.getValueAt(0, 10))
        time += TimeUnit.SECONDS.toNanos(2)
        assertEquals("A comparator cannot see an advancing clock midway through sorting", 9_000L, model.getValueAt(0, 10))
        model.replace(listOf(row))
        assertEquals(11_000L, model.getValueAt(0, 10))
    }
}
