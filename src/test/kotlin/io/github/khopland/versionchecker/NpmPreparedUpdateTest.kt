package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable

class NpmPreparedUpdateTest : BasePlatformTestCase() {
    private val adapter = NpmBuildSystemAdapter()
    private fun plan(reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan =
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.prepareUpdates(project, reports) }
        }), 30_000)

    fun testLocalAndBulkShareEligibilityAndPreserveReplacementForSyntheticCandidates() {
        val dollar = '$'
        data class Case(val manifest: String, val location: String, val replacement: String, val allowed: Boolean,
                        val syntheticBaseline: String? = null)
        val cases = listOf(
            Case("""{"dependencies":{"alpha":"^1.2.3"}}""", "dependencies/alpha", "^1.2.9", true),
            Case("""{"dependencies":{"alias":"npm:alpha@~1.2.3"}}""", "dependencies/alias", "npm:alpha@~1.2.9", true),
            Case("""{"dependencies":{"alpha":"1.2.3"},"overrides":{"alpha":"${dollar}alpha"}}""", "dependencies/alpha", "1.2.9", true),
            Case("""{"dependencies":{"alpha":"1.2.3"},"overrides":{"alpha":"1.2.3"}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alpha":"1.2.3"},"overrides":{"alpha":{".":"1.2.3"}}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alpha":"1.2.3"},"overrides":{"parent":{"alpha":"1.2.3"}}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alpha":"1.2.3"},"overrides":{"alpha@1.2.3":"1.2.3"}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alpha":"1.2.3","beta":"1.2.3"},"overrides":{"beta":"${dollar}alpha"}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alias":"npm:alpha@~1.2.3"},"overrides":{"alpha":"1.2.3"}}""", "dependencies/alias", "npm:alpha@~1.2.9", false),
            Case("""{"peerDependencies":{"alpha":"^1.2.3"}}""", "peerDependencies/alpha", "^1.2.9", false),
            Case("""{"dependencies":{"alpha":">=1 <2"}}""", "dependencies/alpha", "1.2.9", false),
            Case("""{"dependencies":{"alpha":">=1 <2"}}""", "dependencies/alpha", "1.2.9", false, "1.2.3"),
            Case("""{"dependencies":{"alpha":"^1.2.3"}}""", "dependencies/alpha", "^1.2.9", false, "1.2.0")
        )
        for ((index, case) in cases.withIndex()) {
            val local = myFixture.addFileToProject("parity-$index/local/package.json", case.manifest)
            val bulk = myFixture.addFileToProject("parity-$index/bulk/package.json", case.manifest)
            val localSnapshot = adapter.snapshot(project, local.virtualFile)!!
            val bulkSnapshot = adapter.snapshot(project, bulk.virtualFile)!!
            fun declaration(snapshot: BuildSnapshot) = snapshot.declarations.single { it.id.location == case.location }.let {
                if (case.syntheticBaseline == null) it else it.copy(baseline = case.syntheticBaseline)
            }
            val localDeclaration = declaration(localSnapshot)
            val bulkDeclaration = declaration(bulkSnapshot)
            val value = NpmManifest.values(local).getValue(localDeclaration.id)
            val fixes = adapter.quickFixes(project, localSnapshot,
                UpdateCandidate(localDeclaration, "1.2.9", case.replacement), value)
            val bulkPlan = plan(mapOf(bulkSnapshot to UpdateReport(listOf(
                UpdateCandidate(bulkDeclaration, "1.2.9", case.replacement)))))
            assertEquals("Local eligibility for case $index", case.allowed, fixes.isNotEmpty())
            assertEquals("Bulk eligibility for case $index", case.allowed, bulkPlan.changes.isNotEmpty())
            if (case.allowed) {
                val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(value,
                    "update", fixes.single(), ProblemHighlightType.WARNING, false)
                fixes.single().applyFix(project, descriptor)
                assertTrue(bulkPlan.apply(project))
                assertEquals(case.replacement, NpmManifest.values(local).getValue(localDeclaration.id).value)
                assertEquals("Local and bulk edits for case $index", local.text, bulk.text)
            } else {
                assertTrue(bulkPlan.skipped.isNotEmpty())
                assertEquals(case.manifest, local.text)
                assertEquals(case.manifest, bulk.text)
            }
        }
    }

    fun testBlockedMemberSuppressesWorkspaceActionWithoutBlockingSafeLocalOrIndependentBulkUpdates() {
        val dollar = '$'
        val root = myFixture.addFileToProject("member-block/package.json", """{
            "workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3","beta":"~1.2.3"},
            "overrides":{"alpha":"${dollar}alpha"}
        }""")
        val member = myFixture.addFileToProject("member-block/packages/app/package.json",
            """{"dependencies":{"alpha":"1.2.3","beta":"1.2.3"}}""")
        val rootSnapshot = adapter.snapshot(project, root.virtualFile)!!
        val memberSnapshot = adapter.snapshot(project, member.virtualFile)!!
        fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.map {
            UpdateCandidate(it, "1.2.9", NpmSelector.parse(it.artifact.name, it.selector)!!.replace("1.2.9"))
        })
        val candidate = report(rootSnapshot).candidates.single { it.declaration.artifact.name == "alpha" }
        val declarations = adapter.workspaceDeclarations(project, rootSnapshot)
        assertNull(adapter.workspaceFix(project, rootSnapshot, candidate, declarations))
        val localFixes = adapter.quickFixes(project, rootSnapshot, candidate,
            NpmManifest.values(root).getValue(candidate.declaration.id), declarations)
        assertEquals(1, localFixes.size)
        val bulkPlan = plan(mapOf(rootSnapshot to report(rootSnapshot), memberSnapshot to report(memberSnapshot)))
        assertEquals(3, bulkPlan.changes.size)
        assertEquals(1, bulkPlan.skipped.size)
        assertTrue(bulkPlan.apply(project))
        assertEquals("1.2.3", NpmManifest.values(member).entries.single { it.key.location == "dependencies/alpha" }.value.value)
        assertEquals("1.2.9", NpmManifest.values(member).entries.single { it.key.location == "dependencies/beta" }.value.value)
        assertEquals("^1.2.9", NpmManifest.values(root).getValue(candidate.declaration.id).value)
    }

    fun testPreparationRejectsDifferentPhysicalDeclarationEvenWithIdenticalSelector() {
        val file = myFixture.addFileToProject("wrong-target/package.json",
            """{"dependencies":{"alpha":"1.2.3","beta":"1.2.3"}}""")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val declaration = snapshot.declarations.single { it.artifact.name == "alpha" }
        val wrongValue = NpmManifest.values(file).entries.single { it.key.location == "dependencies/beta" }.value
        try {
            adapter.quickFixes(project, snapshot, UpdateCandidate(declaration, "1.2.9"), wrongValue, emptyList())
            fail("Different physical declarations must not authorize an edit")
        } catch (expected: IllegalStateException) {
            assertEquals("npm declaration no longer exists", expected.message)
        }
        assertEquals("1.2.3", wrongValue.value)
    }
}
