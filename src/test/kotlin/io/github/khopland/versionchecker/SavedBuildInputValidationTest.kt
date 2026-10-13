package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.prepareSavedBuildInputValidation
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class SavedBuildInputValidationTest : BasePlatformTestCase() {
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)

    private fun realFiles(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("version-checker-saved-inputs-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }

    fun testUnnoticedPomAndWorkspaceMemberChangesRejectPhysicalBaseline() = realFiles { root ->
        for (name in listOf("pom.xml", "package.json", "member/package.json")) {
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, "version 1.0.0")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            assertEquals("version 1.0.0", document.text)
            val stamp = Files.getLastModifiedTime(path)
            Files.writeString(path, "version 1.0.1")
            Files.setLastModifiedTime(path, stamp)
            assertEquals("Keep the IDE's stale baseline for the regression", "version 1.0.0", document.text)
            assertFalse(background { prepareSavedBuildInputValidation(project, setOf(file.path)) }())
            assertEquals("version 1.0.1", Files.readString(path))
        }
    }

    fun testMatchingBomAndCrLfInputReadsOutsideEdtAndReadLocks() = realFiles { root ->
        val path = root.resolve("pom.xml")
        Files.writeString(path, "\uFEFF<project>\r\n</project>\r\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertEquals("<project>\n</project>\n", document.text)
        var reads = 0
        val guard = background { prepareSavedBuildInputValidation(project, setOf(file.path)) { input ->
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            reads++
            Files.readAllBytes(input)
        } }
        assertTrue(guard())
        assertEquals(1, reads)
    }

    fun testLegitimateUnsavedTextIsAllowedButAnotherEditDuringCaptureRetiresGuard() = realFiles { root ->
        val path = root.resolve("package.json")
        Files.writeString(path, "saved baseline")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("unsaved selector") }
        try {
            assertTrue(background { prepareSavedBuildInputValidation(project, setOf(file.path)) }())
            val guard = background { prepareSavedBuildInputValidation(project, setOf(file.path)) { input ->
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("changed during capture") }
                }
                Files.readAllBytes(input)
            } }
            assertFalse(guard())
            assertEquals("saved baseline", Files.readString(path))
        } finally { FileDocumentManager.getInstance().reloadFromDisk(document) }
    }

    fun testDeletedTrackedFileRejectsWithoutChangingCachedEditorText() = realFiles { root ->
        val path = root.resolve("pom.xml")
        Files.writeString(path, "saved baseline")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        Files.delete(path)
        assertFalse(background { prepareSavedBuildInputValidation(project, setOf(file.path)) }())
        assertEquals("saved baseline", document.text)
    }
}
