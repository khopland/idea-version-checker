package io.github.khopland.versionchecker.core

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Runs the real annotator, quick fixes and inspection profile against a fake provider. */
class AnnotatorTest : BasePlatformTestCase() {
    private class FakeLookup(override val key: String, private val versions: List<String>, private val deprecated: Map<String, String> = emptyMap()) : Lookup {
        override suspend fun fetch() = if (versions.isEmpty()) throw HttpFailure("repo needs credentials") else Releases(versions, deprecated)
    }

    /** `<lib name="x" version="1.0"/>`: each version attribute is a declaration. Names that start with "shared" share one edit. */
    private class FakeProvider : DeclarationProvider {
        override val fileNames = listOf("fake.xml")
        override fun accepts(file: PsiFile) = file.name == "fake.xml"
        override fun collect(file: PsiFile): List<Declaration> {
            val text = file.text
            val shared = Regex("""<shared>([^<]+)</shared>""").find(text)
            return Regex("""<lib name="([^"]+)" version="([^"]+)"/>""").findAll(text).map { match ->
                val name = match.groupValues[1]
                val group = match.groups[2]!!
                val range = TextRange(group.range.first, group.range.last + 1)
                val edit = if (name.startsWith("shared") && shared != null)
                    Edit(file.virtualFile, TextRange(shared.groups[1]!!.range.first, shared.groups[1]!!.range.last + 1), shared.groupValues[1], "shared")
                else Edit(file.virtualFile, range, group.value)
                Declaration(name, range, group.value, FakeLookup(name, VERSIONS[name].orEmpty(), mapOf("1.0.0" to "Use other")), edit)
            }.toList()
        }
    }

    override fun setUp() {
        super.setUp()
        DeclarationProvider.EP.point.registerExtension(FakeProvider(), testRootDisposable)
        myFixture.enableInspections(OutdatedVersionInspection())
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

    fun testDisabledInspectionHidesWarnings() {
        myFixture.disableInspections(OutdatedVersionInspection())
        myFixture.configureByText("fake.xml", """<libs><lib name="a" version="1.0.0"/></libs>""")
        assertTrue(myFixture.doHighlighting().none { it.description?.startsWith("a ") == true })
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

    companion object {
        private val VERSIONS = mapOf(
            "a" to listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0", "2.0.0", "3.0.0-beta"),
            "current" to listOf("1.0.0", "2.0.0"),
            "shared-x" to listOf("1.0.0", "1.0.1", "1.2.0", "2.0.0"),
            "shared-y" to listOf("1.0.0", "1.0.1", "1.1.0"))
    }
}
