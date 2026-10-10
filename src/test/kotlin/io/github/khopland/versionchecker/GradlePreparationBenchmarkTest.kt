package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable
import kotlin.system.measureNanoTime

/** Opt-in headless cached-result preparation, excluding native Gradle and editor rendering. */
class GradlePreparationBenchmarkTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    fun testCachedInspectionAndPreviewPreparation() {
        val adapter = GradleBuildSystemAdapter()
        for (count in listOf(100, 1_000, 10_000)) {
            val directory = "gradle-preparation-$name-$count"
            val settingsFile = myFixture.addFileToProject("$directory/settings.gradle", "rootProject.name = 'fixture'")
            val file = myFixture.addFileToProject("$directory/build.gradle", (0 until count).joinToString("\n", "dependencies {\n", "\n}") {
                "implementation 'g:library-$it:1.2.3' // preserve $it"
            })
            GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
                externalProjectPath = settingsFile.virtualFile.parent.path
            })
            FileDocumentManager.getInstance().saveAllDocuments()
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            val report = UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9", kind = VersionChangeKind.PATCH) })
            val checking = object : BuildSystemAdapter by adapter {
                override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = report
            }
            val service = project.service<VersionCheckService>()
            service.updates(checking, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Cached Gradle benchmark report", { service.cached(snapshot) != null }, 10)
            fun visit(): Long {
                val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
                val elapsed = measureNanoTime { file.accept(NewerGradleDependencyInspection().buildVisitor(holder, true)) }
                assertEquals(count, holder.results.size)
                assertTrue(holder.results.all { it.fixes!!.filterIsInstance<UpdateGradleVersionFix>().single().name == "Update declared version to 1.2.9" })
                return elapsed
            }
            fun preview(): Long = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    lateinit var plan: BulkUpdatePlan
                    val elapsed = measureNanoTime { plan = adapter.prepareUpdates(project, mapOf(snapshot to report)) }
                    assertEquals(count, plan.changes.size)
                    assertTrue(plan.skipped.isEmpty())
                    assertTrue(plan.changes.all { it.expected == "1.2.3" && it.latest == "1.2.9" })
                    val ranges = plan.changes.map { (it as GradleVersionEdit).range.startOffset }
                    assertEquals(ranges.sortedDescending(), ranges)
                    elapsed
                }
            }), 30_000)
            repeat(2) { visit(); preview() }
            val visitor = mutableListOf<Long>()
            val plans = mutableListOf<Long>()
            repeat(5) { sample ->
                if (sample % 2 == 0) { visitor += visit(); plans += preview() }
                else { plans += preview(); visitor += visit() }
            }
            for ((path, samples) in listOf("inspection" to visitor, "preview" to plans)) {
                val millis = samples.map { it / 1_000_000.0 }
                println("gradle-preparation declarations=$count path=$path samplesMs=$millis medianMs=${millis.sorted()[2]} p95Ms=${millis.max()}")
            }
        }
    }
}
