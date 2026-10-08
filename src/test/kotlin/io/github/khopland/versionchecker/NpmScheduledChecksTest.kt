package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/** Real npm discovery and coordinator eligibility, with a counted repository boundary. */
class NpmScheduledChecksTest : BasePlatformTestCase() {
    fun testUnsavedRegistryAndRuntimeInputsSkipOnlyAffectedWorkspacesAndResumeAfterSave() {
        val affected = myFixture.addFileToProject("scheduled/affected/package.json", """{"dependencies":{"alpha":"1.0.0"}}""")
        val unrelated = myFixture.addFileToProject("scheduled/unrelated/package.json", """{"dependencies":{"beta":"1.0.0"}}""")
        val npmrc = myFixture.addFileToProject("scheduled/affected/.npmrc", "registry=https://example.invalid/\n")
        val nvmrc = myFixture.addFileToProject("scheduled/affected/.nvmrc", "22\n")
        val ancestor = myFixture.addFileToProject("scheduled/.node-version", "22\n")
        val native = NpmBuildSystemAdapter()
        val queried = CopyOnWriteArrayList<String>()
        val adapter = object : BuildSystemAdapter by native {
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                queried += snapshot.context.root
                return UpdateReport()
            }
        }
        ExtensionTestUtil.maskExtensions(BuildSystemAdapter.EP, listOf(adapter), testRootDisposable)
        val service = project.service<VersionCheckService>()
        fun refresh() = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { service.refreshScheduled() }
        }), 10_000)
        for (config in listOf(npmrc, nvmrc)) {
            val document = FileDocumentManager.getInstance().getDocument(config.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text + "\n") }
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            queried.clear()
            refresh()
            assertEquals(listOf(unrelated.virtualFile.parent.path), queried.toList())
            assertTrue("The timer must not save configuration", FileDocumentManager.getInstance().isDocumentUnsaved(document))
            FileDocumentManager.getInstance().saveDocument(document)
            queried.clear()
            refresh()
            assertEquals(setOf(affected.virtualFile.parent.path, unrelated.virtualFile.parent.path), queried.toSet())
        }
        val document = FileDocumentManager.getInstance().getDocument(ancestor.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("24\n") }
        queried.clear()
        refresh()
        assertTrue("Unsaved ancestor runtime selection affects both workspaces", queried.isEmpty())
        FileDocumentManager.getInstance().saveDocument(document)
        refresh()
        assertEquals(2, queried.size)
    }
}
