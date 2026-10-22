package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import java.awt.event.ActionEvent
import java.util.concurrent.Callable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NpmTargetDetailsDialogTest : BasePlatformTestCase() {
    private fun plan(version: String): BulkUpdatePlan {
        val file = myFixture.findFileInTempDir("details/package.json")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)
        } ?: myFixture.addFileToProject("details/package.json",
            """{"dependencies":{"display-alias":"npm:@fixture/actual@^1.0.0"}}""")
        val adapter = NpmBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, file!!.virtualFile)!!
        val declaration = snapshot.declarations.single()
        return PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.prepareUpdates(project, mapOf(snapshot to UpdateReport(listOf(
                UpdateCandidate(declaration, version, "npm:@fixture/actual@^$version"))))) }
        }), 30_000)
    }

    fun testShortlistRetainsExactProviderTargetWithoutFetchingAndOldDetailsCannotReplaceNewTarget() {
        val major = plan("2.0.0")
        val patch = plan("1.0.5+fixture")
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "npm", major)
        val pending = mutableListOf<Pair<NpmTarget, Continuation<NpmTargetDetails>>>()
        dialog.npmDetailsLoader = { target -> suspendCoroutine { pending += target to it } }
        try {
            dialog.configure(PreparedVersionPreview(major, System.nanoTime(), 0, 1,
                targetPlans = mapOf(UpdateMode.PATCH to patch)), true)
            dialog.table.setRowSelectionInterval(0, 0)
            val metadata = dialog.selectedPlan().changes.single().npmTarget!!
            assertEquals("@fixture/actual", metadata.packageName)
            assertEquals("2.0.0", metadata.version)
            assertEquals("dependencies/display-alias", metadata.declaration.location)
            assertTrue(pending.isEmpty())
            load(dialog)
            waitFor("First details request") { pending.size == 1 }
            dialog.targetSelector.selectedIndex = 1
            assertEquals("1.0.5+fixture", dialog.selectedPlan().changes.single().npmTarget!!.version)
            assertEquals(1, pending.size)
            pending[0].second.resume(NpmTargetDetails(engines = mapOf("node" to "STALE")))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(dialog.npmDetails.text.contains("STALE"))
            load(dialog)
            waitFor("Selected patch details request") { pending.size == 2 }
            assertEquals("1.0.5+fixture", pending[1].first.version)
            pending[1].second.resume(NpmTargetDetails(engines = mapOf("node" to ">=20")))
            waitFor("Selected details displayed") { dialog.npmDetails.text.contains("node: >=20") }
            assertEquals(setOf(0), dialog.model.selectedIndices)
            assertEquals("npm:@fixture/actual@^1.0.5+fixture", dialog.selectedPlan().changes.single().latest)
            dialog.filter.text = "display-alias"
            assertEquals(2, pending.size)
            assertTrue(dialog.npmDetails.text.contains("Engine requirements: Unknown"))
            load(dialog)
            waitFor("Filtered details request") { pending.size == 3 }
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            pending[2].second.resume(NpmTargetDetails(engines = mapOf("node" to "CLOSED")))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(dialog.npmDetails.text.contains("CLOSED"))
        } finally { if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testMetadataFailureDoesNotRemoveOrAuthorizeAnEditAndRecheckDiscardsPendingDetails() {
        val primary = plan("2.0.0")
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "npm", primary)
        var calls = 0
        dialog.npmDetailsLoader = { calls++; error("token=must-not-appear") }
        try {
            dialog.table.setRowSelectionInterval(0, 0)
            val held = dialog.selectedPlan()
            load(dialog)
            waitFor("Failure is visible") { dialog.npmDetails.text.contains("Details unavailable") }
            assertEquals(1, calls)
            assertFalse(dialog.npmDetails.text.contains("must-not-appear"))
            assertEquals(1, dialog.selectedPlan().changes.size)
            var pending: Continuation<NpmTargetDetails>? = null
            dialog.npmDetailsLoader = { suspendCoroutine { pending = it } }
            load(dialog)
            waitFor("Pending retry") { pending != null }
            dialog.onRecheckRequested = { _, _, _, _ -> }
            dialog.refreshAction.actionPerformed(ActionEvent(dialog, 0, "refresh"))
            pending!!.resume(NpmTargetDetails(engines = mapOf("node" to "STALE")))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(dialog.npmDetails.text.contains("STALE"))
            assertFalse(dialog.loadNpmDetailsAction.isEnabled)
            assertFalse("Details do not renew the held plan after refresh", held.apply(project))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun load(dialog: BulkUpdateDialog) = dialog.loadNpmDetailsAction.actionPerformed(ActionEvent(dialog, 0, "details"))
    private fun waitFor(label: String, ready: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(label, ready, 10_000)
}
