package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable
import java.nio.file.Files

class GradleAdapterTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()
    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
            settings.isOfflineWork = false
        } finally { super.tearDown() }
    }
    private fun link() {
        val root = myFixture.addFileToProject("gradle-project/settings.gradle", "rootProject.name = 'fixture'")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = root.virtualFile.parent.path })
        FileDocumentManager.getInstance().saveAllDocuments()
    }
    private fun report(snapshot: BuildSnapshot, versions: List<String?> = snapshot.declarations.map { "1.2.9" }) = UpdateReport(snapshot.declarations.zip(versions).mapNotNull { (d, v) -> v?.let { UpdateCandidate(d, it) } })
    private fun plan(snapshot: BuildSnapshot, report: UpdateReport): BulkUpdatePlan = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { adapter.prepareUpdates(project, mapOf(snapshot to report)) } }), 30_000)
    fun testBuildFileEditsApplyAtomicallyAndPreserveOtherText() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", """dependencies {
            implementation 'g:alpha:1.2.3'
            testImplementation 'g:beta:1.2.3' // keep
        }
        version = '1.2.3'
        """.trimIndent())
        val lock = myFixture.addFileToProject("gradle-project/gradle.lockfile", "unchanged")
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(snapshot, report(snapshot))
        assertEquals(2, prepared.changes.size)
        assertTrue(prepared.apply(project))
        assertTrue(file.text.contains("g:alpha:1.2.9"))
        assertTrue(file.text.contains("g:beta:1.2.9' // keep"))
        assertTrue(file.text.contains("version = '1.2.3'"))
        assertEquals("unchanged", lock.text)
    }
    fun testSharedCatalogVersionRequiresEveryConsumerToAgree() {
        val file = myFixture.addFileToProject("gradle-project/gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
        """.trimIndent())
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertTrue(plan(snapshot, report(snapshot, listOf("1.2.9", null))).changes.isEmpty())
        assertTrue(plan(snapshot, report(snapshot, listOf("1.2.9", "1.2.8"))).changes.isEmpty())
        val prepared = plan(snapshot, report(snapshot))
        assertEquals(1, prepared.changes.size)
        assertTrue(prepared.apply(project))
        assertTrue(file.text.contains("shared = \"1.2.9\""))
    }
    fun testManualReviewNoticeBlocksSharedCatalogEdit() {
        val file = myFixture.addFileToProject("gradle-project/gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            fixtures = { module = "g:fixtures", version.ref = "shared" }
        """.trimIndent())
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val notice = UpdateNotice(snapshot.declarations.last(), NoticeKind.MANUAL_REVIEW, "Dependency capabilities or features need manual review")
        val prepared = plan(snapshot, report(snapshot, listOf("1.2.9", null)).copy(notices = listOf(notice)))
        assertTrue(prepared.changes.isEmpty())
        assertTrue(prepared.skipped.any { it.contains(notice.message) })
        assertTrue(file.text.contains("shared = \"1.2.3\""))
    }
    fun testUnsavedSettingsOrOtherSubprojectChangesInvalidatePreview() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val other = myFixture.addFileToProject("gradle-project/nested/build.gradle.kts", "// original")
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(snapshot, report(snapshot))
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().getDocument(other.virtualFile)!!.setText("// changed") }
        assertFalse(prepared.apply(project))
        assertTrue(file.text.contains("1.2.3"))
        assertNull(adapter.snapshot(project, file.virtualFile))
    }
    fun testCurrentFileScopesAndOptionalAdapterRegistration() {
        val root = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val child = myFixture.addFileToProject("gradle-project/sub/build.gradle.kts", "dependencies { implementation(\"g:beta:1.2.3\") }")
        val excluded = myFixture.addFileToProject("gradle-project/build/generated/build.gradle", "dependencies { implementation 'g:ignored:1.2.3' }")
        val unlinked = myFixture.addFileToProject("unlinked/build.gradle", "dependencies { implementation 'g:ignored:1.2.3' }")
        link()
        assertNotNull(BuildSystemAdapter.find("gradle"))
        assertTrue(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, child.virtualFile.path)))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, unlinked.virtualFile.path)))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE)))
        assertNull(adapter.snapshot(project, excluded.virtualFile))
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, child.virtualFile.path)) } })
        val snapshots = PlatformTestUtil.waitForFuture(future, 30_000)
        assertEquals(listOf(child.virtualFile.path), snapshots.map { it.sourceFile })
        assertEquals(root.virtualFile.parent.path, snapshots.single().context.root)
        GradleSettings.getInstance(project).isOfflineWork = true
        assertTrue(adapter.isOffline(project))
    }
    fun testLinkedSubprojectOutsideRootAndDefaultCatalogOwnership() {
        myFixture.addFileToProject("gradle-project/build.gradle", "")
        val external = myFixture.addFileToProject("external/build.gradle.kts", "dependencies { implementation(\"g:alpha:1.2.3\") }")
        val unrelated = myFixture.addFileToProject("gradle-project/nested/gradle/libs.versions.toml", "[libraries]\na='g:alpha:1.2.3'")
        link()
        val linked = GradleSettings.getInstance(project).linkedProjectsSettings.single()
        linked.setModules(setOf(external.virtualFile.parent.path))
        val snapshot = adapter.snapshot(project, external.virtualFile)!!
        assertEquals(linked.externalProjectPath, snapshot.context.root)
        assertNull(adapter.snapshot(project, unrelated.virtualFile))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, unrelated.virtualFile.path)))
    }

    fun testWarmInputsAreReusedAndUnrelatedDocumentEditsPreserveFingerprint() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val source = myFixture.addFileToProject("gradle-project/src/readme.txt", "original")
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val cache = project.service<GradleProjectCache>()
        val root = snapshot.context.root
        val files = cache.files(root, listOf(root)) { error("Warm discovery traversed the directory again") }
        val hashes = cache.hashes(root, files)
        assertSame(files, cache.files(root, listOf(root)) { error("Warm discovery was not reused") })
        assertSame(hashes, cache.hashes(root, files))
        assertEquals(snapshot, adapter.snapshot(project, file.virtualFile))
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(source.virtualFile)!!.setText("changed")
        }
        assertSame(files, cache.files(root, listOf(root)) { error("A content edit triggered rediscovery") })
        assertTrue(adapter.isCurrent(project, snapshot))
        assertEquals(snapshot, adapter.snapshot(project, file.virtualFile))
    }

    fun testCreatedRenamedMovedAndDeletedInputsInvalidateWarmFingerprints() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val excluded = myFixture.addFileToProject("gradle-project/build/generated.txt", "generated").virtualFile.parent
        link()
        val original = adapter.snapshot(project, file.virtualFile)!!
        val script = myFixture.addFileToProject("gradle-project/plugin.gradle", "// plugin").virtualFile
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(adapter.isCurrent(project, original))
        val created = adapter.snapshot(project, file.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { script.rename(this, "plugin.txt") }
        assertFalse(adapter.isCurrent(project, created))
        assertTrue(adapter.isCurrent(project, original))
        WriteCommandAction.runWriteCommandAction(project) { script.rename(this, "plugin.gradle") }
        val renamed = adapter.snapshot(project, file.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { script.move(this, excluded) }
        assertFalse(adapter.isCurrent(project, renamed))
        assertTrue(adapter.isCurrent(project, original))
        val input = myFixture.addFileToProject("gradle-project/gradle.properties", "key=value").virtualFile
        FileDocumentManager.getInstance().saveAllDocuments()
        val beforeDelete = adapter.snapshot(project, file.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { input.delete(this) }
        assertFalse(adapter.isCurrent(project, beforeDelete))
    }

    fun testWarmCacheTracksSavedChangesAndLinkedModuleSettings() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val script = myFixture.addFileToProject("gradle-project/plugin.gradle", "// original").virtualFile
        val external = myFixture.addFileToProject("external/build.gradle", "dependencies { implementation 'g:beta:1.2.3' }")
        link()
        val original = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(original, report(original))
        WriteCommandAction.runWriteCommandAction(project) { script.setBinaryContent("// changed".toByteArray()) }
        assertFalse(adapter.isCurrent(project, original))
        assertFalse(prepared.apply(project))
        val saved = adapter.snapshot(project, file.virtualFile)!!
        GradleSettings.getInstance(project).linkedProjectsSettings.single().setModules(setOf(external.virtualFile.parent.path))
        assertFalse(adapter.isCurrent(project, saved))
        assertTrue(adapter.snapshot(project, file.virtualFile)!!.fingerprint.files.containsKey(external.virtualFile.path))
        val linked = adapter.snapshot(project, file.virtualFile)!!
        GradleSettings.getInstance(project).linkedProjectsSettings.single().gradleJvm = "different-jvm"
        assertFalse(adapter.isCurrent(project, linked))
    }

    fun testUserHomeChangesOutsideVfsInvalidateWarmPreviewEvenWithSameFileStamp() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        link()
        val settings = GradleSettings.getInstance(project)
        val previous = settings.serviceDirectoryPath
        val home = Files.createTempDirectory("version-checker-gradle-home")
        try {
            settings.serviceDirectoryPath = home.toString()
            val init = Files.createDirectories(home.resolve("init.d")).resolve("repositories.gradle")
            Files.writeString(init, "// original")
            val original = adapter.snapshot(project, file.virtualFile)!!
            val prepared = plan(original, report(original))
            val stamp = Files.getLastModifiedTime(init)
            Files.writeString(init, "// modified")
            Files.setLastModifiedTime(init, stamp)
            assertFalse(adapter.isCurrent(project, original))
            assertFalse(prepared.apply(project))
            val changed = adapter.snapshot(project, file.virtualFile)!!
            Files.writeString(home.resolve("gradle.properties"), "key=value")
            assertFalse(adapter.isCurrent(project, changed))
        } finally {
            settings.serviceDirectoryPath = previous
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    fun testGradleInstallationChangesInvalidateSnapshotsAndPreviews() {
        val file = myFixture.addFileToProject("gradle-project/build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        link()
        val linked = GradleSettings.getInstance(project).linkedProjectsSettings.single()
        linked.gradleHome = null
        val original = adapter.snapshot(project, file.virtualFile)!!
        assertTrue(adapter.isCurrent(project, original))

        linked.gradleHome = "/opt/gradle-first"
        assertFalse(adapter.isCurrent(project, original))
        val first = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(first, report(first))

        linked.gradleHome = "/opt/gradle-second"
        assertFalse(adapter.isCurrent(project, first))
        assertFalse(prepared.apply(project))
        assertTrue(file.text.contains("1.2.3"))

        linked.gradleHome = null
        assertTrue(adapter.isCurrent(project, original))
    }
}
