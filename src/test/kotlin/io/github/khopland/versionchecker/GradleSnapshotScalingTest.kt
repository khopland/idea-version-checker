package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable

class GradleSnapshotScalingTest : BasePlatformTestCase() {
    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 60_000)

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    fun testLargeBuildDiscoveryValidationAndApplication() {
        val adapter = GradleBuildSystemAdapter()
        val root = myFixture.addFileToProject("large/build.gradle", "")
        myFixture.addFileToProject("large/settings.gradle", "rootProject.name = 'large'")
        val dependencies = (1..100).joinToString("\n") { "implementation 'g:dependency-$it:1.2.3'" }
        val files = (1..100).map {
            myFixture.addFileToProject("large/modules/module-$it/build.gradle", "dependencies {\n$dependencies\n}")
        }
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = root.virtualFile.parent.path
            setModules(files.map { it.virtualFile.parent.path }.toSet())
        })
        FileDocumentManager.getInstance().saveAllDocuments()
        val start = System.nanoTime()
        val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
        val discoveryMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(101, snapshots.size)
        assertEquals(10_000, snapshots.sumOf { it.declarations.size })
        val warmStart = System.nanoTime()
        assertEquals(snapshots, background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) })
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000
        val reports = snapshots.associateWith { snapshot -> UpdateReport(snapshot.declarations.take(1).map { UpdateCandidate(it, "1.2.9") }) }
        val preparationStart = System.nanoTime()
        val plan = background { adapter.prepareUpdates(project, reports) }
        val preparationMs = (System.nanoTime() - preparationStart) / 1_000_000
        assertEquals(100, plan.changes.size)
        val validationStart = System.nanoTime()
        assertTrue(snapshots.all { adapter.isCurrent(project, it) })
        val validationMs = (System.nanoTime() - validationStart) / 1_000_000
        val applicationStart = System.nanoTime()
        assertTrue(plan.apply(project))
        val applicationMs = (System.nanoTime() - applicationStart) / 1_000_000
        assertTrue(files.all { it.text.contains("g:dependency-1:1.2.9") })
        println("Gradle benchmark: 101 build files, 10,000 declarations; discovery=${discoveryMs}ms, " +
            "warm discovery=${warmMs}ms, preparation=${preparationMs}ms, individual validation=${validationMs}ms, application=${applicationMs}ms")
    }
}
