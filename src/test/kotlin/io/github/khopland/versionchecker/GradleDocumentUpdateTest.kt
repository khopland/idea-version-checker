package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleDeclarations
import io.github.khopland.versionchecker.gradle.GradleVersionEdit

class GradleDocumentUpdateTest : BasePlatformTestCase() {
    private fun changes(file: PsiFile, version: (Int) -> String = { "12.345.678" }): List<VersionEdit> {
        val original = file.text
        return GradleDeclarations.parse(file.virtualFile.path, original).mapIndexed { index, declaration ->
            GradleVersionEdit(file, declaration.range!!, version(index), declaration.declaration.id.location, original)
        }
    }

    private fun commits(file: PsiFile): () -> Int {
        var count = 0
        var awaitingCommit = false
        val manager = PsiDocumentManager.getInstance(project)
        manager.getDocument(file)!!.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (awaitingCommit) return
                awaitingCommit = true
                manager.performForCommittedDocument(event.document) {
                    count++
                    awaitingCommit = false
                }
            }
        }, testRootDisposable)
        return { count }
    }

    fun testTenThousandDenseEditsInCombinedPlansCommitOnceAndUndoTogether() {
        val original = buildString {
            append("dependencies {\n")
            repeat(10_000) { append("  implementation 'g:library$it:1.0' // keep $it\n") }
            append("}\n")
        }
        val file = myFixture.addFileToProject("dense/build.gradle", original)
        val versions: (Int) -> String = { if (it % 2 == 0) "20.30.400" else "2" }
        val edits = changes(file, versions)
        assertEquals(10_000, edits.size)
        // Combining wraps edit implementations; batching must survive that delegation.
        val plan = BulkUpdatePlan.combine(edits.chunked(2_500).map { BulkUpdatePlan(it.reversed(), emptyList()) })
        val editor = FileEditorManager.getInstance(project).openFile(file.virtualFile, true).filterIsInstance<TextEditor>().single()
        val commitCount = commits(file)
        val started = System.nanoTime()
        assertTrue(plan.apply(project))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val expected = buildString {
            append("dependencies {\n")
            repeat(10_000) { append("  implementation 'g:library$it:${versions(it)}' // keep $it\n") }
            append("}\n")
        }
        assertEquals(expected, file.text)
        assertEquals("PSI commits must scale with files, not declarations", 1, commitCount())
        println("GRADLE_DENSE_APPLY declarations=10000 files=1 psiCommits=${commitCount()} elapsedMs=$elapsedMs")
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(original, file.text)
    }

    fun testSelectionAcrossFilesPreservesUnselectedVersionsAndCommitsEachFileOnce() {
        val groovy = myFixture.addFileToProject("selected/build.gradle", "dependencies { implementation 'g:a:1.0'; implementation 'g:b:1.0' }")
        val kotlin = myFixture.addFileToProject("selected/child/build.gradle.kts", "dependencies { implementation(\"g:c:1.0\"); implementation(\"g:d:1.0\") }")
        val groovyCommits = commits(groovy)
        val kotlinCommits = commits(kotlin)
        val plan = BulkUpdatePlan.combine(listOf(BulkUpdatePlan(changes(groovy), emptyList()), BulkUpdatePlan(changes(kotlin) { "2" }, emptyList())))
        assertTrue(plan.select(setOf(0, 3)).apply(project))
        assertEquals("dependencies { implementation 'g:a:12.345.678'; implementation 'g:b:1.0' }", groovy.text)
        assertEquals("dependencies { implementation(\"g:c:1.0\"); implementation(\"g:d:2\") }", kotlin.text)
        assertEquals(1, groovyCommits())
        assertEquals(1, kotlinCommits())
    }

    fun testUnchangedTextBetweenUpdatesRetainsCaretAndRangeMarkers() {
        val file = myFixture.configureByText("build.gradle", """dependencies {
            implementation 'g:a:1.0'
            // unchanged anchor
            implementation 'g:b:1.0'
        }""".trimIndent())
        val editor = myFixture.editor
        val anchor = file.text.indexOf("anchor")
        editor.caretModel.moveToOffset(anchor + 3)
        val marker = editor.document.createRangeMarker(anchor, anchor + "anchor".length)
        try {
            assertTrue(BulkUpdatePlan(changes(file), emptyList()).apply(project))
            assertEquals(file.text.indexOf("anchor") + 3, editor.caretModel.offset)
            assertTrue("Unchanged ranges must survive version-only updates", marker.isValid)
            assertEquals("anchor", editor.document.getText(TextRange(marker.startOffset, marker.endOffset)))
        } finally { marker.dispose() }
    }

    fun testUncommittedChangesInAnyFileRejectTheEntirePlan() {
        val first = myFixture.addFileToProject("stale/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        val second = myFixture.addFileToProject("stale/child/build.gradle", "dependencies { implementation 'g:b:1.0' }")
        val plan = BulkUpdatePlan.combine(listOf(BulkUpdatePlan(changes(first), emptyList()), BulkUpdatePlan(changes(second), emptyList())))
        val original = first.text
        val document = PsiDocumentManager.getInstance(project).getDocument(second)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " // unsaved change") }
        val firstCommits = commits(first)
        assertFalse(plan.apply(project))
        assertEquals(original, first.text)
        assertEquals(0, firstCommits())
        assertTrue(document.text.endsWith(" // unsaved change"))
    }

    fun testOverlappingOrInconsistentCapturesRejectAllFilesBeforeWriting() {
        val first = myFixture.addFileToProject("overlap/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        val second = myFixture.addFileToProject("overlap/child/build.gradle", "dependencies { implementation 'g:b:1.0' }")
        val edit = changes(second).single()
        val firstCommits = commits(first)
        assertFalse(BulkUpdatePlan(changes(first) + listOf(edit, edit), emptyList()).apply(project))
        val inconsistent = GradleVersionEdit(second, TextRange(0, 1), "x", "stale", second.text + " ")
        assertFalse(BulkUpdatePlan(changes(first) + listOf(edit, inconsistent), emptyList()).apply(project))
        assertEquals(0, firstCommits())
        assertTrue(second.text.contains("g:b:1.0"))
    }

    fun testInvalidElementEditOrPlanGuardRejectsPreparedDocumentChanges() {
        val file = myFixture.addFileToProject("mixed/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        var elementApplied = false
        val invalid = object : VersionEdit {
            override val element = file
            override val expected = "1.0"
            override val latest = "2.0"
            override val location = "other provider"
            override fun isValid() = false
            override fun apply() { elementApplied = true }
        }
        val count = commits(file)
        assertFalse(BulkUpdatePlan(changes(file) + invalid, emptyList()).apply(project))
        assertFalse(BulkUpdatePlan(changes(file), emptyList()).guardedBy { false }.apply(project))
        assertEquals(0, count())
        assertFalse(elementApplied)
        assertTrue(file.text.contains("g:a:1.0"))
    }
}
