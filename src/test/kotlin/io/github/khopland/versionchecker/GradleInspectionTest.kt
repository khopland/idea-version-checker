package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings

class GradleInspectionTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    private fun file(path: String, content: String): PsiFile {
        val settings = myFixture.addFileToProject("inspection/settings.gradle", "rootProject.name = 'inspection'")
        val file = myFixture.addFileToProject("inspection/$path", content)
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = settings.virtualFile.parent.path
        })
        FileDocumentManager.getInstance().saveAllDocuments()
        return file
    }

    private fun inspect(file: PsiFile, report: (BuildSnapshot) -> UpdateReport): ProblemsHolder {
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val checking = object : BuildSystemAdapter by adapter {
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = report(snapshot)
        }
        val service = project.service<VersionCheckService>()
        service.updates(checking, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("Gradle report cached", { service.cached(snapshot) != null }, 10_000)
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
        file.accept(NewerGradleDependencyInspection().buildVisitor(holder, true))
        return holder
    }

    fun testLargeInspectionVisitsCandidatesOnceInsteadOfSearchingPerDeclaration() {
        val count = 1_000
        val file = file("build.gradle", (1..count).joinToString("\n", "dependencies {\n", "\n}") {
            "implementation 'g:library-$it:1.2.3'"
        })
        var visits = 0
        val holder = inspect(file) { snapshot ->
            val candidates = snapshot.declarations.map { UpdateCandidate(it, "1.2.9", kind = VersionChangeKind.PATCH) }
            UpdateReport(object : AbstractList<UpdateCandidate>() {
                override val size get() = candidates.size
                override fun get(index: Int): UpdateCandidate { visits++; return candidates[index] }
            })
        }
        assertEquals(count, holder.results.size)
        assertTrue(holder.results.all { it.fixes?.filterIsInstance<UpdateGradleVersionFix>()?.size == 1 && it.fixes?.filterIsInstance<IgnorePublishedVersionFix>()?.size == 1 })
        assertTrue("Candidate traversal must grow linearly, visited $visits entries for $count declarations", visits <= count * 2)
    }

    fun testDuplicateCandidatesDoNotOfferAnArbitraryUpdate() {
        val file = file("build.gradle", "dependencies { implementation 'g:alpha:1.2.3' }")
        val holder = inspect(file) { snapshot ->
            val declaration = snapshot.declarations.single()
            UpdateReport(listOf(UpdateCandidate(declaration, "1.2.9"), UpdateCandidate(declaration, "1.2.8")))
        }
        assertTrue(holder.results.isEmpty())
    }

    fun testSharedVersionPreservesFirstNoticeInReportOrderAndWithholdsFixes() {
        val file = file("gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
        """.trimIndent())
        val holder = inspect(file) { snapshot ->
            val alpha = snapshot.declarations.first { it.artifact.name == "alpha" }
            val beta = snapshot.declarations.first { it.artifact.name == "beta" }
            UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9") }, listOf(
                UpdateNotice(beta, NoticeKind.MANUAL_REVIEW, "Beta needs review first"),
                UpdateNotice(alpha, NoticeKind.DEPRECATED, "Alpha is deprecated"),
            ))
        }
        assertEquals("Beta needs review first", holder.results.single().descriptionTemplate)
        assertTrue(holder.results.single().fixes.isNullOrEmpty())
    }
}
