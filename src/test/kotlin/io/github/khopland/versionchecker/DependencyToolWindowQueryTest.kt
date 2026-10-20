package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.VersionChangeKind
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.RowSorter
import javax.swing.SortOrder

class DependencyToolWindowQueryTest : BasePlatformTestCase() {
    private fun row(artifact: String, provider: String = "maven", root: String = "/project", source: String = "$root/pom.xml",
                    outcome: DependencyReviewOutcome = DependencyReviewOutcome.UPDATE, kind: VersionChangeKind? = VersionChangeKind.PATCH) =
        DependencyReviewRow(provider, root, DeclarationId(source, "dependency:$artifact"), artifact,
            "1.0", "1.1", kind, outcome, "A reason for $artifact", UpdateMode.MAJOR, false)

    fun testLiteralPackageAndPathSearchCombinesAllFilters() {
        val match = row("g:lib[core]", root = "/project/service")
        val otherProvider = match.copy(adapter = "gradle")
        val otherRoot = match.copy(root = "/project/other")
        val otherKind = match.copy(kind = VersionChangeKind.MAJOR)
        val otherOutcome = match.copy(outcome = DependencyReviewOutcome.SKIPPED)
        val rows = listOf(match, otherProvider, otherRoot, otherKind, otherOutcome, row("g:libcore"))
        assertEquals(listOf(match), DependencyReviewQuery("LIB[core]", "maven", "/project/service",
            DependencyReviewFilter.UPDATES, VersionChangeKind.PATCH).apply(rows))
        assertEquals(listOf(match), DependencyReviewQuery("SERVICE/POM.XML", "maven", "/project/service",
            DependencyReviewFilter.UPDATES, VersionChangeKind.PATCH).apply(rows))
        assertEmpty(DependencyReviewQuery(".*").apply(rows))
    }

    fun testSearchTrimsWhitespaceAndIncludesTheOriginalCheckSource() {
        val row = row("g:library").copy(origin = DependencyReviewOrigin(
            io.github.khopland.versionchecker.core.BuildContextId("maven", "/project", "module"),
            DeclarationId("/project/module/pom.xml", "dependency:library"), "/project/module/pom.xml"))
        val rows = listOf(row)
        assertEquals(rows, DependencyReviewQuery("  G:LIBRARY  ").apply(rows))
        assertEquals(rows, DependencyReviewQuery("  MODULE/POM.XML  ").apply(rows))
        assertEquals(rows, DependencyReviewQuery(" \t ").apply(rows))
        assertEmpty(DependencyReviewQuery("module", provider = "gradle").apply(rows))
    }

    private fun withPanel(action: (DependencyToolWindowPanel, AtomicInteger) -> Unit) {
        val reads = AtomicInteger()
        val panel = DependencyToolWindowPanel(project) { reads.incrementAndGet(); emptyList() }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Initial passive presentation", { reads.get() > 0 && panel.summaryText().isNotEmpty() }, 10_000)
            action(panel, reads)
        } finally { Disposer.dispose(panel) }
    }

    fun testSortedSelectionDetailsAndExportTrackIdentityAcrossResults() = withPanel { panel, _ ->
        val alpha = row("g:alpha")
        val beta = row("g:beta")
        panel.presentRows(listOf(alpha, beta))
        panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(3, SortOrder.DESCENDING))
        panel.table.setRowSelectionInterval(0, 0)
        assertEquals(beta.identity(), panel.selectedRow()!!.identity())
        assertTrue(panel.details.text.contains("Source: /project/pom.xml"))
        assertTrue(panel.details.text.contains("A reason for g:beta"))
        val changed = beta.copy(available = "1.2", detail = "New diagnostic")
        panel.presentRows(listOf(changed, alpha))
        assertEquals(changed, panel.selectedRow())
        assertTrue(panel.details.text.contains("Available: 1.2"))
        assertTrue(panel.details.text.contains("New diagnostic"))
        assertEquals(listOf("g:beta", "g:alpha"), panel.visibleRows().map { it.artifact })
        assertEquals(SortOrder.DESCENDING, panel.table.rowSorter.sortKeys.single().sortOrder)
        val exported = JsonParser.parseString(DependencyReviewExport.json(panel.visibleRows(), "/project"))
            .asJsonObject.getAsJsonArray("results").map { it.asJsonObject.get("artifact").asString }
        assertEquals(listOf("g:beta", "g:alpha"), exported)
        // Another context with the same package is never substituted for the selected row.
        panel.presentRows(listOf(alpha, changed.copy(root = "/other")))
        assertNull(panel.selectedRow())
        assertFalse(panel.canOpenSelectedSource())
        assertTrue(panel.details.text.startsWith("Select a dependency"))
    }

    fun testFiltersAndDetailsArePassiveAccessibleAndClearable() = withPanel { panel, reads ->
        val source = java.nio.file.Files.createTempFile("dependency-source", ".xml")
        source.toFile().deleteOnExit()
        java.nio.file.Files.writeString(source, "<project/>")
        assertNotNull(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        val alpha = row("g:alpha", source = source.toString())
        val beta = row("g:beta", provider = "gradle", root = "/other", source = "/missing/build.gradle")
        panel.presentRows(listOf(alpha, beta))
        val initialReads = reads.get()
        panel.provider.selectedItem = "maven"
        panel.buildRoot.selectedItem = "/project"
        panel.search.text = "ALPHA"
        panel.filter.selectedItem = DependencyReviewFilter.UPDATES
        panel.kind.selectedItem = "PATCH"
        assertEquals(listOf(alpha), panel.visibleRows())
        panel.table.setRowSelectionInterval(0, 0)
        assertTrue(panel.canOpenSelectedSource())
        assertTrue(panel.details.text.contains("Build system: maven"))
        assertEquals("Cached dependency versions", panel.table.accessibleContext.accessibleName)
        assertEquals("Selected dependency details", panel.details.accessibleContext.accessibleName)
        assertEquals("Build system", panel.provider.accessibleContext.accessibleName)
        assertEquals("Build root", panel.buildRoot.accessibleContext.accessibleName)
        assertNotNull(panel.table.actionMap.get("openSource"))
        assertNotNull(panel.actionMap.get("focusSearch"))
        assertEquals(6, panel.table.columnCount)
        panel.search.text = "no match"
        assertTrue(panel.summaryText().startsWith("No matches"))
        assertNull(panel.selectedRow())
        assertFalse(panel.canOpenSelectedSource())
        panel.clearFilters()
        assertEquals(2, panel.visibleRows().size)
        panel.table.setRowSelectionInterval(1, 1)
        assertFalse(panel.canOpenSelectedSource())
        assertEquals(initialReads, reads.get())
        panel.provider.selectedItem = "maven"
        panel.buildRoot.selectedItem = "/project"
        panel.presentRows(listOf(beta))
        assertEquals("maven", panel.provider.selectedItem)
        assertEquals("/project", panel.buildRoot.selectedItem)
        assertEmpty(panel.visibleRows())
        assertTrue(panel.summaryText().startsWith("No matches"))
        panel.presentRows(emptyList())
        assertTrue(panel.summaryText().startsWith("No cached results available"))
        assertFalse(panel.summaryText().contains("expired", ignoreCase = true))
    }
    fun testControlsRemainUsableAtNarrowToolWindowWidth() = withPanel { panel, _ ->
        panel.setSize(340, 600)
        fun layout(component: java.awt.Container) {
            component.doLayout()
            component.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        layout(panel)
        assertTrue("Search retains usable width", panel.search.width >= 80)
        assertTrue("Provider filter remains visible", panel.provider.width > 40)
        assertTrue("Root filter remains visible", panel.buildRoot.width > 40)
        assertTrue("Details remain visible", panel.details.width > 0)
        assertEquals("focusSearch", panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .get(javax.swing.KeyStroke.getKeyStroke("control F")))
        assertEquals("openSource", panel.table.inputMap.get(javax.swing.KeyStroke.getKeyStroke("ENTER")))
    }
}
