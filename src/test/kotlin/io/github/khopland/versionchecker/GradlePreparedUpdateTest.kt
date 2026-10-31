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

class GradlePreparedUpdateTest : BasePlatformTestCase() {
    private val adapter = GradleBuildSystemAdapter()
    private data class Case(val name: String, val text: String, val allowed: Boolean,
                            val transform: (UpdateReport) -> UpdateReport = { it })

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }

    fun testLocalAndBulkShareLiteralCatalogAndBlockedEligibility() {
        val catalog = """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
        """.trimIndent()
        val cases = listOf(
            Case("literal", "dependencies { implementation 'g:alpha:1.2.3' }", true),
            Case("shared", catalog, true),
            Case("unchanged consumer", catalog, false, { it.copy(candidates = it.candidates.take(1)) }),
            Case("conflicting consumers", catalog, false, { it.copy(candidates = it.candidates.mapIndexed { i, c -> c.copy(version = if (i == 0) "1.2.9" else "1.2.8", replacementSelector = if (i == 0) "1.2.9" else "1.2.8") }) }),
            Case("duplicate native result", catalog, false, { it.copy(candidates = it.candidates + it.candidates.first()) }),
            Case("failed report", catalog, false, { it.copy(failure = "Native check failed") }),
            Case("notice", catalog, false, { it.copy(notices = listOf(UpdateNotice(it.candidates.last().declaration, NoticeKind.MANUAL_REVIEW, "Capabilities need review"))) }),
            Case("plugin consumer", catalog + "\n[plugins]\napplication = { id = \"g.application\", version.ref = \"shared\" }", true),
            Case("plugin consumer without update", catalog + "\n[plugins]\napplication = { id = \"g.application\", version.ref = \"shared\" }", false,
                { it.copy(candidates = it.candidates.filterNot { c -> c.declaration.id.location.startsWith("plugins/") }) }),
            Case("dynamic", "dependencies { implementation 'g:alpha:1.+' }", false),
            Case("property", "dependencies { implementation 'g:alpha:\$alphaVersion' }", false),
            Case("map notation", "dependencies { implementation group: 'g', name: 'alpha', version: '1.2.3' }", false),
            Case("downgrade", "dependencies { implementation 'g:alpha:1.2.3' }", false, { it.copy(candidates = it.candidates.map { c -> c.copy(version = "1.2.2", replacementSelector = "1.2.2") }) }),
            Case("channel switch", "dependencies { implementation 'g:alpha:1.2.3-jre' }", false),
            Case("wrong baseline", "dependencies { implementation 'g:alpha:1.2.3' }", false, { it.copy(candidates = it.candidates.map { c -> c.copy(declaration = c.declaration.copy(baseline = "1.2.0")) }) }),
            Case("wrong artifact", "dependencies { implementation 'g:alpha:1.2.3' }", false, { it.copy(candidates = it.candidates.map { c -> c.copy(declaration = c.declaration.copy(artifact = ArtifactId("g", "other"))) }) }),
            Case("unsupported replacement", "dependencies { implementation 'g:alpha:1.2.3' }", false, { it.copy(candidates = it.candidates.map { c -> c.copy(replacementSelector = "1.+") }) })
        )
        for ((index, case) in cases.withIndex()) {
            val root = myFixture.addFileToProject("prepared-$index/settings.gradle", "rootProject.name = 'prepared'")
            val path = if (case.text.startsWith("[versions]")) "gradle/libs.versions.toml" else "build.gradle"
            val file = myFixture.addFileToProject("prepared-$index/$path", case.text)
            GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply { externalProjectPath = root.virtualFile.parent.path })
            FileDocumentManager.getInstance().saveAllDocuments()
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            val report = case.transform(UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "1.2.9", kind = VersionChangeKind.PATCH) }))
            val checking = object : BuildSystemAdapter by adapter {
                override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) = report
            }
            val service = project.service<VersionCheckService>()
            service.invalidateForPreview(checking, file.virtualFile)
            service.updates(checking, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Gradle report cached", { service.cached(snapshot) != null }, 10_000)
            val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            file.accept(NewerGradleDependencyInspection().buildCapturedVisitor(holder, true))
            val local = holder.results.flatMap { it.fixes.orEmpty().filterIsInstance<UpdateGradleVersionFix>() }
            val bulk = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { adapter.prepareUpdates(project, mapOf(snapshot to report)) }
            }), 30_000)
            assertEquals("Local: ${case.name}", case.allowed, local.isNotEmpty())
            assertEquals("Bulk: ${case.name}", case.allowed, bulk.changes.isNotEmpty())
            assertEquals(case.text, file.text)
            if (case.allowed) {
                val prepared = GradlePreparedUpdate.prepare(file, report).single()
                assertEquals(snapshot.declarations.map { it.id }.toSet(), prepared.affectedDeclarations)
                assertEquals(file.virtualFile.path, prepared.target!!.file)
                assertEquals("1.2.3", case.text.substring(prepared.target.range.startOffset, prepared.target.range.endOffset))
                val descriptor = holder.results.single()
                local.single().applyFix(project, descriptor)
                assertEquals(case.text.replace("1.2.3", "1.2.9"), file.text)
            } else assertTrue("Blocked: ${case.name}", bulk.skipped.isNotEmpty())
        }
    }

    fun testSharedVersionUsesFirstNoticeInReportOrder() {
        val file = myFixture.addFileToProject("gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
        """.trimIndent())
        val declarations = GradleDeclarations.parse(file.virtualFile.path, file.text).map { it.declaration }
        val firstNotice = UpdateNotice(declarations.last(), NoticeKind.MANUAL_REVIEW, "First native notice")
        val report = UpdateReport(declarations.map { UpdateCandidate(it, "1.2.9") }, listOf(firstNotice,
            UpdateNotice(declarations.first(), NoticeKind.DEPRECATED, "Second native notice")))
        val prepared = GradlePreparedUpdate.prepare(file, report).single()
        assertEquals(firstNotice, prepared.notice)
        assertNull(prepared.edit)
        assertEquals(firstNotice.message, prepared.blockingReasons.first())
    }

    fun testPhysicalDeclarationCannotBeBorrowedFromAnIdenticalSibling() {
        val file = myFixture.addFileToProject("build.gradle", "dependencies { implementation 'g:alpha:1.2.3'; implementation 'g:beta:1.2.3' }")
        val parsed = GradleDeclarations.parse(file.virtualFile.path, file.text)
        val alpha = parsed.first().declaration
        val beta = parsed.last().declaration
        val report = UpdateReport(listOf(UpdateCandidate(alpha.copy(id = beta.id), "1.2.9")))
        val prepared = GradlePreparedUpdate.prepare(file, report)
        assertTrue(prepared.all { it.edit == null })
        assertEquals(2, prepared.map { it.target }.distinct().size)
        assertTrue(prepared.last().blockingReasons.any { it.contains("physical declaration") })
    }
}
