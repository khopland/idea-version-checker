package io.github.khopland.versionchecker

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.Change
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.DependencyReport
import io.github.khopland.versionchecker.core.Edit
import io.github.khopland.versionchecker.core.Edits
import io.github.khopland.versionchecker.core.FakeProvider
import io.github.khopland.versionchecker.core.UpdateKind
import io.github.khopland.versionchecker.core.VersionService
import io.github.khopland.versionchecker.core.VersionsChangedListener
import com.intellij.openapi.util.TextRange

/** The Dependency Updates tool window against a fake provider: `<lib name="x" version="1.0"/>`. */
class DependencyUpdatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        DeclarationProvider.EP.point.registerExtension(FakeProvider(), testRootDisposable)
        VersionService.getInstance().clear()
    }

    private fun check(): DependencyReport.State {
        val job = DependencyReport.getInstance(project).refresh()
        waitUntil { job.isCompleted }
        return DependencyReport.getInstance(project).current.value
    }

    private fun waitUntil(state: () -> Any? = { null }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("Timed out: ${state()}", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun document(file: PsiFile) = PsiDocumentManager.getInstance(project).getDocument(file)!!

    fun testListsOutdatedDeprecatedAndUncheckedVersions() {
        myFixture.addFileToProject("app/fake.xml", """
            <libs>
              <lib name="current" version="2.0.0"/>
              <lib name="broken" version="1.0.0"/>
              <lib name="a" version="1.0.0"/>
            </libs>
        """.trimIndent())
        myFixture.addFileToProject("lib/fake.xml", """
            <libs>
              <!--suppress OutdatedDependencyVersion -->
              <lib name="a" version="1.0.0"/>
              <lib name="shared-y" version="1.0.1"/>
            </libs>
        """.trimIndent())
        val state = check()
        assertTrue(state.done)
        assertEquals("The suppressed version is not counted", 4, state.checked)
        assertEquals(2, state.files)
        // Up-to-date versions are left out, and versions that could not be checked come last.
        assertEquals(listOf("a app/fake.xml:4", "shared-y lib/fake.xml:4", "broken app/fake.xml:3"),
            state.rows.map { "${it.declaration.name} ${it.file.parent.name}/${it.file.name}:${it.line}" })

        val a = state.rows[0]
        assertEquals(listOf("1.0.1", "1.2.0", "2.0.0"), UpdateKind.entries.map { a.offered(it) })
        assertEquals("a 1.0.0 → 1.0.1 (patch) · 1.2.0 (minor) · 2.0.0 (major). 1.0.0 is deprecated: Use other", a.message)
        val shared = state.rows[1]
        assertEquals("Only the column of the update's kind is filled", listOf(null, "1.1.0", null), UpdateKind.entries.map { shared.offered(it) })
        assertEquals(mapOf(UpdateKind.MINOR to "1.1.0", UpdateKind.MAJOR to "1.1.0"), shared.fixes)
        assertEquals("Could not check broken: repo needs credentials", state.rows[2].message)
    }

    fun testUpdatesTheSelectedRowsAsOneUndoableEdit() {
        val app = myFixture.addFileToProject("app/fake.xml", """<libs><lib name="a" version="1.0.0"/></libs>""")
        val lib = myFixture.addFileToProject("lib/fake.xml", """<libs><lib name="shared-y" version="1.0.0"/></libs>""")
        val panel = DependencyUpdatesPanel(project, testRootDisposable)
        check()
        waitUntil { panel.table.items.size == 2 }

        panel.table.selection = panel.table.items.filter { it.declaration.name == "a" }
        panel.update(UpdateKind.MINOR)
        assertEquals("""<libs><lib name="a" version="1.2.0"/></libs>""", document(app).text)
        assertEquals("""<libs><lib name="shared-y" version="1.0.0"/></libs>""", document(lib).text)

        check()
        waitUntil { panel.table.items.any { it.declaration.current == "1.2.0" } }
        assertEquals("The selection survives the new check", listOf("a"), panel.table.selectedObjects.map { it.declaration.name })

        // Without a selection, every row updates.
        panel.table.clearSelection()
        panel.update(UpdateKind.PATCH)
        assertEquals("""<libs><lib name="shared-y" version="1.0.1"/></libs>""", document(lib).text)
        assertEquals("a 1.2.0 has no patch update", """<libs><lib name="a" version="1.2.0"/></libs>""", document(app).text)

        UndoManager.getInstance(project).undo(null)
        assertEquals("""<libs><lib name="shared-y" version="1.0.0"/></libs>""", document(lib).text)
    }

    fun testEmptyListSaysWhy() {
        val panel = DependencyUpdatesPanel(project, testRootDisposable)
        myFixture.addFileToProject("fake.xml", """<libs><lib name="current" version="2.0.0"/></libs>""")
        check()
        waitUntil({ panel.table.emptyText.text }) { panel.table.emptyText.text == "All dependencies are up to date" }

        VersionCheckerSettings.getInstance().state.enabled = false
        try {
            check()
            waitUntil({ panel.table.emptyText.text }) { panel.table.emptyText.text == "Version checks are turned off" }
        } finally {
            VersionCheckerSettings.getInstance().state.enabled = true
        }
    }

    fun testEditsTellTheListToCheckAgain() {
        val file = myFixture.addFileToProject("fake.xml", """<libs><lib name="a" version="1.0.0"/></libs>""")
        var changed = 0
        project.messageBus.connect(testRootDisposable).subscribe(VersionsChangedListener.TOPIC, VersionsChangedListener { changed++ })
        val start = file.text.indexOf("1.0.0")
        val edit = Edit(file.virtualFile, TextRange(start, start + 5), "1.0.0")
        Edits.apply(project, listOf(Change(edit, "1.0.1")), "Update")
        assertEquals(1, changed)
        Edits.apply(project, listOf(Change(edit, "1.0.2")), "Update")
        assertEquals("Nothing changes when the text is no longer as expected", 1, changed)
    }
}
