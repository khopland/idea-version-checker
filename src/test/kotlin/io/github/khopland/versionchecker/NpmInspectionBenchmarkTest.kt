package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import java.util.Collections
import java.util.IdentityHashMap

/** Opt-in headless visitor timing. Complete cached reports; no native query or editor rendering. */
class NpmInspectionBenchmarkTest : BasePlatformTestCase() {
    fun testRepeatedAliasesAcrossTenThousandWorkspaceDeclarations() {
        val adapter = NpmBuildSystemAdapter()
        for ((manifests, aliases) in listOf(2 to 2, 100 to 100)) {
            val directory = "inspection-benchmark-$name-$manifests"
            myFixture.addFileToProject("$directory/package.json", """{"workspaces":["packages/*"]}""")
            val members = (0 until manifests).map { member ->
                val dependencies = (0 until aliases).joinToString(",") { "\"alias-$it\":\"npm:alpha@^1.2.3\"" }
                myFixture.addFileToProject("$directory/packages/member-$member/package.json", """{"dependencies":{$dependencies}}""")
            }
            val file = members.first()
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            val checking = object : BuildSystemAdapter by adapter {
                override val capabilities = adapter.capabilities.copy(incrementalInspections = false)
                override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                    UpdateReport(snapshot.declarations.map {
                        UpdateCandidate(it, "1.2.9", NpmSelector.parse(it.artifact.name, it.selector)!!.replace("1.2.9"), VersionChangeKind.PATCH)
                    })
            }
            val service = project.service<VersionCheckService>()
            service.updates(checking, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Cached npm benchmark report", { service.cached(snapshot) != null }, 10)
            data class Sample(val elapsed: Long, val first: Long, val workspaceFixes: Int)
            fun visit(): Sample {
                val started = System.nanoTime()
                val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
                val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
                var first = 0L
                PsiTreeUtil.processElements(file) { element ->
                    element.accept(visitor)
                    if (first == 0L && holder.hasResults()) first = System.nanoTime() - started
                    true
                }
                val elapsed = System.nanoTime() - started
                assertEquals(aliases, holder.results.size)
                assertTrue(holder.results.all { it.fixes!!.size == 2 })
                val workspace = Collections.newSetFromMap(IdentityHashMap<LocalQuickFix, Boolean>())
                holder.results.forEach {
                    val fixes = it.fixes!!
                    assertEquals("Update locally to npm:alpha@^1.2.9", fixes[0].name)
                    assertEquals("Update alpha across workspace to 1.2.9 (${manifests * aliases} declarations)", fixes[1].name)
                    workspace += fixes[1] as LocalQuickFix
                }
                assertEquals("Equivalent workspace actions must prepare their edits only once per pass", 1, workspace.size)
                return Sample(elapsed, first, workspace.size)
            }
            repeat(2) { visit() }
            val samples = (0 until 5).map { visit() }
            val elapsed = samples.map { it.elapsed / 1_000_000.0 }
            val first = samples.map { it.first / 1_000_000.0 }
            println("npm-inspection manifests=$manifests declarations=${manifests * aliases} warnings=$aliases samplesMs=$elapsed medianMs=${elapsed.sorted()[2]} p95Ms=${elapsed.max()} firstProblemSamplesMs=$first firstProblemMedianMs=${first.sorted()[2]} distinctWorkspaceFixes=${samples.map { it.workspaceFixes }}")
        }
    }
}
