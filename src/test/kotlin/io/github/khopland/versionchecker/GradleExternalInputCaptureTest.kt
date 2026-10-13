package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import kotlinx.coroutines.*
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class GradleExternalInputCaptureTest : BasePlatformTestCase() {
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    private fun link(name: String) = myFixture.addFileToProject("$name/build.gradle",
        "dependencies { implementation 'g:library:1.2.3' }").also { file ->
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = file.virtualFile.parent.path
        })
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun withHome(action: (Path) -> Unit) {
        val settings = GradleSettings.getInstance(project)
        val previous = settings.serviceDirectoryPath
        val home = Files.createTempDirectory("version-checker-gradle-inputs-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, home.toString())
        try {
            settings.serviceDirectoryPath = home.toString()
            action(home)
        } finally {
            settings.serviceDirectoryPath = previous
            home.toFile().deleteRecursively()
        }
    }

    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })

    fun testPhysicalBuildFileChangesWithoutVfsRefreshRejectHeldEdits() = withHome {
        val root = Files.createTempDirectory("version-checker-gradle-source-race-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val path = root.resolve("build.gradle")
        val original = "dependencies { implementation 'g:library:1.2.3' }"
        Files.writeString(path, original)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val settings = GradleSettings.getInstance(project)
        settings.linkProject(GradleProjectSettings().apply { externalProjectPath = root.toString() })
        try {
            val adapter = GradleBuildSystemAdapter()
            val snapshot = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.path)) }.single()
            val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
            val cached = FileDocumentManager.getInstance().getDocument(file)!!
            assertEquals(original, cached.text)
            val stamp = Files.getLastModifiedTime(path)
            val modified = original.replace("1.2.3", "1.2.4")
            Files.writeString(path, modified)
            Files.setLastModifiedTime(path, stamp)
            assertEquals("The regression must keep stale IDE text", original, cached.text)
            assertFalse("Apply must reject physical bytes changed outside VFS", plan.apply(project))
            assertEquals(original, cached.text)
            assertEquals(modified, Files.readString(path))
            Files.writeString(path, original)
            Files.setLastModifiedTime(path, stamp)
            assertTrue("Restoring the exact disk baseline permits a fresh validation", plan.apply(project))
            assertTrue(cached.text.contains("g:library:1.2.9"))
        } finally {
            settings.unlinkExternalProject(root.toString())
            root.toFile().deleteRecursively()
        }
    }

    fun testPhysicalSettingsChangeOrMissingInputRejectsEverySelectedFile() = withHome {
        val root = Files.createTempDirectory("version-checker-gradle-settings-race-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val properties = root.resolve("gradle.properties")
        Files.writeString(properties, "flag=original")
        val build = root.resolve("build.gradle")
        val child = Files.createDirectories(root.resolve("child")).resolve("build.gradle")
        val original = "dependencies { implementation 'g:library:1.2.3' }"
        Files.writeString(build, original)
        Files.writeString(child, original)
        val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        virtualRoot.refresh(false, true)
        val settings = GradleSettings.getInstance(project)
        settings.linkProject(GradleProjectSettings().apply { externalProjectPath = root.toString() })
        try {
            val adapter = GradleBuildSystemAdapter()
            val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
            val plan = background { adapter.prepareUpdates(project, snapshots.associateWith(::report)) }
            assertEquals(2, plan.changes.size)
            val stamp = Files.getLastModifiedTime(properties)
            Files.writeString(properties, "flag=modified")
            Files.setLastModifiedTime(properties, stamp)
            assertFalse(plan.apply(project))
            assertTrue(plan.changes.all { it.element!!.containingFile.text == original })
            assertEquals(original, Files.readString(build))
            assertEquals(original, Files.readString(child))
            Files.writeString(properties, "flag=original")
            Files.setLastModifiedTime(properties, stamp)
            Files.delete(properties)
            assertFalse("A missing tracked file must not authorize any edit", plan.apply(project))
            assertTrue(plan.changes.all { it.element!!.containingFile.text == original })
        } finally {
            settings.unlinkExternalProject(root.toString())
            root.toFile().deleteRecursively()
        }
    }

    fun testDiscoveryValidationAndPreviewReadDiskOutsideReadLocksOncePerOperation() = withHome { home ->
        val first = link("first")
        link("second")
        Files.writeString(home.resolve("gradle.properties"), "flag=true")
        Files.writeString(home.resolve("init.gradle"), "// init")
        val init = Files.createDirectories(home.resolve("init.d/nested"))
        repeat(24) { Files.writeString(init.resolve("config-$it.gradle"), "// config $it") }
        var reads = 0
        val adapter = GradleBuildSystemAdapter { path ->
            assertFalse("Disk bytes must be read without an IDE read lock", ApplicationManager.getApplication().isReadAccessAllowed)
            assertFalse("Disk bytes must be read off EDT", ApplicationManager.getApplication().isDispatchThread)
            reads++
            Files.readAllBytes(path)
        }
        val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        assertEquals(2, snapshots.size)
        assertEquals("External inputs are shared across linked roots", 26, reads)
        val sync = GradleBuildSystemAdapter().snapshot(project, first.virtualFile)!!
        assertEquals(sync, snapshots.single { it.sourceFile == first.virtualFile.path })
        assertTrue(background { adapter.isCurrentInBackground(project, sync) })
        assertEquals(52, reads)
        val plan = background { adapter.prepareUpdates(project, snapshots.associateWith(::report)) }
        assertEquals(2, plan.changes.size)
        assertEquals("Preview preparation captures one external input set", 78, reads)
        val captured = background { adapter.prepareInspectionSnapshot(project, first.virtualFile) }
        assertEquals(sync, captured())
        val beforeApply = reads
        assertTrue(plan.apply(project))
        assertEquals("Apply captures once outside EDT/read locks", beforeApply + 26, reads)
    }

    fun testBackgroundValidationDetectsSameStampChangesAndFinalApplyStillRejectsThem() = withHome { home ->
        val file = link("same-stamp")
        val init = Files.createDirectories(home.resolve("init.d")).resolve("repositories.gradle")
        Files.writeString(init, "// original")
        val adapter = GradleBuildSystemAdapter()
        val snapshot = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) }.single()
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report(snapshot))) }
        val stamp = Files.getLastModifiedTime(init)
        Files.writeString(init, "// modified")
        Files.setLastModifiedTime(init, stamp)
        assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
        assertFalse(plan.apply(project))
        assertTrue(file.text.contains("g:library:1.2.3"))
        val changed = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) }.single()
        Files.writeString(home.resolve("gradle.properties"), "flag=true")
        assertFalse(background { adapter.isCurrentInBackground(project, changed) })
        val withProperties = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) }.single()
        Files.delete(init)
        assertFalse(background { adapter.isCurrentInBackground(project, withProperties) })
    }

    fun testFinalBulkValidationSharesOneFreshExternalCaptureAcrossLinkedBuilds() = withHome { home ->
        val files = (1..12).map { link("bulk-root-$it") }
        val properties = home.resolve("gradle.properties")
        Files.writeString(properties, "flag=original")
        val init = Files.createDirectories(home.resolve("init.d/nested"))
        repeat(8) { Files.writeString(init.resolve("config-$it.gradle"), "// config $it") }
        var reads = 0
        val adapter = GradleBuildSystemAdapter { path -> reads++; Files.readAllBytes(path) }
        val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        val plan = background { adapter.prepareUpdates(project, snapshots.associateWith(::report)) }
        assertEquals(12, plan.changes.size)
        reads = 0
        val stamp = Files.getLastModifiedTime(properties)
        Files.writeString(properties, "flag=modified")
        Files.setLastModifiedTime(properties, stamp)
        assertFalse("Fresh disk bytes must reject changed inputs", plan.apply(project))
        assertEquals(9, reads)
        assertTrue(files.all { it.text.contains("g:library:1.2.3") })
        Files.writeString(properties, "flag=original")
        Files.setLastModifiedTime(properties, stamp)
        reads = 0
        assertTrue("A later validation must capture disk inputs again", plan.apply(project))
        assertEquals("User-home inputs are read once, not once per linked build", 9, reads)
        assertTrue(files.all { it.text.contains("g:library:1.2.9") })
    }

    fun testUnsavedExternalDocumentChangedDuringDiskCaptureIsReadFromLatestEditorState() = withHome { home ->
        val file = link("unsaved-input")
        val path = home.resolve("gradle.properties")
        Files.writeString(path, "flag=saved")
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(virtual)!!
        val snapshot = GradleBuildSystemAdapter().snapshot(project, file.virtualFile)!!
        var edited = false
        val adapter = GradleBuildSystemAdapter { input ->
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            if (!edited) {
                edited = true
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("flag=unsaved") }
                }
            }
            Files.readAllBytes(input)
        }
        try {
            assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
            assertTrue(documents.isDocumentUnsaved(document))
            assertEquals("flag=saved", Files.readString(path))
            val current = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) }.single()
            assertEquals(GradleBuildSystemAdapter().snapshot(project, file.virtualFile), current)
        } finally { documents.reloadFromDisk(document) }
    }

    fun testUserHomeChangedDuringDiskCaptureRetriesWithTheNewInputs() = withHome { oldHome ->
        val file = link("home-change")
        Files.writeString(oldHome.resolve("gradle.properties"), "flag=old")
        val newHome = Files.createTempDirectory("version-checker-new-gradle-home-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, newHome.toString())
        try {
            Files.writeString(newHome.resolve("gradle.properties"), "flag=new")
            var changed = false
            val adapter = GradleBuildSystemAdapter { path ->
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                if (!changed) {
                    changed = true
                    ApplicationManager.getApplication().invokeAndWait {
                        GradleSettings.getInstance(project).serviceDirectoryPath = newHome.toString()
                    }
                }
                Files.readAllBytes(path)
            }
            val snapshot = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) }.single()
            assertEquals(GradleBuildSystemAdapter().snapshot(project, file.virtualFile), snapshot)
            assertTrue(snapshot.fingerprint.files.containsKey(newHome.resolve("gradle.properties").toString()))
            assertFalse(snapshot.fingerprint.files.containsKey(oldHome.resolve("gradle.properties").toString()))
        } finally { newHome.toFile().deleteRecursively() }
    }

    fun testCancellationStopsExternalCaptureBeforeAnotherInputOrSnapshot() = withHome { home ->
        val file = link("cancelled-input")
        Files.writeString(home.resolve("gradle.properties"), "flag=true")
        Files.writeString(home.resolve("init.gradle"), "// init")
        var reads = 0
        val cancelled = background {
            supervisorScope {
                val capture = async {
                    val job = currentCoroutineContext().job
                    val adapter = GradleBuildSystemAdapter { path ->
                        reads++
                        job.cancel(CancellationException("stop external capture"))
                        Files.readAllBytes(path)
                    }
                    adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path))
                }
                try { capture.await(); false } catch (_: CancellationException) { true }
            }
        }
        assertTrue(cancelled)
        assertEquals(1, reads)
    }
}
