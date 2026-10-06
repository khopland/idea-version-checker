package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable

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
}
