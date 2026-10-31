package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleRelease
import io.github.khopland.versionchecker.gradle.GradleWrapperAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class GradleWrapperAdapterTest : BasePlatformTestCase() {
    private val now = System.currentTimeMillis()
    private val releases = listOf(
        GradleRelease("8.10", now - TimeUnit.DAYS.toMillis(300)),
        GradleRelease("8.10.2", now - TimeUnit.DAYS.toMillis(200)),
        GradleRelease("8.14.3", now - TimeUnit.DAYS.toMillis(100)),
        GradleRelease("9.1.0", now - TimeUnit.HOURS.toMillis(2)),
    )
    private val adapter = GradleWrapperAdapter { releases }

    override fun setUp() {
        super.setUp()
        // Earlier tests may leave a minimum release age, which would withhold the newest fixture release.
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
    }

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        } finally { super.tearDown() }
    }

    private fun wrapper(name: String, properties: String): PsiFile {
        val root = myFixture.addFileToProject("$name/settings.gradle", "rootProject.name = '$name'")
        val file = myFixture.addFileToProject("$name/gradle/wrapper/gradle-wrapper.properties", properties)
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = root.virtualFile.parent.path })
        FileDocumentManager.getInstance().saveAllDocuments()
        return file
    }

    private fun check(file: PsiFile, mode: UpdateMode): UpdateReport {
        val snapshot = runReadAction { adapter.snapshot(project, file.virtualFile)!! }
        return PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.check(project, snapshot, mode) }
        }), 10_000)
    }

    fun testOfficialDistributionFollowsModesAndReleaseAge() {
        val file = wrapper("official", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n")
        assertTrue(runReadAction { adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)) })
        assertEquals("9.1.0", check(file, UpdateMode.MAJOR).candidates.single().version)
        assertEquals(VersionChangeKind.MAJOR, check(file, UpdateMode.MAJOR).candidates.single().kind)
        assertEquals("8.14.3", check(file, UpdateMode.MINOR).candidates.single().version)
        assertEquals("8.10.2", check(file, UpdateMode.PATCH).candidates.single().version)
        project.service<VersionCheckerSettings>().state.minimumReleaseAgeHours = 24
        assertEquals("8.14.3", check(file, UpdateMode.MAJOR).candidates.single().version)
    }

    fun testMirrorsAndChecksumsNeedManualReview() {
        for ((name, properties) in listOf(
            "mirror" to "distributionUrl=https\\://nexus.example/gradle/gradle-8.10-bin.zip\n",
            "checksum" to "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\ndistributionSha256Sum=abc\n")) {
            val report = check(wrapper(name, properties), UpdateMode.MAJOR)
            assertTrue(name, report.candidates.isEmpty())
            assertEquals(name, NoticeKind.MANUAL_REVIEW, report.notices.single().kind)
        }
    }

    fun testUpdateEditsOnlyTheVersionInTheUrl() {
        val file = wrapper("edit", "distributionBase=GRADLE_USER_HOME\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-all.zip\n")
        val snapshot = runReadAction { adapter.snapshot(project, file.virtualFile)!! }
        val report = check(file, UpdateMode.MINOR)
        val plan = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.prepareUpdates(project, mapOf(snapshot to report)) }
        }), 10_000)
        assertEquals(1, plan.changes.size)
        assertTrue(plan.apply(project))
        assertEquals("distributionBase=GRADLE_USER_HOME\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-all.zip\n", file.text)
    }

    fun testInspectionOffersTheUpdate() {
        val file = wrapper("inspect", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n")
        // Replace the registered wrapper adapter with the fake-release one for this test only.
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(BuildSystemAdapter.EP,
            BuildSystemAdapter.EP.extensionList.filterNot { it is GradleWrapperAdapter } + adapter, testRootDisposable)
        val service = project.service<VersionCheckService>()
        val snapshot = runReadAction { adapter.snapshot(project, file.virtualFile)!! }
        service.updates(adapter, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("Wrapper report cached", { service.cached(snapshot) != null }, 10)
        // Inspection inputs are captured in the background; the first visitor call only starts that capture.
        PlatformTestUtil.waitWithEventsDispatching("Inspection inputs captured", {
            runReadAction { service.inspectionSnapshot(adapter, file.virtualFile) } != null
        }, 10)
        // Run the visitor directly, like the other inspection tests, so daemon state from earlier tests cannot interfere.
        val holder = com.intellij.codeInspection.ProblemsHolder(com.intellij.codeInspection.InspectionManager.getInstance(project), file, true)
        runReadAction { file.accept(io.github.khopland.versionchecker.gradle.GradleWrapperVersionInspection().buildVisitor(holder, true)) }
        val problem = holder.results.single()
        assertTrue(problem.descriptionTemplate, problem.descriptionTemplate.contains("8.10 → 9.1.0"))
        assertEquals(listOf("Update wrapper to 9.1.0"), problem.fixes.orEmpty().map { it.name })
    }
}
