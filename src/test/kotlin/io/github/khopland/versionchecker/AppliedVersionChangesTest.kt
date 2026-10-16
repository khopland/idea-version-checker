package io.github.khopland.versionchecker

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.npm.NpmVersionEdit

class AppliedVersionChangesTest : BasePlatformTestCase() {
    private fun edit(file: PsiFile, valid: () -> Boolean = { true }, change: () -> Unit) = object : VersionEdit {
        override val element = file
        override val expected = "before"
        override val latest = "after"
        override val location = file.name
        override fun isValid() = valid()
        override fun apply() = change()
    }

    fun testMultipleDocumentEditsCaptureOneActualWholeFileAndSelectionCapturesOnlyChosenChanges() {
        val file = myFixture.configureByText("package.json", """{"dependencies":{"a":"1.0","b":"1.0"}}""")
        val before = file.text
        val values = PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java).filter { it.value == "1.0" }
        val edits = values.map { NpmVersionEdit(it, "2.0", file.name) }
        val all = BulkUpdatePlan(edits, emptyList())
        val selected = all.select(setOf(0))
        assertTrue(selected.apply(project))
        assertNull(all.appliedChanges)
        val captured = selected.appliedChanges!!.files.single()
        assertEquals(before, captured.before)
        assertEquals(file.text, captured.after)
        assertEquals(1, Regex("2.0").findAll(captured.after).count())
        val other = myFixture.configureByText("package.json", before)
        val both = PsiTreeUtil.findChildrenOfType(other, JsonStringLiteral::class.java).filter { it.value == "1.0" }
            .map { NpmVersionEdit(it, "2.0", other.name) }
        val plan = BulkUpdatePlan(both, emptyList())
        assertTrue(plan.apply(project))
        assertEquals(1, plan.appliedChanges!!.files.size)
        assertEquals(before.replace("1.0", "2.0"), plan.appliedChanges!!.files.single().after)
    }

    fun testPsiInsertionCapturesFormattedActualResultAndSnapshotsSurviveUndoAndLaterChanges() {
        val file = myFixture.configureByText("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>") as XmlFile
        val before = file.text
        val editor = FileEditorManager.getInstance(project).openFile(file.virtualFile, true).filterIsInstance<TextEditor>().single()
        val plan = BulkUpdatePlan(listOf(edit(file) {
            val root = file.rootTag!!
            val properties = root.addSubTag(root.createChildTag("properties", root.namespace, null, false), false)
            properties.addSubTag(root.createChildTag("library.version", root.namespace, "2.0", false), false)
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(properties)
        }), emptyList())
        assertTrue(plan.apply(project))
        val captured = plan.appliedChanges!!.files.single()
        assertEquals(before, captured.before)
        assertEquals(file.text, captured.after)
        assertTrue(captured.after.contains("<library.version>2.0</library.version>"))
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before, file.text)
        WriteCommandAction.runWriteCommandAction(project) { file.rootTag!!.setAttribute("changed", "later") }
        assertEquals(before, captured.before)
        assertTrue(captured.after.contains("<library.version>2.0</library.version>"))
        assertFalse(captured.after.contains("changed="))
        val request = AppliedVersionChangesService.requests(project, plan.appliedChanges!!).single()
        assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        assertTrue(request.title!!.contains("historical snapshots, not current files"))
        assertEquals(captured.before, (request.contents[0] as DocumentContent).document.text)
        assertEquals(captured.after, (request.contents[1] as DocumentContent).document.text)
    }

    fun testRejectedNoOpEmptyAndRepeatedFailedApplyDoNotExposeAnEarlierSuccessfulSnapshot() {
        val file = myFixture.configureByText("pom.xml", "<project><version>1</version></project>") as XmlFile
        var valid = true
        val plan = BulkUpdatePlan(listOf(edit(file, { valid }) { file.rootTag!!.findFirstSubTag("version")!!.value.setText("2") }), emptyList())
        assertTrue(plan.apply(project)); assertNotNull(plan.appliedChanges)
        valid = false
        assertFalse(plan.apply(project)); assertNull(plan.appliedChanges)
        val noOp = BulkUpdatePlan(listOf(edit(file) { }), emptyList())
        assertTrue(noOp.apply(project)); assertNull(noOp.appliedChanges)
        val empty = BulkUpdatePlan(emptyList(), emptyList())
        assertTrue(empty.apply(project)); assertNull(empty.appliedChanges)
        var changed = false
        val rejected = BulkUpdatePlan(listOf(edit(file) { changed = true }), emptyList(), isCurrent = { false })
        assertFalse(rejected.apply(project)); assertFalse(changed); assertNull(rejected.appliedChanges)
    }

    fun testThrownEditNeverPublishesSuccessEvenIfEarlierEditChangedAFile() {
        val file = myFixture.configureByText("pom.xml", "<project><version>1</version></project>") as XmlFile
        val plan = BulkUpdatePlan(listOf(edit(file) { file.rootTag!!.findFirstSubTag("version")!!.value.setText("2") },
            edit(file) { throw IllegalStateException("Edit failed") }), emptyList())
        try { plan.apply(project); fail("Expected edit failure") } catch (_: IllegalStateException) { }
        assertNull(plan.appliedChanges)
    }

    fun testCaptureLimitsOmitWholeFilesAndDoNotAffectTheAuthorizedEdits() {
        val small = myFixture.addFileToProject("small.xml", "<project/>") as XmlFile
        val large = myFixture.addFileToProject("large.xml", "<project>" + " ".repeat(1_000) + "</project>") as XmlFile
        val edits = listOf(small, large).map { file -> edit(file) { file.rootTag!!.setAttribute("updated", "true") } }
        lateinit var capture: AppliedVersionChangeCapture
        WriteCommandAction.runWriteCommandAction(project) {
            capture = AppliedVersionChangeCapture.before(edits, AppliedVersionChangeCapture.Limits(files = 1, characters = 200))!!
            edits.forEach { it.apply() }
        }
        lateinit var result: AppliedVersionChanges
        WriteCommandAction.runWriteCommandAction(project) { result = capture.finish()!! }
        assertEquals(listOf(small.virtualFile.path), result.files.map { it.path })
        assertEquals(1, result.omittedTargetFiles)
        assertTrue(result.textCharacters <= 200)
        assertEquals("true", large.rootTag!!.getAttributeValue("updated"))
    }

    fun testEntirelyOmittedTargetsRetainDisclosureAndSnapshotFailureDoesNotFailCommittedEdit() {
        val file = myFixture.configureByText("pom.xml", "<project><version>1</version></project>") as XmlFile
        lateinit var capture: AppliedVersionChangeCapture
        WriteCommandAction.runWriteCommandAction(project) {
            capture = AppliedVersionChangeCapture.before(listOf(edit(file) { }), AppliedVersionChangeCapture.Limits(characters = 10))!!
            file.rootTag!!.findFirstSubTag("version")!!.value.setText("2")
            val omitted = capture.finish()!!
            assertEmpty(omitted.files)
            assertEquals(1, omitted.omittedTargetFiles)
            assertNotNull(AppliedVersionChangesStore().retain(omitted))
        }
        val deleted = myFixture.addFileToProject("deleted.xml", "<project/>")
        val plan = BulkUpdatePlan(listOf(edit(deleted) { deleted.delete() }), emptyList())
        assertTrue("Optional snapshot failure must not turn applied edits into an unsuccessful Boolean result", plan.apply(project))
        assertTrue(plan.appliedChanges!!.captureUnavailable)
        assertEmpty(plan.appliedChanges!!.files)
    }

    fun testPayloadBudgetEvictsBeforeCountLimit() {
        val store = AppliedVersionChangesStore(records = 10, characters = 5)
        val a = store.retain(AppliedVersionChanges(listOf(AppliedVersionFile("a", "1", "2"))))!!
        val b = store.retain(AppliedVersionChanges(listOf(AppliedVersionFile("b", "11", "22"))))!!
        assertNull(store.get(a))
        assertNotNull(store.get(b))
    }

    fun testStoreEvictsByPayloadAndCountAndDoesNotRetainMutableLists() {
        val file = AppliedVersionFile("pom.xml", "1", "2")
        val mutable = mutableListOf(file)
        val first = AppliedVersionChanges(mutable)
        mutable.clear()
        assertEquals(listOf(file), first.files)
        val store = AppliedVersionChangesStore(records = 2, characters = 5)
        val a = store.retain(first)!!
        val b = store.retain(AppliedVersionChanges(listOf(file.copy(path = "other.xml"))))!!
        val c = store.retain(AppliedVersionChanges(listOf(file.copy(path = "last.xml"))))!!
        assertNull(store.get(a)); assertNotNull(store.get(b)); assertNotNull(store.get(c))
        assertNull(store.retain(AppliedVersionChanges(listOf(file.copy(before = "oversized")))))
        assertNotNull(store.get(c))
        assertNull(store.retain(AppliedVersionChanges(List(51) { AppliedVersionFile("file-$it", "", "") })))
        assertNotNull(store.get(c))
        store.clear()
        assertNull(store.get(b)); assertNull(store.get(c))
    }
}
