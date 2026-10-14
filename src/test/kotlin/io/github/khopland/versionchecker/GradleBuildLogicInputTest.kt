package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable

class GradleBuildLogicInputTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)
    private fun link() = myFixture.addFileToProject("project/build.gradle", "dependencies { implementation 'g:library:1.2.3' }").also {
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = it.virtualFile.parent.path })
        FileDocumentManager.getInstance().saveAllDocuments()
    }
    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }
    private fun plan(snapshot: BuildSnapshot) = background {
        adapter.prepareUpdates(project, mapOf(snapshot to UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })))
    }

    fun testBuildSrcKotlinJavaAndResourcesRejectSavedAndUnsavedHeldEdits() {
        val source = link()
        for (path in listOf("src/main/kotlin/Policy.kt", "src/main/java/Policy.java", "src/main/resources/policy.properties")) {
            val input = myFixture.addFileToProject("project/buildSrc/$path", "original")
            FileDocumentManager.getInstance().saveAllDocuments()
            val snapshot = adapter.snapshot(project, source.virtualFile)!!
            assertTrue(snapshot.fingerprint.files.containsKey(input.virtualFile.path))
            assertTrue(background { adapter.resolutionInputPaths(project, BuildSelection(UpdateScope.CURRENT_FILE, source.virtualFile.path)) }.contains(input.virtualFile.path))
            val held = plan(snapshot)
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(input.virtualFile)!!.setText("changed")
            }
            assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
            assertFalse(adapter.canCheckInBackground(project, snapshot))
            assertNull(adapter.snapshot(project, source.virtualFile))
            assertFalse(held.apply(project))
            FileDocumentManager.getInstance().saveAllDocuments()
            assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
            assertFalse(held.apply(project))
        }
        assertTrue(source.text.contains("1.2.3"))
    }

    fun testAddedBuildLogicInvalidatesInventoryButApplicationSourcesAndGeneratedOutputsDoNot() {
        val source = link()
        myFixture.addFileToProject("project/buildSrc/src/main/kotlin/Policy.kt", "original")
        FileDocumentManager.getInstance().saveAllDocuments()
        val snapshot = adapter.snapshot(project, source.virtualFile)!!
        myFixture.addFileToProject("project/src/main/kotlin/Application.kt", "application")
        myFixture.addFileToProject("project/buildSrc/build/generated/Policy.kt", "generated")
        assertTrue(adapter.isCurrent(project, snapshot))
        val held = plan(snapshot)
        myFixture.addFileToProject("project/buildSrc/src/main/resources/new-policy.json", "{}")
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(adapter.isCurrent(project, snapshot))
        assertFalse(held.apply(project))
    }

    fun testUnlinkedSiblingIncludedBuildTracksItsSourcesAndResources() {
        val source = link()
        myFixture.addFileToProject("project/settings.gradle.kts", "pluginManagement { includeBuild(\"../build-logic\") }")
        val logic = myFixture.addFileToProject("build-logic/src/main/java/Policy.java", "original")
        myFixture.addFileToProject("build-logic/src/main/resources/policy.properties", "original")
        FileDocumentManager.getInstance().saveAllDocuments()
        val snapshot = adapter.snapshot(project, source.virtualFile)!!
        assertTrue(snapshot.fingerprint.files.containsKey(logic.virtualFile.path))
        val held = plan(snapshot)
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(logic.virtualFile)!!.setText("changed")
        }
        assertFalse(background { adapter.isCurrentInBackground(project, snapshot) })
        assertFalse(held.apply(project))
    }

    fun testExternalOrComputedScriptInputsRequireManualReview() {
        val source = link()
        val settings = myFixture.addFileToProject("project/settings.gradle", "includeBuild(buildLogicLocation)")
        FileDocumentManager.getInstance().saveAllDocuments()
        val dynamicSnapshot = adapter.snapshot(project, source.virtualFile)!!
        val dynamic = plan(dynamicSnapshot)
        assertFalse("Local quick fixes share the coverage guard", background { adapter.prepareEditValidation(project, listOf(dynamicSnapshot)) }())
        val dynamicReport = background { adapter.check(project, dynamicSnapshot, UpdateMode.MAJOR) }
        assertTrue(dynamicReport.candidates.isEmpty())
        assertEquals(NoticeKind.MANUAL_REVIEW, dynamicReport.notices.single().kind)
        assertTrue(dynamic.changes.isEmpty())
        assertTrue(dynamic.skipped.single().contains("Computed included build paths"))
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(settings.virtualFile)!!.setText("apply from: '../external.gradle'")
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        val externalSnapshot = adapter.snapshot(project, source.virtualFile)!!
        val external = plan(externalSnapshot)
        assertFalse("Local quick fixes cannot authorize untracked external scripts", background { adapter.prepareEditValidation(project, listOf(externalSnapshot)) }())
        assertEquals(NoticeKind.MANUAL_REVIEW, background { adapter.check(project, externalSnapshot, UpdateMode.MAJOR) }.notices.single().kind)
        assertTrue(external.changes.isEmpty())
        assertTrue(external.skipped.single().contains("External applied scripts"))
    }

    fun testLiteralPrefixDoesNotAuthorizeComputedBuildOrScriptPaths() {
        val source = link()
        myFixture.addFileToProject("project/logic/build.gradle", "// existing literal prefix")
        myFixture.addFileToProject("project/policy.gradle", "// existing literal prefix")
        val settings = myFixture.addFileToProject("project/settings.gradle", "")
        for (expression in listOf("includeBuild('logic' + suffix)", "apply from: 'policy.gradle' + suffix")) {
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(settings.virtualFile)!!.setText(expression)
            }
            FileDocumentManager.getInstance().saveAllDocuments()
            val snapshot = adapter.snapshot(project, source.virtualFile)!!
            assertFalse(background { adapter.prepareEditValidation(project, listOf(snapshot)) }())
            val prepared = plan(snapshot)
            assertTrue(prepared.changes.isEmpty())
            assertTrue(prepared.skipped.single().contains("Computed"))
        }
    }
}
