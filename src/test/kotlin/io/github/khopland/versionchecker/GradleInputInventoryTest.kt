package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.gradle.GradleInputDirectories
import io.github.khopland.versionchecker.gradle.GradleInputInventory
import io.github.khopland.versionchecker.gradle.GradleProjectCache
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateCandidate
import io.github.khopland.versionchecker.core.UpdateReport
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class GradleInputInventoryTest : BasePlatformTestCase() {
    private fun <T> background(action: () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { action() }), 30_000)

    private fun inventory(paths: List<String>, logic: Set<String> = emptySet()): GradleInputInventory =
        GradleInputInventory.discover(GradleInputDirectories(paths, logic)) {
            find(it)
        }

    private fun find(path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull {
            it.fileSystem.findFileByPath(path)
        }

    private fun baseline(inventory: GradleInputInventory): Map<VirtualFile, String> =
        inventory.files.associateWith { GradleProjectCache.hash(it.contentsToByteArray()) }

    private fun withDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("version-checker-inventory-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        try { action(directory) } finally { directory.toFile().deleteRecursively() }
    }

    private fun refresh(path: Path) = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also {
        it.refresh(false, true)
    }

    fun testSavingUnsavedDetectionAndDiskValidationShareBuildLogicCoverage() {
        val root = myFixture.addFileToProject("project/build.gradle", "// build").virtualFile.parent
        val policy = myFixture.addFileToProject("project/buildSrc/src/main/resources/policy.json", "{}").virtualFile
        val configuration = myFixture.addFileToProject("project/gradle/dependency-locks/runtime.lock", "locked").virtualFile
        val application = myFixture.addFileToProject("project/src/main/resources/application.json", "{}").virtualFile
        myFixture.addFileToProject("project/buildSrc/build/generated/policy.json", "generated")
        FileDocumentManager.getInstance().saveAllDocuments()
        val inputs = inventory(listOf(root.path, root.findChild("buildSrc")!!.path), setOf(root.findChild("buildSrc")!!.path))
        assertTrue(inputs.resolutionInputPaths().containsAll(listOf(policy.path, configuration.path)))
        assertFalse(inputs.resolutionInputPaths().contains(application.path))
        assertFalse(inputs.files.any { it.path.contains("/build/generated/") })
        assertFalse(inputs.hasUnsavedDocuments())
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(policy)!!.setText("{\"changed\":true}")
        }
        assertTrue(inputs.hasUnsavedDocuments())
    }

    fun testPhysicalAdditionsUseTheSamePolicyBeforeVfsRefresh() = withDirectory { root ->
        Files.writeString(root.resolve("build.gradle"), "// build")
        refresh(root)
        val inputs = inventory(listOf(root.toString()))
        val saved = baseline(inputs)
        Files.writeString(Files.createDirectories(root.resolve("src/main/resources")).resolve("application.json"), "{}")
        Files.writeString(Files.createDirectories(root.resolve("buildSrc/build/generated")).resolve("policy.json"), "{}")
        assertTrue(background { inputs.diskMatches(saved) {} })
        Files.writeString(Files.createDirectories(root.resolve("buildSrc/src/main/resources")).resolve("policy.json"), "{}")
        assertFalse(background { inputs.diskMatches(saved) {} })
    }

    fun testCanonicalAliasesKeepLinkedSourcePathsAndValidatePhysicalAdditions() = withDirectory { parent ->
        val root = Files.createDirectory(parent.resolve("actual"))
        Files.writeString(root.resolve("build.gradle"), "// build")
        val alias = Files.createSymbolicLink(parent.resolve("linked"), root)
        refresh(root)
        refresh(alias)
        val inputs = inventory(listOf(alias.toString(), root.toString()))
        assertEquals(1, inputs.files.size)
        assertEquals(alias.resolve("build.gradle").toString(), inputs.files.single().path)
        val saved = baseline(inputs)
        assertTrue(background { inputs.diskMatches(saved) {} })
        Files.writeString(root.resolve("gradle.properties"), "new=true")
        assertFalse(background { inputs.diskMatches(saved) {} })
    }

    fun testExplicitImportedRootBelowGeneratedBoundaryStillTracksItsResources() = withDirectory { root ->
        Files.writeString(root.resolve("build.gradle"), "// build")
        val imported = Files.createDirectories(root.resolve("build/included"))
        val policy = Files.createDirectories(imported.resolve("src/main/resources")).resolve("policy.json")
        Files.writeString(policy, "{}")
        refresh(root)
        val inputs = inventory(listOf(root.toString(), imported.toString()), setOf(imported.toString()))
        assertTrue(inputs.resolutionInputPaths().contains(policy.toString()))
        val saved = baseline(inputs)
        assertTrue(background { inputs.diskMatches(saved) {} })
        Files.writeString(policy.resolveSibling("new-policy.json"), "{}")
        assertFalse(background { inputs.diskMatches(saved) {} })
    }

    fun testPhysicalImportedDirectoryAbsentFromVfsCannotBypassValidation() = withDirectory { root ->
        Files.writeString(root.resolve("build.gradle"), "// build")
        refresh(root)
        val imported = Files.createDirectories(root.resolve("build/included"))
        Files.writeString(Files.createDirectories(imported.resolve("src/main/resources")).resolve("policy.json"), "{}")
        // Deliberately withhold this imported root: filesystem watchers and lazy VFS
        // lookups can otherwise discover it before the test reaches validation.
        val inputs = GradleInputInventory.discover(
            GradleInputDirectories(listOf(root.toString(), imported.toString()), setOf(imported.toString()))
        ) { path -> if (path == imported.toString()) null else find(path) }
        val saved = baseline(inputs)
        assertFalse(background { inputs.diskMatches(saved) {} })
    }

    fun testCanonicalModuleWithinSymlinkRootIsNotExternalBuildLogic() = withDirectory { parent ->
        val actual = Files.createDirectory(parent.resolve("actual"))
        Files.writeString(actual.resolve("build.gradle"), "dependencies { implementation 'g:library:1.2.3' }")
        val module = Files.createDirectory(actual.resolve("module"))
        Files.writeString(module.resolve("build.gradle"), "// module")
        val application = Files.createDirectories(module.resolve("src/main/java")).resolve("Application.java")
        Files.writeString(application, "class Application {}")
        val external = Files.createDirectory(parent.resolve("build-logic"))
        val policy = Files.createDirectories(external.resolve("src/main/resources")).resolve("policy.json")
        Files.writeString(policy, "{}")
        val linked = Files.createSymbolicLink(parent.resolve("linked"), actual)
        refresh(actual)
        refresh(external)
        val linkedRoot = refresh(linked)
        val settings = GradleSettings.getInstance(project)
        settings.linkProject(GradleProjectSettings().apply {
            externalProjectPath = linked.toString()
            setModules(setOf(module.toString(), external.toString()))
        })
        try {
            val adapter = GradleBuildSystemAdapter()
            val source = linkedRoot.findChild("build.gradle")!!
            val snapshot = adapter.snapshot(project, source)!!
            assertFalse(snapshot.fingerprint.files.keys.any { it.endsWith("/Application.java") })
            assertTrue(snapshot.fingerprint.files.containsKey(policy.toString()))
            val held = background { runBlocking {
                adapter.prepareUpdates(project, mapOf(snapshot to UpdateReport(
                    snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })))
            } }
            assertTrue("Canonical imported modules must not reject unchanged edits", held.apply(project))
            assertTrue(FileDocumentManager.getInstance().getDocument(source)!!.text.contains("1.2.9"))
        } finally { settings.unlinkExternalProject(linked.toString()) }
    }
}
