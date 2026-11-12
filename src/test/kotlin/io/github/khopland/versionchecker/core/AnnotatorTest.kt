package io.github.khopland.versionchecker.core

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.milliseconds

/** Runs the real annotator, quick fixes and inspection profile against a fake provider. */
class AnnotatorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        DeclarationProvider.EP.point.registerExtension(FakeProvider(), testRootDisposable)
        myFixture.enableInspections(OutdatedVersionInspection(), MovedDependencyInspection())
        VersionService.getInstance().clear()
    }

    fun testWarningsFixesAndFailures() {
        myFixture.configureByText("fake.xml", """
            <libs>
              <lib name="a" version="1.0.0"/>
              <lib name="current" version="2.0.0"/>
              <lib name="broken" version="1.0.0"/>
            </libs>
        """.trimIndent())
        val highlights = myFixture.doHighlighting().filter { it.description != null }
        val warning = highlights.single { it.severity == HighlightSeverity.WARNING }
        assertEquals("a 1.0.0 → 1.0.1 (patch) · 1.2.0 (minor) · 2.0.0 (major). 1.0.0 is deprecated: Use other", warning.description)
        assertTrue(highlights.any { it.severity == HighlightSeverity.WEAK_WARNING && it.description == "Could not check broken: repo needs credentials" })

        myFixture.editor.caretModel.moveToOffset(warning.startOffset + 1)
        val texts = myFixture.availableIntentions.map { it.text }
        assertContainsElements(texts, "Update to 1.0.1 (patch)", "Update to 1.2.0 (minor)", "Update to 2.0.0 (major)", "Update all versions in this file…")
        myFixture.launchAction(myFixture.findSingleIntention("Update to 1.2.0 (minor)"))
        assertTrue(myFixture.editor.document.text.contains("""<lib name="a" version="1.2.0"/>"""))
    }

    fun testRepeatedFailuresShowOnceForTheFile() {
        myFixture.configureByText("fake.xml", """
            <libs>
              <lib name="broken" version="1.0.0"/>
              <lib name="broken-too" version="1.0.0"/>
              <lib name="a" version="1.0.0"/>
            </libs>
        """.trimIndent())
        val weak = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WEAK_WARNING }
        assertEquals(listOf("Could not check 2 versions: repo needs credentials"), weak.map { it.description })
        assertTrue(weak.single().isFileLevelAnnotation)
    }

    fun testSharedVersionNeedsAgreement() {
        myFixture.configureByText("fake.xml", """
            <libs><shared>1.0.0</shared>
              <lib name="shared-x" version="1.0.0"/>
              <lib name="shared-y" version="1.0.0"/>
            </libs>
        """.trimIndent())
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("1.0.0\"/>") + 1)
        val texts = myFixture.availableIntentions.map { it.text }
        // x wants 1.0.1/1.2.0/2.0.0 and y wants 1.0.1/1.1.0/1.1.0; only the patch update agrees.
        assertContainsElements(texts, "Update shared to 1.0.1 (patch)")
        assertDoesntContain(texts, "Update shared to 1.2.0 (minor)", "Update shared to 2.0.0 (major)")
    }

    fun testSlowRepositoryShowsLaterThanTheOthers() {
        val service = VersionService.getInstance()
        val gate = CompletableDeferred<Unit>()
        val fetch = service.fetch
        service.patience = 200.milliseconds
        service.fetch = { lookup -> if (lookup.key == "current") gate.await(); fetch(lookup) }
        try {
            myFixture.configureByText("fake.xml", """<libs><lib name="a" version="1.0.0"/><lib name="current" version="1.0.0"/></libs>""")
            fun warnings() = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WARNING }.map { it.description.substringBefore(" →") }
            assertEquals(listOf("a 1.0.0"), warnings())
            // Inspect Code has no later, so it waits.
            val batch = VersionAnnotator().collectInformation(myFixture.file)!!
            assertFalse(batch.editor)

            gate.complete(Unit)
            // The check went on after the editor stopped waiting, and the file is highlighted again when it ends.
            val checked = CompletableDeferred<Unit>()
            service.whenMore("test", FakeProvider().collect(myFixture.file)) { checked.complete(Unit) }
            PlatformTestUtil.waitWithEventsDispatching("The slow version was not checked", { checked.isCompleted }, 10)
            assertEquals(listOf("a 1.0.0", "current 1.0.0"), warnings())
        } finally {
            service.patience = null
            service.fetch = fetch
            gate.complete(Unit)
        }
    }

    fun testDisabledInspectionHidesWarnings() {
        myFixture.disableInspections(OutdatedVersionInspection())
        myFixture.configureByText("fake.xml", """<libs><lib name="a" version="1.0.0"/></libs>""")
        assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("a ") == true })
    }

    fun testMovedPackageHasItsOwnWarning() {
        myFixture.configureByText("fake.xml", """<libs><lib name="old" version="1.0.1"/><lib name="old-current" version="1.1.0"/></libs>""")
        val highlights = myFixture.doHighlighting().filter { it.description != null }
        assertEquals(listOf("old 1.0.1 → 1.1.0 (minor)"), highlights.filter { it.severity == HighlightSeverity.WARNING }.map { it.description })
        assertEquals(listOf("old has moved to org.new:old (newest 2.0.0)", "old-current has moved to org.new:old: Renamed"),
            highlights.filter { it.severity == HighlightSeverity.WEAK_WARNING }.map { it.description })

        VersionService.getInstance().clear()
        myFixture.disableInspections(MovedDependencyInspection())
        assertTrue(myFixture.doHighlighting().none { it.description?.contains("has moved") == true })
    }

    fun testSuppressCommentKeepsTheVersion() {
        myFixture.configureByText("fake.xml", """
            <libs>
              <!--suppress OutdatedDependencyVersion -->
              <lib name="a" version="1.0.0"/>
              <!--suppress MovedDependency: renamed later -->
              <lib name="old" version="1.0.1"/>
              <lib name="current" version="1.1.0"/>
            </libs>
        """.trimIndent())
        val highlights = myFixture.doHighlighting().filter { it.description != null }
        assertEquals(listOf("old 1.0.1 → 1.1.0 (minor)", "current 1.1.0 → 2.0.0 (major)"),
            highlights.filter { it.severity == HighlightSeverity.WARNING }.map { it.description })
        assertTrue(highlights.none { it.description?.contains("has moved") == true })

        // The fixes carry the platform's suppress actions.
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("1.1.0\"/>\n</libs>") + 1)
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Suppress for tag" })
        assertTrue(Regex("""<!--suppress OutdatedDependencyVersion -->\s*<lib name="current"""").containsMatchIn(myFixture.editor.document.text))
        assertTrue(myFixture.doHighlighting().none { it.severity == HighlightSeverity.WARNING && it.description?.startsWith("current") == true })
    }

    fun testSuppressCommentAboveTheLine() {
        val text = """
            //noinspection UnusedProperty, OutdatedDependencyVersion
            implementation("a:b:1.0")
            #noinspection ALL
            junit = "4.13"
            # noinspection OutdatedDependencyVersion

            guava = "33.0"
        """.trimIndent()
        fun suppressed(line: String) = Suppression.commentAbove(text, text.indexOf(line), OutdatedVersionInspection.SHORT_NAME)
        assertTrue(suppressed("implementation"))
        assertTrue(suppressed("junit"))
        assertFalse("Only the line right above counts", suppressed("guava"))
        assertFalse(Suppression.commentAbove(text, 0, OutdatedVersionInspection.SHORT_NAME))
    }

    fun testUpdateSkipsSuppressedVersions() {
        val file = myFixture.addFileToProject("fake.xml", """
            <libs>
              <!--suppress OutdatedDependencyVersion -->
              <lib name="a" version="1.0.0"/>
              <lib name="shared-y" version="1.0.0"/>
            </libs>
        """.trimIndent())
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)!!
        Updater.getInstance(project).update(listOf(file.virtualFile), UpdateKind.MINOR)
        val deadline = System.currentTimeMillis() + 10_000
        while (!document.text.contains("1.1.0") && System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(document.text.contains("""<lib name="shared-y" version="1.1.0"/>"""))
        assertTrue(document.text.contains("""<lib name="a" version="1.0.0"/>"""))
    }

    fun testUpdateAllFilesIsOneUndoableCommand() {
        val first = myFixture.addFileToProject("one/fake.xml", """<libs><lib name="a" version="1.0.0"/></libs>""")
        val second = myFixture.addFileToProject("two/fake.xml", """<libs><lib name="shared-y" version="1.0.0"/></libs>""")
        Updater.getInstance(project).update(null, UpdateKind.MINOR)
        val documents = listOf(first, second).map { com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(it)!! }
        val deadline = System.currentTimeMillis() + 10_000
        while (documents[0].text.contains("1.0.0") && System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals("""<libs><lib name="a" version="1.2.0"/></libs>""", documents[0].text)
        assertEquals("""<libs><lib name="shared-y" version="1.1.0"/></libs>""", documents[1].text)

        com.intellij.openapi.command.undo.UndoManager.getInstance(project).undo(null)
        assertEquals("""<libs><lib name="a" version="1.0.0"/></libs>""", documents[0].text)
        assertEquals("""<libs><lib name="shared-y" version="1.0.0"/></libs>""", documents[1].text)
    }
}
