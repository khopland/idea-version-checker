package io.github.khopland.versionchecker

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.khopland.versionchecker.core.UpdateScope
import java.awt.Container
import java.awt.event.ActionEvent
import javax.swing.JButton

class BulkUpdateDialogTest : BasePlatformTestCase() {
    private fun withTracing(block: () -> Unit) {
        val logger = Logger.getInstance(CheckPerformance::class.java)
        val level = if (logger.isDebugEnabled) LogLevel.DEBUG else LogLevel.INFO
        logger.setLevel(LogLevel.DEBUG)
        try { block() } finally { logger.setLevel(level) }
    }
    private fun edit(name: String): VersionEdit {
        val file = myFixture.addFileToProject("selection/$name.txt", "1.0")
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        return object : VersionEdit {
            override val element = file
            override val expected = "1.0"
            override val latest = "2.0"
            override val location = "$name: declared version"
            override fun isValid() = document.text == expected
            override fun apply() { document.setText(latest) }
        }
    }

    private fun dialog(plan: BulkUpdatePlan) = BulkUpdateDialog(project, UpdateMode.PATCH, UpdateScope.WHOLE_PROJECT.label, "Test", plan)
    private fun text(edit: VersionEdit) = FileDocumentManager.getInstance().getDocument(edit.element!!.containingFile.virtualFile)!!.text
    private fun buttons(container: Container): List<JButton> = container.components.flatMap {
        when (it) { is JButton -> listOf(it); is Container -> buttons(it); else -> emptyList() }
    }

    fun testSortedCheckboxEditsTheCorrectDeclarationAndKeepsOriginalApplyOrder() {
        val edits = listOf(edit("zeta"), edit("alpha"), edit("beta"))
        val dialog = dialog(BulkUpdatePlan(edits, emptyList()))
        try {
            dialog.model.selectRows(edits.indices.toSet(), false)
            dialog.table.rowSorter.toggleSortOrder(1)
            assertEquals(1, dialog.table.convertRowIndexToModel(0))
            dialog.table.setValueAt(true, 0, 0)
            val selected = dialog.selectedPlan()
            assertEquals(listOf(edits[1]), selected.changes)
            assertTrue(selected.apply(project))
            assertEquals("1.0", text(edits[0]))
            assertEquals("2.0", text(edits[1]))
            assertEquals("1.0", text(edits[2]))
            dialog.model.selectRows(setOf(0, 2), true)
            assertEquals(edits, dialog.selectedPlan().changes)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testFilteredSelectionsRemainVisibleInSummaryAndClearSelectionDisablesApply() {
        val dialog = dialog(BulkUpdatePlan(listOf(edit("alpha"), edit("beta"), edit("zeta")), emptyList()))
        val body = dialog.createCenterPanel()
        try {
            dialog.filter.text = "alpha"
            assertEquals(1, dialog.table.rowCount)
            assertTrue(dialog.summary.text.contains("2 selected hidden by filter"))
            assertEquals(3, dialog.selectedPlan().changes.size)
            buttons(body).single { it.text == "Clear selection" }.doClick()
            assertFalse(dialog.isOKActionEnabled)
            buttons(body).single { it.text == "Select visible" }.doClick()
            assertTrue(dialog.isOKActionEnabled)
            assertEquals(listOf("alpha: declared version"), dialog.selectedPlan().changes.map { it.location })
            dialog.filter.text = "[" // Literal filtering must not treat user text as a regex.
            assertEquals(0, dialog.table.rowCount)
            assertTrue(dialog.summary.text.contains("1 selected hidden by filter"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testSpaceTogglesSortedVisibleRowsWithoutChangingHiddenSelection() {
        val dialog = dialog(BulkUpdatePlan(listOf(edit("zeta-keyboard"), edit("alpha-keyboard"), edit("beta-keyboard")), emptyList()))
        try {
            dialog.table.rowSorter.toggleSortOrder(1)
            dialog.filter.text = "alpha-keyboard"
            dialog.table.setRowSelectionInterval(0, 0)
            val toggle = dialog.table.actionMap.get("toggleVersionSelection")
            toggle.actionPerformed(ActionEvent(dialog.table, ActionEvent.ACTION_PERFORMED, "Space"))
            assertEquals(setOf(0, 2), dialog.model.selectedIndices)
            assertTrue(dialog.summary.text.contains("2 selected hidden by filter"))
            toggle.actionPerformed(ActionEvent(dialog.table, ActionEvent.ACTION_PERFORMED, "Space"))
            assertEquals(setOf(0, 1, 2), dialog.model.selectedIndices)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testSelectedPlanRetainsEveryOriginalStaleInputGuard() {
        val edits = listOf(edit("alpha"), edit("beta"))
        var current = true
        val plan = BulkUpdatePlan(edits, emptyList(), isCurrent = { current })
        val dialog = dialog(plan)
        try {
            dialog.model.setValueAt(false, 1, 0)
            current = false
            assertFalse(dialog.selectedPlan().apply(project))
            assertTrue(edits.all { it.element!!.text == "1.0" })
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testSelectingOneProviderOnlyKeepsItsOwnFollowUpGuidance() {
        val combined = BulkUpdatePlan.combine(listOf(
            BulkUpdatePlan(listOf(edit("maven")), emptyList()),
            BulkUpdatePlan(listOf(edit("npm")), emptyList(), followUp = listOf("Synchronize npm lockfile")),
            BulkUpdatePlan(listOf(edit("gradle")), emptyList(), followUp = listOf("Reload Gradle"))
        ))
        assertTrue(combined.select(setOf(0)).followUp.isEmpty())
        assertEquals(listOf("Synchronize npm lockfile"), combined.select(setOf(1)).followUp)
        assertEquals(listOf("Reload Gradle"), combined.select(setOf(0, 2)).followUp)
    }

    fun testEmptyPreviewStillOffersRefreshAndUnavailableCurrentFileIsRemovedWithoutClosing() {
        val dialog = dialog(BulkUpdatePlan(emptyList(), listOf("Shared version needs review")))
        dialog.configure(PreparedVersionPreview(BulkUpdatePlan(emptyList(), emptyList()), System.nanoTime(), 0, 1), false)
        assertFalse(dialog.isDisposed)
        assertFalse(dialog.isOKActionEnabled)
        assertEquals(1, dialog.scopeSelector.itemCount)
        dialog.refreshAction.actionPerformed(ActionEvent(dialog, ActionEvent.ACTION_PERFORMED, "Refresh"))
        assertEquals(BulkUpdateDialog.RECHECK_EXIT_CODE, dialog.exitCode)
        assertTrue(dialog.refreshRequested)
        assertTrue(dialog.isDisposed)
    }

    fun testChangingModeRequestsANewPreviewBeforeAnythingCanApply() {
        val edit = edit("alpha")
        val dialog = dialog(BulkUpdatePlan(listOf(edit), emptyList()))
        dialog.modeSelector.selectedItem = UpdateMode.MINOR
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(BulkUpdateDialog.RECHECK_EXIT_CODE, dialog.exitCode)
        assertFalse(dialog.refreshRequested)
        assertEquals("1.0", edit.element!!.text)
    }

    fun testReturningToThePreparedModeBeforeRecheckKeepsThePreviewOpen() {
        val dialog = dialog(BulkUpdatePlan(listOf(edit("rapid-mode")), emptyList()))
        try {
            dialog.modeSelector.selectedItem = UpdateMode.MINOR
            dialog.modeSelector.selectedItem = UpdateMode.PATCH
            UIUtil.dispatchAllInvocationEvents()
            assertFalse(dialog.isDisposed)
            assertTrue(dialog.isOKActionEnabled)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testApplyDuringPendingModeChangeRequestsRecheckInsteadOfAcceptingOldPlan() {
        val edit = edit("pending-mode")
        val dialog = dialog(BulkUpdatePlan(listOf(edit), emptyList()))
        dialog.modeSelector.selectedItem = UpdateMode.MINOR
        dialog.doOKAction()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(BulkUpdateDialog.RECHECK_EXIT_CODE, dialog.exitCode)
        assertFalse(dialog.refreshRequested)
        assertEquals("1.0", edit.element!!.text)
    }

    fun testRefreshAndModeChangesStartIndependentPreviewInteractions() = withTracing {
        val initial = CheckPerformance.start(CheckPerformance.Stage.PREVIEW_INVOKED)!!
        CheckPerformance.locally(initial) {
            val refresh = dialog(BulkUpdatePlan(emptyList(), emptyList()))
            refresh.refreshAction.actionPerformed(ActionEvent(refresh, ActionEvent.ACTION_PERFORMED, "Refresh"))
            val refreshTrace = refresh.recheckInteraction!!
            assertTrue(refreshTrace.id != initial.id)
            assertTrue(refreshTrace.started >= initial.started)

            val mode = dialog(BulkUpdatePlan(emptyList(), emptyList()))
            mode.modeSelector.selectedItem = UpdateMode.MINOR
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(mode.isDisposed)
            assertTrue(mode.recheckInteraction!!.id != refreshTrace.id)
            assertNull(mode.applyInteraction)
            assertSame(initial, CheckPerformance.current())
        }
    }

    fun testBulkApplyKeepsItsInvocationOriginThroughWritePreparation() = withTracing {
        val edit = edit("apply-trace")
        val dialog = dialog(BulkUpdatePlan(listOf(edit), emptyList()))
        dialog.doOKAction()
        assertTrue(dialog.isOK)
        val interaction = dialog.applyInteraction!!
        assertNull(dialog.recheckInteraction)
        var observed: CheckPerformance.Interaction? = null
        val selected = dialog.selectedPlan().guardedBy {
            observed = CheckPerformance.current()
            true
        }
        assertTrue(selected.apply(project, interaction))
        assertSame(interaction, observed)
        assertEquals("2.0", text(edit))
        assertNull(CheckPerformance.current())
    }

    fun testEmptyApplyAndRevertedModeDoNotStartAnInteraction() = withTracing {
        val dialog = dialog(BulkUpdatePlan(emptyList(), emptyList()))
        try {
            dialog.modeSelector.selectedItem = UpdateMode.MINOR
            dialog.modeSelector.selectedItem = UpdateMode.PATCH
            UIUtil.dispatchAllInvocationEvents()
            dialog.doOKAction()
            assertFalse(dialog.isDisposed)
            assertNull(dialog.recheckInteraction)
            assertNull(dialog.applyInteraction)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
