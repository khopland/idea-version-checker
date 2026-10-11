package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import kotlinx.coroutines.*
import java.util.concurrent.Callable
import java.nio.file.Files

internal fun saveBuildInputsAndWait(project: Project, selection: BuildSelection, adapters: List<BuildSystemAdapter>) {
    val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
        runBlocking { saveBuildInputs(project, selection, adapters) }
    })
    PlatformTestUtil.waitForFuture(future, 10_000)
}

class BuildInputSavingTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }
    private val documents get() = FileDocumentManager.getInstance()
    private fun change(file: PsiFile, suffix: String = " ") {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = documents.getDocument(file.virtualFile)!!
            document.insertString(document.textLength, suffix)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
    fun testCurrentGradleFileSavesSiblingConfigurationAndLeavesSourceAndIndependentBuildUnsaved() {
        val settings = myFixture.addFileToProject("saving-gradle/settings.gradle", "rootProject.name = 'saving'")
        val build = myFixture.addFileToProject("saving-gradle/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        val sibling = myFixture.addFileToProject("saving-gradle/sub/build.gradle.kts", "// configuration")
        val properties = myFixture.addFileToProject("saving-gradle/gradle.properties", "example=true")
        val source = myFixture.addFileToProject("saving-gradle/src/App.java", "class App {}")
        val unrelated = myFixture.addFileToProject("independent/build.gradle", "// another project")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = settings.virtualFile.parent.path
        })
        listOf(settings, build, sibling, properties, source, unrelated).forEach(::change)
        val adapter = GradleBuildSystemAdapter()
        assertNull(adapter.snapshot(project, build.virtualFile))
        saveBuildInputsAndWait(project, BuildSelection(UpdateScope.CURRENT_FILE, build.virtualFile.path), listOf(adapter))
        listOf(settings, build, sibling, properties).forEach { assertFalse(documents.isFileModified(it.virtualFile)) }
        listOf(source, unrelated).forEach { assertTrue(documents.isFileModified(it.virtualFile)) }
        assertNotNull(adapter.snapshot(project, build.virtualFile))
    }
    fun testNpmWorkspaceSavesMemberManifestsAndRegistryConfigurationWithoutSavingOtherProjects() {
        val root = myFixture.addFileToProject("saving-npm/package.json", """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.0.0"}}""")
        val member = myFixture.addFileToProject("saving-npm/packages/app/package.json", """{"name":"app","dependencies":{"alpha":"~1.0.0"}}""")
        val config = myFixture.addFileToProject("saving-npm/.npmrc", "prefer-online=true")
        val source = myFixture.addFileToProject("saving-npm/app.js", "const value = 1;")
        val unrelated = myFixture.addFileToProject("other-npm/package.json", """{"name":"other"}""")
        listOf(root, member, config, source, unrelated).forEach(::change)
        saveBuildInputsAndWait(project, BuildSelection(UpdateScope.CURRENT_FILE, member.virtualFile.path), listOf(NpmBuildSystemAdapter()))
        listOf(root, member, config).forEach { assertFalse(documents.isFileModified(it.virtualFile)) }
        listOf(source, unrelated).forEach { assertTrue(documents.isFileModified(it.virtualFile)) }
    }
    fun testPostApplySavingOnlySavesSelectedEditFiles() {
        val first = myFixture.addFileToProject("save-selected/a.txt", "1.0")
        val second = myFixture.addFileToProject("save-selected/b.txt", "1.0")
        change(first); change(second)
        val edit = object : VersionEdit {
            override val element = first
            override val expected = "1.0 "
            override val latest = "2.0"
            override val location = "first"
            override fun isValid() = true
            override fun apply() = Unit
        }
        saveChangedVersionFiles(BulkUpdatePlan(listOf(edit), emptyList()))
        assertFalse(documents.isFileModified(first.virtualFile))
        assertTrue(documents.isFileModified(second.virtualFile))
    }

    private fun adapter(paths: suspend () -> Set<String>) = object : BuildSystemAdapter {
        override val id = "save-input-test"
        override val displayName = "Input test"
        override val capabilities = AdapterCapabilities()
        override fun supports(project: Project, selection: BuildSelection) = true
        override fun isOffline(project: Project) = false
        override suspend fun resolutionInputPaths(project: Project, selection: BuildSelection): Set<String> {
            val application = ApplicationManager.getApplication()
            assertFalse("Discovery must not run on EDT", application.isDispatchThread)
            assertFalse("External discovery must not hold an IDE read lock", application.isReadAccessAllowed)
            return paths()
        }
        override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? = null
        override fun isCurrent(project: Project, snapshot: BuildSnapshot) = true
        override suspend fun discover(project: Project, selection: BuildSelection) = emptyList<BuildSnapshot>()
        override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport = error("Not used")
        override suspend fun prepareUpdates(project: Project, reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan = error("Not used")
    }

    fun testPathDiscoveryRunsWithoutReadLockAndSavesTheLatestDocumentText() {
        val file = myFixture.addFileToProject("background-input/config.txt", "original")
        change(file)
        val provider = adapter {
            withContext(Dispatchers.EDT) { change(file, "latest") }
            setOf(file.virtualFile.path)
        }
        saveBuildInputsAndWait(project, BuildSelection(UpdateScope.WHOLE_PROJECT), listOf(provider))
        assertFalse(documents.isFileModified(file.virtualFile))
        assertEquals("original latest", String(file.virtualFile.contentsToByteArray()))
    }

    fun testCancelledDiscoveryCannotScheduleASave() {
        val file = myFixture.addFileToProject("cancelled-input/config.txt", "original")
        change(file)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val provider = adapter { started.complete(Unit); release.await(); setOf(file.virtualFile.path) }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = worker.launch { saveBuildInputs(project, BuildSelection(UpdateScope.WHOLE_PROJECT), listOf(provider)) }
            PlatformTestUtil.waitWithEventsDispatching("Input discovery started", { started.isCompleted }, 10)
            job.cancel()
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Cancelled input discovery stopped", { job.isCompleted }, 10)
            assertTrue(documents.isFileModified(file.virtualFile))
            assertEquals("original", String(file.virtualFile.contentsToByteArray()))
        } finally { worker.cancel() }
    }

    fun testGradleSavingIncludesNestedUserInitScriptsWithoutFingerprintingThem() {
        val build = myFixture.addFileToProject("external-input/build.gradle", "// build")
        // init.d discovery uses the disk, so this fixture needs a real directory rather than light VFS.
        val home = Files.createTempDirectory("version-checker-save-inputs-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, home.toString())
        fun external(name: String, text: String): PsiFile {
            val path = home.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            return PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!)!!
        }
        val properties = external("gradle.properties", "flag=true")
        val init = external("init.d/nested/config.gradle", "// init")
        val unrelated = external("notes.txt", "notes")
        val settings = GradleSettings.getInstance(project)
        val previousHome = settings.serviceDirectoryPath
        try {
            settings.serviceDirectoryPath = properties.virtualFile.parent.path
            settings.linkProject(GradleProjectSettings().apply { externalProjectPath = build.virtualFile.parent.path })
            listOf(build, properties, init, unrelated).forEach(::change)
            saveBuildInputsAndWait(project, BuildSelection(UpdateScope.CURRENT_FILE, build.virtualFile.path), listOf(GradleBuildSystemAdapter()))
            listOf(build, properties, init).forEach { assertFalse("Input must be saved: ${it.virtualFile.path}", documents.isFileModified(it.virtualFile)) }
            assertTrue(documents.isFileModified(unrelated.virtualFile))
        } finally {
            settings.serviceDirectoryPath = previousHome
            listOf(properties, init, unrelated).forEach { documents.getCachedDocument(it.virtualFile)?.let(documents::reloadFromDisk) }
            home.toFile().deleteRecursively()
        }
    }
}
