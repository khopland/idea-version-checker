package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.FileDigestCache
import io.github.khopland.versionchecker.core.sha256

class FileDigestCacheTest : BasePlatformTestCase() {
    fun testUnsavedEditsChangeCurrentDigestAndPreserveSavedBaseline() {
        val file = myFixture.addFileToProject("inputs/config.txt", "saved").virtualFile
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)!!
        documents.saveDocument(document)
        val cache = FileDigestCache()
        val baseline = cache.get(file)
        assertEquals(baseline.saved, baseline.current)
        assertSame(baseline, cache.get(file))

        WriteCommandAction.runWriteCommandAction(project) { document.setText("edited") }
        val edited = cache.get(file)
        assertEquals(baseline.saved, edited.saved)
        assertFalse(baseline.current == edited.current)
        assertSame(edited, cache.get(file))

        documents.saveDocument(document)
        val saved = cache.get(file)
        assertEquals(sha256("edited".toByteArray()), saved.saved)
        assertEquals(saved.saved, saved.current)
        assertFalse(edited.current == saved.current)
    }

    fun testSavedFileChangesInvalidateDigestWithACachedDocument() {
        val file = myFixture.addFileToProject("inputs/config.txt", "first").virtualFile
        FileDocumentManager.getInstance().getDocument(file)!!
        val cache = FileDigestCache()
        val before = cache.get(file)
        WriteCommandAction.runWriteCommandAction(project) { file.setBinaryContent("second".toByteArray()) }
        val after = cache.get(file)
        assertFalse(before.saved == after.saved)
        assertEquals(sha256(file.contentsToByteArray()), after.saved)
        assertEquals(after.saved, after.current)
    }

    fun testIdenticalUnsavedTextStillHasASeparateIdentityUntilSaved() {
        val file = myFixture.addFileToProject("inputs/config.txt", "original").virtualFile
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)!!
        documents.saveDocument(document)
        val cache = FileDigestCache()
        val saved = cache.get(file)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("temporary")
            document.setText("original")
        }
        assertTrue(documents.isDocumentUnsaved(document))
        val unsaved = cache.get(file)
        assertEquals(saved.saved, unsaved.saved)
        assertFalse(saved.current == unsaved.current)
    }
}
