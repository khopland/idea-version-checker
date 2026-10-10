package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.util.concurrent.CopyOnWriteArrayList

class OtherManagerWarningRetentionTest : BasePlatformTestCase() {
    private val npm = NpmBuildSystemAdapter()
    private val gradle = GradleBuildSystemAdapter()
    private val latest = "1.2.99"

    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
            settings.isOfflineWork = false
        } finally { super.tearDown() }
    }

    private fun snapshot(adapter: BuildSystemAdapter, file: PsiFile) = adapter.inspectionSnapshot(project, file.virtualFile)!!
    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.filter {
        it.baseline.isNotEmpty() && it.baseline != latest
    }.map {
        UpdateCandidate(it, latest, if (snapshot.context.adapterId == "npm")
            NpmSelector.parse(it.artifact.name, it.selector)!!.replace(latest) else latest)
    })

    private fun linkGradle() {
        val settings = myFixture.addFileToProject("gradle/settings.gradle", "rootProject.name = 'fixture'")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = settings.virtualFile.parent.path
        })
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun applyNpm(file: PsiFile, report: UpdateReport) {
        val candidate = report.candidates.first()
        val value = NpmManifest.values(file)[candidate.declaration.id]!!
        val fix = npm.quickFixes(project, snapshot(npm, file), candidate, value).first()
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            value, "Newer version", fix, ProblemHighlightType.WARNING, false)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
    }

    private fun applyGradle(file: PsiFile, report: UpdateReport) {
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, false)
        NewerGradleDependencyInspection().buildVisitor(holder, false).visitFile(file)
        assertEquals(report.candidates.size, holder.results.size)
        val descriptor = holder.results.first()
        descriptor.fixes!!.filterIsInstance<UpdateGradleVersionFix>().single().applyFix(project, descriptor)
    }

    private fun verifySequentialUpdates(adapter: BuildSystemAdapter, first: PsiFile, sibling: PsiFile,
                                        apply: (PsiFile, UpdateReport) -> Unit) {
        val checked = CopyOnWriteArrayList<BuildSnapshot>()
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val checkingAdapter = object : BuildSystemAdapter by adapter {
            override val capabilities = adapter.capabilities.copy(incrementalInspections = false)
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                checked += snapshot
                gate.await()
                return report(snapshot)
            }
        }
        val service = project.service<VersionCheckService>()
        val initial = listOf(snapshot(adapter, first), snapshot(adapter, sibling))
        initial.forEach { service.updates(checkingAdapter, it) }
        PlatformTestUtil.waitWithEventsDispatching("Initial warnings cached", {
            initial.all { service.cached(it) != null }
        }, 10_000)
        gate = CompletableDeferred()
        try {
            apply(first, service.cached(initial.first())!!)
            val edited = snapshot(adapter, first)
            assertNull(service.cached(edited))
            val remaining = service.updates(checkingAdapter, edited)!!
            assertEquals(1, remaining.candidates.size)
            assertEquals(1, service.updates(checkingAdapter, snapshot(adapter, sibling))!!.candidates.size)
            if (adapter.id == "gradle") {
                assertNull("Bulk discovery still requires saved files", adapter.snapshot(project, first.virtualFile))
                assertFalse(adapter.canCheckInBackground(project, edited))
                assertEquals("Unsaved Gradle edits must not start native resolution", 2, checked.size)
                // Prove the retained warning is exposed through the actual Gradle inspection.
                val holder = ProblemsHolder(InspectionManager.getInstance(project), first, false)
                NewerGradleDependencyInspection().buildVisitor(holder, false).visitFile(first)
                assertEquals(1, holder.results.size)
                assertNotNull(holder.results.single().fixes?.filterIsInstance<UpdateGradleVersionFix>()?.singleOrNull())
                FileDocumentManager.getInstance().saveAllDocuments()
                service.updates(checkingAdapter, snapshot(adapter, first))
                service.updates(checkingAdapter, snapshot(adapter, sibling))
            }
            PlatformTestUtil.waitWithEventsDispatching("Recheck is blocked", { checked.size == 3 }, 10_000)
            apply(first, service.updates(checkingAdapter, snapshot(adapter, first))!!)
            assertTrue(service.updates(checkingAdapter, snapshot(adapter, first))!!.candidates.isEmpty())
            apply(sibling, service.updates(checkingAdapter, snapshot(adapter, sibling))!!)
            assertTrue(service.updates(checkingAdapter, snapshot(adapter, sibling))!!.candidates.isEmpty())
            FileDocumentManager.getInstance().saveAllDocuments()
            val current = listOf(snapshot(adapter, first), snapshot(adapter, sibling))
            current.forEach { service.updates(checkingAdapter, it) }
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Fresh reports replace retained warnings", {
                current.all { service.cached(it)?.candidates?.isEmpty() == true }
            }, 10_000)
            assertTrue(first.text.contains(latest))
            assertTrue(sibling.text.contains(latest))
        } finally { gate.complete(Unit) }
    }

    fun testNpmWorkspaceWarningsAndAliasQuickFixesRemainAvailableDuringRecheck() {
        val root = myFixture.addFileToProject("npm/package.json", """{
            "workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3","alias":"npm:beta@~1.2.3"}
        }""")
        val sibling = myFixture.addFileToProject("npm/packages/app/package.json",
            """{"name":"app","dependencies":{"gamma":"1.2.3"}}""")
        verifySequentialUpdates(npm, root, sibling, ::applyNpm)
        assertTrue(root.text.contains("npm:beta@~$latest"))
    }

    fun testGradleScriptWarningsAndQuickFixesRemainAvailableWithUnsavedAndSavedEdits() {
        val first = myFixture.addFileToProject("gradle/build.gradle", """dependencies {
            implementation 'g:alpha:1.2.3'
            testImplementation 'g:beta:1.2.3'
        }""")
        val sibling = myFixture.addFileToProject("gradle/app/build.gradle.kts",
            """dependencies { implementation("g:gamma:1.2.3") }""")
        linkGradle()
        verifySequentialUpdates(gradle, first, sibling, ::applyGradle)
    }

    fun testGradleSharedCatalogVersionWarningsAreRemovedTogetherAndOtherFixesRemainAvailable() {
        val file = myFixture.addFileToProject("gradle/gradle/libs.versions.toml", """
            [versions]
            shared = "1.2.3"
            other = "1.2.3"
            [libraries]
            alpha = { module = "g:alpha", version.ref = "shared" }
            beta = { module = "g:beta", version.ref = "shared" }
            gamma = { module = "g:gamma", version.ref = "other" }
        """.trimIndent())
        linkGradle()
        val previous = snapshot(gradle, file)
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), report(previous))
        val range = GradleDeclarations.parse(file.virtualFile.path, file.text).first().range!!
        val fix = UpdateGradleVersionFix(GradleVersionEdit(file, range, latest, "catalog")) { gradle.isCurrent(project, previous) }
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            file, "Newer version", fix, ProblemHighlightType.WARNING, false)
        fix.applyFix(project, descriptor)
        val current = snapshot(gradle, file)
        assertNull(cache.get(current))
        val retained = cache.retainedInspectionReport(gradle, current)!!
        assertEquals(listOf("gamma"), retained.candidates.map { it.declaration.artifact.name })
        val next = GradleDeclarations.parse(file.virtualFile.path, file.text).last().range!!
        val nextFix = UpdateGradleVersionFix(GradleVersionEdit(file, next, latest, "catalog")) { gradle.isCurrent(project, current) }
        nextFix.applyFix(project, descriptor)
        assertTrue(cache.retainedInspectionReport(gradle, snapshot(gradle, file))!!.candidates.isEmpty())
    }

    fun testNpmRegistryAndWorkspaceConfigurationChangesDiscardRetainedWarnings() {
        val file = myFixture.addFileToProject("npm/package.json",
            """{"dependencies":{"alpha":"^1.2.3"}}""")
        val config = myFixture.addFileToProject("npm/.npmrc", "registry=https://original.example/")
        val previous = snapshot(npm, file)
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), report(previous))
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(config.virtualFile)!!.setText("registry=https://changed.example/")
        }
        assertNull(cache.retainedInspectionReport(npm, snapshot(npm, file)))
        assertNotNull(cache.retainedInspectionReport(npm, previous))
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(config.virtualFile)!!.setText("registry=https://original.example/")
            FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.setText(
                """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3"}}""")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertNull(cache.retainedInspectionReport(npm, snapshot(npm, file)))
        cache.invalidateAdapter(npm.id)
        assertNull(cache.retainedInspectionReport(npm, previous))
    }

    fun testGradleRepositoryEditsAndSettingsChangesDiscardRetainedWarnings() {
        val file = myFixture.addFileToProject("gradle/build.gradle", """repositories { mavenCentral() }
            dependencies { implementation 'g:alpha:1.2.3' }""")
        linkGradle()
        val previous = snapshot(gradle, file)
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), report(previous))
        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.setText(file.text.replace("mavenCentral()", "mavenLocal()"))
        }
        assertNull(cache.retainedInspectionReport(gradle, snapshot(gradle, file)))
        assertNotNull(cache.retainedInspectionReport(gradle, previous))
        GradleSettings.getInstance(project).linkedProjectsSettings.single().gradleJvm = "different-jvm"
        assertNull(cache.retainedInspectionReport(gradle, snapshot(gradle, file)))
    }
}
