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

class GradleNotationReviewTest : BasePlatformTestCase() {
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

    fun testExtendedAndMapNotationHaveReviewCoverageAndCannotProduceEdits() {
        val original = """
            dependencies {
                implementation 'g:classifier:1.2.3:tests'
                implementation 'g:extension:1.2.3@jar'
                implementation group: 'g', name: 'map', version: '1.2.3'
            }
        """.trimIndent()
        val file = myFixture.addFileToProject("gradle-project/build.gradle", original)
        link()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertEquals(3, snapshot.declarations.size)
        assertEquals(3, snapshot.skippedDeclarations.size)
        assertTrue(snapshot.declarations.all { it.baseline.isEmpty() })
        val checked = background { adapter.check(project, snapshot, UpdateMode.MAJOR) }
        assertEquals(CoverageSummary(skipped = 3), checked.coverageSummary(snapshot))
        assertTrue(checked.candidates.isEmpty())
        assertTrue(checked.coverage!!.values.all { it.reason!!.contains("manual review") })
        val review = background { adapter.prepareUpdates(project, mapOf(snapshot to checked)) }
        assertEquals(3, review.skipped.size)
        assertTrue(review.changes.isEmpty())
        // A supplied candidate must not bypass review-only declaration ownership.
        val supplied = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") })
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to supplied)) }
        assertTrue(plan.changes.isEmpty())
        assertEquals(3, plan.skipped.size)
        assertEquals(original, file.text)
    }
}
