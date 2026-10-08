package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*

class NpmProjectCacheTest : BasePlatformTestCase() {
    fun testSourceDocumentPsiAndVfsEditsKeepWorkspaceInputsWarm() {
        myFixture.addFileToProject("warm/package.json", """{"workspaces":["packages/*"]}""")
        val member = myFixture.addFileToProject("warm/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val source = myFixture.addFileToProject("warm/app.js", "export const value = 0;\n")
        val adapter = NpmBuildSystemAdapter()
        val cache = project.service<NpmProjectCache>()
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        val files = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
        val workspace = cache.workspace(member.virtualFile) { error("Workspace was not cached") }
        val hashes = cache.manifestDigests(workspace) { error("Manifest hashes were not cached") }
        val document = FileDocumentManager.getInstance().getDocument(source.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("export const value = 1;\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
        WriteCommandAction.runWriteCommandAction(project) {
            source.virtualFile.setBinaryContent("export const value = 2;\n".toByteArray())
            source.virtualFile.rename(this, "renamed.js")
            source.virtualFile.parent.createChildData(this, "another.js")
        }
        assertSame(files, NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
        assertSame(workspace, cache.workspace(member.virtualFile) { error("Source editing rebuilt ownership") })
        assertSame(hashes, cache.manifestDigests(workspace) { error("Source editing rebuilt hashes") })
        assertTrue(adapter.isCurrent(project, snapshot))
    }

    fun testManifestEditsRebuildOwnershipWhileReusingUnchangedDigestsAndDiscovery() {
        myFixture.addFileToProject("content/package.json", """{"workspaces":["packages/*"]}""")
        val member = myFixture.addFileToProject("content/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val library = myFixture.addFileToProject("content/packages/library/package.json", """{"name":"beta"}""")
        val adapter = NpmBuildSystemAdapter()
        val cache = project.service<NpmProjectCache>()
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        val files = NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
        val workspace = cache.workspace(member.virtualFile) { error("Workspace was not cached") }
        val unchanged = cache.digest(member.virtualFile)
        val original = cache.digest(library.virtualFile)
        val document = FileDocumentManager.getInstance().getDocument(library.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("""{"name":"alpha"}""") }
        assertSame(files, NpmManifest.files(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
        assertSame(unchanged, cache.digest(member.virtualFile))
        assertFalse(original == cache.digest(library.virtualFile))
        assertFalse(adapter.isCurrent(project, snapshot))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertTrue(adapter.snapshot(project, member.virtualFile)!!.declarations.single().baseline.isEmpty())
        assertNotSame(workspace, cache.workspace(member.virtualFile) { error("Fresh ownership was not cached") })
    }

    fun testManifestRenameCopyMoveAndDirectoryRenameInvalidateDiscovery() {
        val manifest = myFixture.addFileToProject("structure/app/package.json", "{}")
        val destination = myFixture.addFileToProject("structure/other/marker.txt", "").virtualFile.parent
        val cache = project.service<NpmProjectCache>()
        var discoveries = 0
        fun discover() = cache.files { discoveries++; listOf(manifest.virtualFile) }
        discover()
        val mutations = listOf<() -> Unit>(
            { manifest.virtualFile.rename(this, "renamed.json") },
            { manifest.virtualFile.rename(this, "package.json") },
            { manifest.virtualFile.copy(this, destination, "package.json") },
            { manifest.virtualFile.move(this, destination) },
            { destination.rename(this, "node_modules") },
            { destination.rename(this, "returned") },
            { manifest.virtualFile.delete(this) }
        )
        // Remove the copy before moving the original into the same directory.
        for ((index, mutation) in mutations.withIndex()) {
            WriteCommandAction.runWriteCommandAction(project) {
                if (index == 3) destination.findChild("package.json")!!.delete(this)
                mutation()
            }
            discover()
            assertEquals("Structure mutation $index must invalidate cached discovery", index + 2, discoveries)
        }
    }
}
