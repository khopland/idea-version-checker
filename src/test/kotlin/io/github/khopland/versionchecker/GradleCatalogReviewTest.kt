package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable

class GradleCatalogReviewTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    private fun link() {
        val settingsFile = myFixture.addFileToProject("gradle-project/settings.gradle", "rootProject.name = 'fixture'")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = settingsFile.virtualFile.parent.path
        })
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 30_000)

    fun testMalformedCatalogEntryHasReviewCoverageAndCannotProduceAnEdit() {
        val file = myFixture.addFileToProject("gradle-project/gradle/libs.versions.toml",
            "[libraries]\nalpha = { module = 123, version = '1.2.3' }")
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val declaration = snapshot.declarations.single()
        assertEquals("libraries/alpha", declaration.id.location)
        assertTrue(declaration.baseline.isEmpty())
        assertTrue(snapshot.skippedDeclarations.getValue(declaration.id).contains("manual review"))
        val checked = background { adapter.check(project, snapshot, UpdateMode.MAJOR) }
        assertEquals(CoverageSummary(skipped = 1), checked.coverageSummary(snapshot))
        assertTrue(checked.candidates.isEmpty())
        val prepared = background { adapter.prepareUpdates(project, mapOf(snapshot to checked)) }
        assertTrue(prepared.changes.isEmpty())
        // BulkUpdateDialog displays this list in its Needs review section.
        assertTrue(prepared.skipped.single().contains("libraries/alpha"))
        assertTrue(prepared.skipped.single().contains("manual review"))
    }

    fun testMalformedSharedConsumerBlocksSharedEditWhileDirectSiblingRemainsEditable() {
        val original = """
            [versions]
            shared = "1.2.3"
            [libraries]
            broken = { module = 123, version.ref = "shared" }
            valid = { module = "g:valid", version.ref = "shared" }
            direct = "g:direct:1.2.3"
        """.trimIndent()
        val file = myFixture.addFileToProject("gradle-project/gradle/libs.versions.toml", original)
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertEquals(setOf("libraries/broken", "libraries/valid"), snapshot.skippedDeclarations.keys.map { it.location }.toSet())
        // Even a supplied candidate cannot authorize a malformed declaration or its
        // shared consumer. The direct sibling has independent version ownership.
        val report = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })
        val prepared = background { adapter.prepareUpdates(project, mapOf(snapshot to report)) }
        assertEquals(1, prepared.changes.size)
        assertTrue(prepared.changes.single().location.contains("libraries/direct"))
        assertTrue(prepared.skipped.any { it.contains("libraries/broken") })
        assertTrue(prepared.skipped.any { it.contains("libraries/valid") && it.contains("ownership") })
        assertTrue(prepared.apply(project))
        assertEquals(original.replace("g:direct:1.2.3", "g:direct:1.2.9"), file.text)
    }
}
