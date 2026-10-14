package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.BuildSelection
import io.github.khopland.versionchecker.core.BuildSnapshot
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateScope
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter
import io.github.khopland.versionchecker.npm.NpmManifest
import io.github.khopland.versionchecker.npm.NpmWorkspaces
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class BuildFolderOwnershipTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private val addedRoots = mutableSetOf<String>()
    private val npm = NpmBuildSystemAdapter()
    private val gradle = GradleBuildSystemAdapter()

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-folder-ownership-")
    }

    override fun tearDown() {
        try {
            GradleSettings.getInstance(project).linkedProjectsSettings.mapNotNull { it.externalProjectPath }
                .forEach(GradleSettings.getInstance(project)::unlinkExternalProject)
            ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.url.removePrefix("file://") in addedRoots }.forEach(model::removeContentEntry)
            }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } finally { super.tearDown() }
    }

    private fun write(relative: String, text: String): VirtualFile {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private fun contentRoot(root: VirtualFile) {
        ModuleRootModificationUtil.addContentRoot(myFixture.module, root.path)
        addedRoots += root.path
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun link(root: VirtualFile, modules: Set<String> = emptySet()) {
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = root.path
            setModules(modules)
        })
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun discover(adapter: BuildSystemAdapter, selection: BuildSelection = BuildSelection(UpdateScope.WHOLE_PROJECT)): List<BuildSnapshot> {
        // Disk writes schedule indexing independently of VFS refresh. npm intentionally
        // returns no indexed manifests during dumb mode, so wait before asserting discovery.
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.discover(project, selection) }
        }), 30_000)
    }

    fun testNpmContentRootAndEnclosingGeneratedNamesDoNotExcludeTheProject() {
        val expected = mutableSetOf<String>()
        for (name in listOf("build", "dist", "vendor")) {
            val manifest = write("$name/independent/$name/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            contentRoot(manifest.parent)
            expected += manifest.path
            val selection = BuildSelection(UpdateScope.CURRENT_FILE, manifest.path)
            assertTrue(npm.supports(project, selection))
            assertEquals(manifest.path, discover(npm, selection).single().sourceFile)
            assertEquals("alpha", npm.snapshot(project, manifest)!!.declarations.single().artifact.name)
        }
        assertEquals(expected, discover(npm).map { it.sourceFile }.toSet())
    }

    fun testNpmGeneratedDescendantsRemainExcludedForDiscoveryInspectionAndCurrentFile() {
        val manifest = write("npm/package.json", "{}")
        contentRoot(manifest.parent)
        for (name in listOf("build", "dist", "vendor", "node_modules")) {
            val generated = write("npm/$name/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            assertFalse(NpmManifest.supported(project, generated))
            assertNull(npm.snapshot(project, generated))
            assertFalse(npm.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, generated.path)))
            assertEmpty(discover(npm, BuildSelection(UpdateScope.CURRENT_FILE, generated.path)))
        }
        assertEquals(listOf(manifest.path), discover(npm).map { it.sourceFile })
    }

    fun testNestedIndependentNpmContentRootOwnsItsWorkspaceAndOverrides() {
        val outer = write("npm/package.json", """{"workspaces":["packages/*"],"overrides":{"alpha":"1.2.3"}}""")
        val inner = write("npm/build/independent/package.json", """{"workspaces":["packages/*"]}""")
        val member = write("npm/build/independent/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        contentRoot(outer.parent)
        contentRoot(inner.parent)
        assertEquals(inner.parent, NpmWorkspaces.resolve(project, member).root)
        assertEquals(setOf(inner, member), NpmWorkspaces.resolve(project, member).manifests.toSet())
        assertEquals(listOf(outer), NpmWorkspaces.resolve(project, outer).manifests)
        val snapshot = npm.snapshot(project, member)!!
        assertTrue(snapshot.skippedDeclarations.isEmpty())
        assertEquals(setOf(outer.path, inner.path, member.path), discover(npm).map { it.sourceFile }.toSet())
    }

    fun testDeclaredNpmWorkspaceMemberContentRootRetainsRootOverrides() {
        val outer = write("npm/package.json", """{"workspaces":["packages/*"],"overrides":{"alpha":"1.2.3"}}""")
        val member = write("npm/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        contentRoot(outer.parent)
        contentRoot(member.parent)
        assertEquals(outer.parent, NpmWorkspaces.resolve(project, member).root)
        val snapshot = npm.snapshot(project, member)!!
        assertEquals(outer.parent.path, snapshot.context.root)
        assertTrue(snapshot.skippedDeclarations.values.single().contains("overrides"))
        assertEquals(setOf(outer.path, member.path), discover(npm).map { it.sourceFile }.toSet())
    }

    fun testOpeningOnlyNpmMemberDeclinesChecksOwnedByOutsideWorkspace() {
        val outer = write("outside/package.json", """{"workspaces":["packages/*"],"overrides":{"alpha":"1.2.3"}}""")
        write("outside/.npmrc", "registry=https://outside-workspace.invalid/\n")
        val member = write("outside/packages/app/package.json", """{"dependencies":{"alpha":"1.2.9"}}""")
        contentRoot(member.parent)
        assertEquals(outer.parent, NpmWorkspaces.resolve(project, member).root)
        assertTrue(NpmManifest.supported(project, member))
        assertFalse(npm.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, member.path)))
        assertFalse(npm.supports(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
        assertNull(npm.snapshot(project, member))
        assertEmpty(discover(npm, BuildSelection(UpdateScope.CURRENT_FILE, member.path)))
        assertEmpty(discover(npm))
    }

    fun testOutsideNpmAncestorDoesNotOwnAnUndeclaredIndependentProject() {
        write("outside/package.json", """{"workspaces":["other/*"],"overrides":{"alpha":"1.2.3"}}""")
        val independent = write("outside/build/project/package.json", """{"dependencies":{"alpha":"1.2.9"}}""")
        contentRoot(independent.parent)
        assertEquals(independent.parent, NpmWorkspaces.resolve(project, independent).root)
        assertTrue(npm.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, independent.path)))
        assertTrue(npm.snapshot(project, independent)!!.skippedDeclarations.isEmpty())
        assertEquals(listOf(independent.path), discover(npm).map { it.sourceFile })
    }

    fun testNpmSymlinkContentRootIgnoresGeneratedNamedTargetAncestors() {
        val original = write("build/real/vendor/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        val alias = Files.createSymbolicLink(directory.resolve("alias"), Path.of(original.parent.path))
        val linkedRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(alias)!!
        contentRoot(linkedRoot)
        val manifest = linkedRoot.findChild("package.json")!!
        assertTrue(npm.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, manifest.path)))
        assertEquals(linkedRoot.path, npm.snapshot(project, manifest)!!.context.root)
        assertEquals(manifest.path, discover(npm, BuildSelection(UpdateScope.CURRENT_FILE, manifest.path)).single().sourceFile)
    }

    fun testGradleLinkedRootAndEnclosingGeneratedNamesRemainCheckable() {
        val expected = mutableSetOf<String>()
        for (name in listOf("build", "dist", "vendor")) {
            val build = write("$name/independent/$name/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
            val catalog = write("$name/independent/$name/gradle/libs.versions.toml", "[libraries]\nalpha = 'g:alpha:1.2.3'")
            link(build.parent)
            expected += setOf(build.path, catalog.path)
            for (file in listOf(build, catalog)) {
                val selection = BuildSelection(UpdateScope.CURRENT_FILE, file.path)
                assertTrue(gradle.supports(project, selection))
                assertEquals("alpha", discover(gradle, selection).single().declarations.single().artifact.name)
            }
        }
        assertEquals(expected, discover(gradle).map { it.sourceFile }.toSet())
    }

    fun testGradleGeneratedDescendantsRemainExcludedFromWholeAndCurrentFile() {
        val build = write("gradle/build.gradle", "// root")
        for (name in listOf("build", "out", "vendor", "node_modules")) {
            val generated = write("gradle/$name/app/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
            linkIfNeeded(build.parent)
            assertFalse(gradle.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, generated.path)))
            assertEmpty(discover(gradle, BuildSelection(UpdateScope.CURRENT_FILE, generated.path)))
        }
        assertEquals(listOf(build.path), discover(gradle).map { it.sourceFile })
    }

    private fun linkIfNeeded(root: VirtualFile) {
        if (GradleSettings.getInstance(project).getLinkedProjectSettings(root.path) == null) link(root)
    }

    fun testNestedIndependentLinkedGradleRootIsNotHiddenByOuterGeneratedDirectory() {
        val outer = write("gradle/build.gradle", "// outer")
        val inner = write("gradle/build/vendor/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        link(outer.parent)
        link(inner.parent)
        val snapshots = discover(gradle)
        assertEquals(setOf(outer.path, inner.path), snapshots.map { it.sourceFile }.toSet())
        assertEquals(inner.parent.path, snapshots.single { it.sourceFile == inner.path }.context.root)
    }

    fun testImportedGradleModuleUnderGeneratedDirectoryOwnsItsBoundary() {
        val outer = write("gradle/build.gradle", "// outer")
        val module = write("gradle/build/vendor/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val generated = write("gradle/build/vendor/build/app/build.gradle", "// generated")
        link(outer.parent, setOf(outer.parent.path, module.parent.path))
        assertEquals(setOf(outer.path, module.path), discover(gradle).map { it.sourceFile }.toSet())
        assertTrue(gradle.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, module.path)))
        assertFalse(gradle.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, generated.path)))
    }

    fun testGradleSymlinkRootNamedBuildRetainsCanonicalModuleDeduplication() {
        val build = write("vendor/real/build.gradle", "// root")
        val module = write("vendor/real/module/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val alias = Files.createSymbolicLink(directory.resolve("build"), Path.of(build.parent.path))
        val linkedRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(alias)!!
        link(linkedRoot, setOf(build.parent.path, module.parent.path))
        val snapshots = discover(gradle)
        assertEquals(setOf("${linkedRoot.path}/build.gradle", "${linkedRoot.path}/module/build.gradle"), snapshots.map { it.sourceFile }.toSet())
        assertEquals(1, snapshots.sumOf { it.declarations.size })
        assertTrue(gradle.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, "${linkedRoot.path}/module/build.gradle")))
    }
}
