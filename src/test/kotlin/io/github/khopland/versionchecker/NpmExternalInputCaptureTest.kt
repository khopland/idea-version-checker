package io.github.khopland.versionchecker

import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.javascript.nodejs.npm.NpmNodePackage
import com.intellij.javascript.nodejs.util.NodePackageRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class NpmExternalInputCaptureTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-npm-inputs-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        for (name in listOf("first", "second")) {
            Files.createDirectories(directory.resolve(name))
            Files.writeString(directory.resolve("$name/package.json"), """{"dependencies":{"alpha":"^1.2.3"}}""")
        }
        Files.writeString(directory.resolve(".npmrc"), "registry=https://example.invalid/\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            .refresh(false, true)
        ModuleRootModificationUtil.addContentRoot(myFixture.module, directory.toString())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                model.contentEntries.filter { it.file?.path == directory.toString() }.forEach(model::removeContentEntry)
            }
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)
    private fun file(name: String = "first") = LocalFileSystem.getInstance().findFileByNioFile(directory.resolve("$name/package.json"))!!
    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9", "^1.2.9") })

    fun testNpmUserConfigPathsUseHomeAndResolveOverridesFromCommandDirectory() {
        val home = directory.resolve("custom-home")
        val root = directory.resolve("first")
        val paths = NpmBuildInputs.userConfigurationPaths(mapOf(
            "HOME" to home.toString(), "NPM_CONFIG_USERCONFIG" to "config/local.npmrc",
            "npm_config_globalconfig" to directory.resolve("global.npmrc").toString()), root)
        assertFalse(home.resolve(".npmrc") in paths) // Explicit userconfig replaces npm's default.
        assertTrue(home.resolve(".npmrc") in NpmBuildInputs.userConfigurationPaths(mapOf("HOME" to home.toString()), root))
        assertTrue(root.resolve("config/local.npmrc") in paths)
        assertTrue(directory.resolve("global.npmrc") in paths)
        assertFalse(Path.of(System.getProperty("user.home"), ".npmrc") in paths)
        assertTrue(root.resolve("~/.npmrc") in NpmBuildInputs.userConfigurationPaths(mapOf("HOME" to ""), root))
    }

    fun testRuntimeConfigurationUsesTheSameInventoryForSavingUnsavedChecksAndCapture() {
        val runtimeConfig = directory.resolve("mise.local.toml")
        Files.writeString(runtimeConfig, "[tools]\nnode = '20'\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(runtimeConfig)!!
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val adapter = NpmBuildSystemAdapter()
        val selection = BuildSelection(UpdateScope.CURRENT_FILE, file().path)
        val paths = background { adapter.resolutionInputPaths(project, selection) }
        assertTrue(runtimeConfig.toString() in paths)
        assertTrue(NpmBuildInputs.hasUnsavedResolutionInputs(project, file().parent.path, setOf(runtimeConfig.toString())))
        val original = background { adapter.discover(project, selection) }.single()
        val reads = mutableListOf<Path>()
        val capturingAdapter = NpmBuildSystemAdapter { path ->
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            reads.add(path)
            Files.readAllBytes(path)
        }
        Files.writeString(runtimeConfig, "[tools]\nnode = '22'\n")
        assertFalse(background { capturingAdapter.isCurrentInBackground(project, original) })
        assertEquals(1, reads.count { it == runtimeConfig })
        // Asking which files to save must never invoke the adapter's disk reader.
        reads.clear()
        assertEquals(paths, background { capturingAdapter.resolutionInputPaths(project, selection) })
        assertTrue(reads.isEmpty())
    }

    fun testOtherManagerInventoryUsesExistenceRatherThanLockfileContents() {
        val marker = directory.resolve("first/yarn.lock")
        Files.writeString(marker, "one")
        val reads = mutableListOf<Path>()
        val capture: () -> NpmBuildInputs.External = {
            background { NpmBuildInputs.capture(project, { setOf(file().parent.path) }, { path ->
                reads.add(path)
                Files.readAllBytes(path)
            }) { it } }
        }
        val first = capture()
        assertEquals(true, first.existing[marker])
        assertFalse(marker in first.digests)
        assertFalse(marker in reads)
        Files.writeString(marker, "different lockfile contents")
        assertEquals(first.existing, capture().existing)
        Files.delete(marker)
        assertEquals(false, capture().existing[marker])
    }

    fun testInventoryDiscoveryRequiresNoExistingDirectoriesOrRuntime() {
        val root = directory.resolve("not-created/workspace")
        val runtime = directory.resolve("not-installed/node/bin/node")
        val npm = directory.resolve("not-installed/npm")
        val inventory = NpmInputInventory.discover(setOf(root.toString()), mapOf("HOME" to directory.toString()), runtime.toString(), npm.toString())
        assertTrue(root.resolve("mise.local.toml") in inventory.contentPaths)
        assertTrue(root.resolve("package.json") in inventory.manifestPaths)
        assertTrue(root.resolve("pnpm-lock.yaml") in inventory.markerPaths)
        assertFalse(root.resolve("pnpm-lock.yaml") in inventory.contentPaths)
        assertEquals(setOf(runtime, npm, npm.resolve("package.json"), npm.resolve("bin/npm-cli.js")), inventory.runtimePaths)
        assertTrue(directory.resolve("not-installed/node/etc/npmrc") in inventory.contentPaths)
        assertTrue(inventory.hasUnsavedInputs(setOf(root.resolve("mise.local.toml").toString())))
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(runtime))
    }

    fun testDiscoveryContextValidationAndPreviewShareDiskReadsOutsideReadLocks() {
        val reads = mutableListOf<Path>()
        val readBytes: (Path) -> ByteArray = { path ->
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            reads.add(path)
            Files.readAllBytes(path)
        }
        val adapter = NpmBuildSystemAdapter(readBytes)
        val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        assertEquals(2, snapshots.size)
        assertEquals(reads.toSet().size, reads.size)
        assertEquals(1, reads.count { it == directory.resolve(".npmrc") })
        assertEquals(NpmBuildSystemAdapter().snapshot(project, file()), snapshots.single { it.sourceFile == file().path })
        reads.clear()
        assertTrue(background { adapter.isCurrentInBackground(project, snapshots.first()) })
        assertEquals(reads.toSet().size, reads.size)
        reads.clear()
        val plan = background { adapter.prepareUpdates(project, snapshots.associateWith(::report)) }
        assertEquals(2, plan.changes.size)
        assertEquals(reads.toSet().size, reads.size)
        val roots = snapshots.map { it.context.root }.toSet()
        val contexts = background {
            NpmBuildInputs.capture(project, { roots }, readBytes) { external ->
                roots.associateWith { path ->
                    NpmBuildInputs.resolutionContext(project, LocalFileSystem.getInstance().findFileByPath(path)!!, external)
                }
            }
        }
        roots.forEach { root ->
            assertEquals(NpmBuildInputs.resolutionContext(project, LocalFileSystem.getInstance().findFileByPath(root)!!), contexts[root])
        }
        val captured = background { adapter.prepareInspectionSnapshot(project, file()) }
        assertEquals(snapshots.single { it.sourceFile == file().path }, captured())
        reads.clear()
        assertTrue(plan.apply(project))
        assertEquals(reads.toSet().size, reads.size)
    }

    fun testSameStampDiskConfigAndOtherManagerChangesStillRejectBackgroundResultsAndFinalEdits() {
        val adapter = NpmBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, file())!!
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
        val config = directory.resolve(".npmrc")
        val stamp = Files.getLastModifiedTime(config)
        Files.writeString(config, "registry=https://another.invalid/\n")
        Files.setLastModifiedTime(config, stamp)
        assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
        assertFalse(plan.apply(project))
        val changed = adapter.snapshot(project, file())!!
        val lock = directory.resolve("first/yarn.lock")
        Files.writeString(lock, "lock")
        assertFalse(background { adapter.isCurrentInBackground(project, changed) })
        Files.delete(lock)
        assertTrue(background { adapter.isCurrentInBackground(project, changed) })
    }

    fun testUnsavedRegistryEditDuringCaptureUsesLatestEditorText() {
        val config = LocalFileSystem.getInstance().findFileByNioFile(directory.resolve(".npmrc"))!!
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(config)!!
        val original = NpmBuildSystemAdapter().snapshot(project, file())!!
        var edited = false
        val adapter = NpmBuildSystemAdapter { path ->
            if (!edited) {
                edited = true
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("registry=https://changed.invalid/\n") }
                }
            }
            Files.readAllBytes(path)
        }
        try {
            assertFalse(background { adapter.isCurrentInBackground(project, original) })
            assertTrue(documents.isDocumentUnsaved(document))
            val current = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file().path)) }.single()
            assertEquals(NpmBuildSystemAdapter().snapshot(project, file()), current)
        } finally { documents.reloadFromDisk(document) }
    }

    fun testRuntimeChangedDuringCaptureRetriesItsPathAndContext() {
        val npm = NpmManager.getInstance(project)
        val previous = npm.packageRef
        val installation = Files.createDirectories(directory.resolve("runtime/npm"))
        Files.createDirectories(installation.resolve("bin"))
        Files.writeString(installation.resolve("package.json"), """{"name":"npm","version":"10.0.0"}""")
        Files.writeString(installation.resolve("bin/npm-cli.js"), "// fixture")
        var changed = false
        val adapter = NpmBuildSystemAdapter { path ->
            if (!changed) {
                changed = true
                ApplicationManager.getApplication().invokeAndWait {
                    npm.setPackageRef(NodePackageRef.create(NpmNodePackage(installation.toString())))
                }
            }
            Files.readAllBytes(path)
        }
        try {
            val current = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file().path)) }.single()
            assertEquals(NpmBuildSystemAdapter().snapshot(project, file()), current)
        } finally { npm.setPackageRef(previous) }
    }

    fun testCancellationStopsBeforeAnotherDiskReadOrAnyReturnedSnapshot() {
        var reads = 0
        assertTrue(background {
            supervisorScope {
                val capture = async {
                    val job = currentCoroutineContext().job
                    val adapter = NpmBuildSystemAdapter { path ->
                        reads++
                        job.cancel(CancellationException("stop external capture"))
                        Files.readAllBytes(path)
                    }
                    adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT))
                }
                try { capture.await(); false } catch (_: CancellationException) { true }
            }
        })
        assertEquals(1, reads)
    }
}
