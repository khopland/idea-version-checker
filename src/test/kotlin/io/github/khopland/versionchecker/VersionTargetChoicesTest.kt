package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import javax.swing.event.PopupMenuEvent

class VersionTargetChoicesTest : BasePlatformTestCase() {
    private fun <T> background(block: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { block() } }), 30_000)

    private fun edit(name: String, target: String): VersionEdit {
        val file = myFixture.findFileInTempDir("targets/$name.txt")?.let { PsiManager.getInstance(project).findFile(it) }
            ?: myFixture.addFileToProject("targets/$name.txt", "1.0.0")
        val document = FileDocumentManager.getInstance().getDocument(file!!.virtualFile)!!
        return object : VersionEdit {
            override val element = file
            override val expected = "1.0.0"
            override val latest = target
            override val location = file.virtualFile.path
            override val buildSystemId = "test"
            override fun isValid() = document.text == expected
            override fun apply() { document.setText(latest) }
        }
    }

    fun testMixedTargetsKeepEachProvidersAuthorizationAndSelectedEdits() {
        var patchCurrent = true
        val major = BulkUpdatePlan(listOf(edit("one", "2.0.0"), edit("two", "3.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("one", "1.0.2"), edit("two", "1.0.3")), emptyList(),
            isCurrent = { patchCurrent })
        val choices = VersionTargetChoices(major, UpdateMode.MAJOR, mapOf(UpdateMode.PATCH to patch))
        assertTrue(choices.choose(0, choices.options(0).single { it.mode == UpdateMode.PATCH }))
        assertEquals(listOf("1.0.2", "3.0.0"), choices.plan(setOf(0, 1)).changes.map { it.latest })
        val selected = choices.plan(setOf(0, 1))
        patchCurrent = false
        assertFalse(selected.apply(project))
        assertEquals("1.0.0", major.changes[1].element!!.text)
        patchCurrent = true
        assertTrue(choices.plan(setOf(0, 1)).apply(project))
        assertEquals("1.0.2", major.changes[0].element!!.text)
        assertEquals("3.0.0", major.changes[1].element!!.text)
    }

    fun testRestrictedModeAndAmbiguousOwnersNeverExposeTargets() {
        val primary = BulkUpdatePlan(listOf(edit("restricted", "1.1.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("restricted", "1.0.1"), edit("restricted", "1.0.2")), emptyList())
        val major = BulkUpdatePlan(listOf(edit("restricted", "9.0.0")), emptyList())
        val choices = VersionTargetChoices(primary, UpdateMode.MINOR,
            mapOf(UpdateMode.PATCH to patch, UpdateMode.MAJOR to major))
        assertEquals(listOf("1.1.0"), choices.options(0).map { it.edit.latest })
        assertFalse(choices.choose(0, VersionTargetChoice(UpdateMode.MAJOR, major, 0)))
    }

    fun testMixingModesKeepsOriginalDeclarationOrder() {
        val primary = BulkUpdatePlan((0..2).map { edit("order-$it", "2.0.0") }, emptyList())
        val patch = BulkUpdatePlan((0..2).map { edit("order-$it", "1.0.2") }, emptyList())
        val choices = VersionTargetChoices(primary, UpdateMode.MAJOR, mapOf(UpdateMode.PATCH to patch))
        listOf(0, 2).forEach { row -> choices.choose(row, choices.options(row).single { it.mode == UpdateMode.PATCH }) }
        val selected = choices.plan(setOf(2, 1, 0))
        assertEquals(primary.changes.map { it.location }, selected.changes.map { it.location })
        assertEquals(listOf("1.0.2", "2.0.0", "1.0.2"), selected.changes.map { it.latest })
    }

    fun testPsiTargetsWithTheSameLabelAndVersionHaveDistinctPhysicalOwners() {
        val file = myFixture.configureByText(XmlFileType.INSTANCE, "<root><one>1.0.0</one><two>1.0.0</two></root>") as XmlFile
        val tags = file.rootTag!!.subTags
        fun change(index: Int, version: String) = object : VersionEdit {
            override val element = tags[index]
            override val expected = "1.0.0"
            override val latest = version
            override val location = "same declaration label"
            override fun isValid() = true
            override fun apply() = Unit
        }
        val primary = BulkUpdatePlan(listOf(change(0, "2.0.0"), change(1, "3.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(change(1, "1.0.2")), emptyList())
        val choices = VersionTargetChoices(primary, UpdateMode.MAJOR, mapOf(UpdateMode.PATCH to patch))
        assertEquals(listOf("2.0.0"), choices.options(0).map { it.edit.latest })
        assertEquals(listOf("3.0.0", "1.0.2"), choices.options(1).map { it.edit.latest })
    }

    fun testChoosingATargetRetiresHeldSelectionAndKeepsItsCheckbox() {
        val major = BulkUpdatePlan(listOf(edit("dialog", "2.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("dialog", "1.0.2")), emptyList())
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "Test", major)
        try {
            dialog.configure(PreparedVersionPreview(major, System.nanoTime(), 0, 1,
                targetPlans = mapOf(UpdateMode.PATCH to patch)), true)
            dialog.table.setRowSelectionInterval(0, 0)
            val held = dialog.selectedPlan()
            dialog.targetSelector.selectedIndex = 1
            assertFalse(held.apply(project))
            assertEquals("1.0.0", major.changes.single().element!!.text)
            assertEquals(setOf(0), dialog.model.selectedIndices)
            assertEquals("1.0.2", dialog.model.getValueAt(0, 3))
            assertEquals("1.0.2", dialog.selectedPlan().changes.single().latest)
            dialog.targetSelector.selectedIndex = 0
            assertEquals("2.0.0", dialog.selectedPlan().changes.single().latest)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testCancelledPopupDoesNotCommitOrRetireTheReviewedTarget() {
        val primary = BulkUpdatePlan(listOf(edit("cancel-popup", "2.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("cancel-popup", "1.0.2")), emptyList())
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "Test", primary)
        try {
            dialog.configure(PreparedVersionPreview(primary, System.nanoTime(), 0, 1,
                targetPlans = mapOf(UpdateMode.PATCH to patch)), true)
            dialog.table.setRowSelectionInterval(0, 0)
            val held = dialog.selectedPlan()
            val event = PopupMenuEvent(dialog.targetSelector)
            dialog.targetPopupListener.popupMenuWillBecomeVisible(event)
            dialog.targetSelector.selectedIndex = 1
            assertEquals("2.0.0", dialog.selectedPlan().changes.single().latest)
            dialog.targetPopupListener.popupMenuCanceled(event)
            dialog.targetPopupListener.popupMenuWillBecomeInvisible(event)
            assertEquals(0, dialog.targetSelector.selectedIndex)
            assertEquals("2.0.0", dialog.selectedPlan().changes.single().latest)
            assertTrue("Cancelling a popup keeps the original reviewed selection valid", held.apply(project))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testCommittedPopupTargetRetiresTheHeldSelection() {
        val primary = BulkUpdatePlan(listOf(edit("commit-popup", "2.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("commit-popup", "1.0.2")), emptyList())
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "Test", primary)
        try {
            dialog.configure(PreparedVersionPreview(primary, System.nanoTime(), 0, 1,
                targetPlans = mapOf(UpdateMode.PATCH to patch)), true)
            dialog.table.setRowSelectionInterval(0, 0)
            val held = dialog.selectedPlan()
            val event = PopupMenuEvent(dialog.targetSelector)
            dialog.targetPopupListener.popupMenuWillBecomeVisible(event)
            dialog.targetSelector.selectedIndex = 1
            dialog.targetPopupListener.popupMenuWillBecomeInvisible(event)
            assertEquals("1.0.2", dialog.selectedPlan().changes.single().latest)
            assertFalse(held.apply(project))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testAlternativePreparationUsesModeSpecificChecksAndPreservesTheirLeases() {
        val initial = edit("native-checks", "2.0.0")
        val file = initial.element!!.containingFile.virtualFile
        val declaration = VersionDeclaration(DeclarationId(file.path, "version"), ArtifactId("test", "library"), "1.0.0", "1.0.0")
        val snapshot = BuildSnapshot(BuildContextId("target-test", file.parent.path, file.path), file.path,
            BuildFingerprint(mapOf(file.path to "1.0.0"), "test"), listOf(declaration))
        val checked = mutableListOf<UpdateMode>()
        val adapter = object : BuildSystemAdapter {
            override val id = "target-test"
            override val displayName = "Test"
            override val capabilities = AdapterCapabilities()
            override fun supports(project: Project, selection: BuildSelection) = true
            override fun isOffline(project: Project) = false
            override fun snapshot(project: Project, file: VirtualFile) = snapshot
            override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
            override suspend fun discover(project: Project, selection: BuildSelection) = listOf(snapshot)
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                checked += mode
                return UpdateReport(listOf(UpdateCandidate(declaration, when (mode) {
                    UpdateMode.PATCH -> "1.0.2"
                    UpdateMode.MINOR -> "1.2.0"
                    UpdateMode.MAJOR -> "2.0.0"
                })))
            }
            override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan {
                val target = reports.values.single().candidates.single().version
                return BulkUpdatePlan(listOf(object : VersionEdit by initial {
                    override val latest = target
                    override fun apply() { FileDocumentManager.getInstance().getDocument(file)!!.setText(target) }
                }), emptyList())
            }
        }
        val preparer = VersionPreviewPreparer(project)
        background { preparer.prepare(UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT, null, listOf(adapter), false) }
        assertEquals(listOf(UpdateMode.MAJOR), checked)
        val prepared = background {
            preparer.prepare(UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT, null, listOf(adapter), false, true)
        }
        assertEquals(listOf(UpdateMode.MAJOR, UpdateMode.PATCH, UpdateMode.MINOR), checked)
        val choices = VersionTargetChoices(prepared.plan, UpdateMode.MAJOR, prepared.targetPlans)
        assertEquals(setOf("2.0.0", "1.0.2", "1.2.0"), choices.options(0).map { it.edit.latest }.toSet())
        choices.choose(0, choices.options(0).single { it.mode == UpdateMode.PATCH })
        val held = choices.plan(setOf(0))
        background { project.service<VersionCheckService>().checkNow(adapter, snapshot, UpdateMode.PATCH, reuseCached = false) }
        assertFalse("Replacing the patch result must retire its prepared choice", held.apply(project))
        assertEquals("1.0.0", initial.element!!.text)
    }

    fun testRefreshingRetiresOldTargetsAndRequiresSelectionForChangedVersions() {
        val major = BulkUpdatePlan(listOf(edit("refresh", "2.0.0")), emptyList())
        val patch = BulkUpdatePlan(listOf(edit("refresh", "1.0.2")), emptyList())
        val dialog = BulkUpdateDialog(project, UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT.label, "Test", major)
        try {
            dialog.configure(PreparedVersionPreview(major, System.nanoTime(), 0, 1,
                targetPlans = mapOf(UpdateMode.PATCH to patch)), true)
            dialog.table.setRowSelectionInterval(0, 0)
            dialog.targetSelector.selectedIndex = 1
            val held = dialog.selectedPlan()
            dialog.replacePrepared(PreparedVersionPreview(major, System.nanoTime(), 0, 1),
                UpdateMode.MAJOR, UpdateScope.WHOLE_PROJECT, "Test", true)
            assertFalse(held.apply(project))
            assertTrue(dialog.model.selectedIndices.isEmpty())
            dialog.table.setRowSelectionInterval(0, 0)
            assertEquals(1, dialog.targetSelector.itemCount)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
