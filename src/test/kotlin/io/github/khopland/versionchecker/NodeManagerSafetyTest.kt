package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.nio.file.Files
import java.util.concurrent.Callable

class NodeManagerSafetyTest : BasePlatformTestCase() {
    private fun <T> background(action: suspend () -> T): T =
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { action() }
        }), 30_000)

    fun testManagerDeclarationsMustAgreeAcrossWorkspaceAndDevEnginesArray() {
        for (manager in NodePackageManager.entries) {
            val root = myFixture.addFileToProject("${manager.id}/package.json", """{
                "packageManager":"${manager.id}@1.0.0","workspaces":["packages/*"],
                "devEngines":{"packageManager":[{"name":"${manager.id}"},{"name":"${manager.id}"}]}
            }""")
            if (manager == NodePackageManager.PNPM)
                myFixture.addFileToProject("${manager.id}/pnpm-workspace.yaml", "packages: ['packages/*']")
            val child = myFixture.addFileToProject("${manager.id}/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            assertEquals(manager, NpmWorkspaces.manager(project, child.virtualFile))
            val document = FileDocumentManager.getInstance().getDocument(root.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText("""{"packageManager":"${manager.id}@1.0.0","workspaces":["packages/*"],
                    "devEngines":{"packageManager":[{"name":"${manager.id}"},{"name":"unknown"}]}}""")
            }
            assertNull(NpmWorkspaces.manager(project, root.virtualFile))
        }
    }

    fun testUnsavedPnpmMembershipChangesRetirePreparedEditsAndCachedOwners() {
        val root = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@12.15.0"}""")
        val yaml = myFixture.addFileToProject("pnpm/pnpm-workspace.yaml", "packages: ['packages/*']")
        val member = myFixture.addFileToProject("pnpm/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val adapter = PnpmBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        assertEquals(root.virtualFile.parent, NpmWorkspaces.resolve(project, member.virtualFile).root)
        val declaration = snapshot.declarations.single()
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to UpdateReport(listOf(
            UpdateCandidate(declaration, "1.2.9", "^1.2.9"))))) }
        val document = FileDocumentManager.getInstance().getDocument(yaml.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("packages: ['different/*']") }
        assertNull(adapter.snapshot(project, member.virtualFile))
        assertFalse(adapter.isCurrent(project, snapshot))
        assertFalse(plan.apply(project))
        assertEquals("^1.2.3", NpmManifest.values(member).values.single().value)
    }

    fun testYarnRegistryProtocolChecksAndQuickFixPreserveItsRange() {
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        val file = myFixture.addFileToProject("yarn/package.json", """{
            "packageManager":"yarn@4.18.1","dependencies":{"alpha":"npm:^1.2.3"}
        }""")
        val adapter = YarnBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val report = background {
            checkNpmVersions(snapshot.declarations, UpdateMode.PATCH, emptyMap(), Semaphore(1),
                metadata = { NpmPackageMetadata(listOf("1.2.3", "1.2.9", "2.0.0"), emptyMap()) },
                deprecated = { _, _ -> error("Combined metadata already supplies deprecations") },
                allowRegistryProtocol = true)
        }
        val candidate = report.candidates.single()
        assertEquals("npm:^1.2.9", candidate.replacementSelector)
        val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report)) }
        assertEquals("yarn", plan.changes.single().buildSystemId)
        assertEquals(NodePackageManager.YARN, plan.changes.single().npmTarget!!.manager)
        val value = NpmManifest.values(file).values.single()
        val fix = adapter.quickFixes(project, snapshot, candidate, value).single()
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(value, "update", fix,
            ProblemHighlightType.WARNING, false)
        fix.applyFix(project, descriptor)
        assertEquals("npm:^1.2.9", NpmManifest.values(file).values.single().value)
    }

    fun testManagerOverridesCannotAuthorizeLocalOrBulkDependencyEdits() {
        data class Case(val manager: NodePackageManager, val extra: String, val yaml: String? = null)
        for ((index, case) in listOf(
            Case(NodePackageManager.YARN, ",\"resolutions\":{\"alpha\":\"1.2.3\"}"),
            Case(NodePackageManager.BUN, ",\"overrides\":{\"alpha\":\"1.2.3\"}"),
            Case(NodePackageManager.PNPM, ",\"pnpm\":{\"overrides\":{\"alpha\":\"1.2.3\"}}"),
            Case(NodePackageManager.PNPM, "", "packages: []\noverrides:\n  alpha: 1.2.3")
        ).withIndex()) {
            val file = myFixture.addFileToProject("blocked-$index/package.json", """{
                "packageManager":"${case.manager.id}@1.0.0","dependencies":{"alpha":"1.2.3"}${case.extra}
            }""")
            case.yaml?.let { myFixture.addFileToProject("blocked-$index/pnpm-workspace.yaml", it) }
            val adapter = NpmBuildSystemAdapter(case.manager)
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            val candidate = UpdateCandidate(snapshot.declarations.single(), "1.2.9")
            val value = NpmManifest.values(file).values.single()
            assertTrue(snapshot.skippedDeclarations.values.single().contains("manual review"))
            assertTrue(adapter.quickFixes(project, snapshot, candidate, value).isEmpty())
            val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to UpdateReport(listOf(candidate)))) }
            assertTrue(plan.changes.isEmpty())
            assertEquals("1.2.3", value.value)
        }
    }

    fun testNodeManagerIgnoreAndSharedPolicyRulesUseTheSamePackageIdentity() {
        val artifact = ArtifactId("npm", "@scope/alpha")
        val declaration = VersionDeclaration(DeclarationId("package.json", "dependencies/@scope/alpha"), artifact, "1.2.3", "1.2.3")
        val candidate = UpdateCandidate(declaration, "1.2.9")
        for (manager in NodePackageManager.entries) {
            val ignored = IgnoredVersion(manager.id, artifact, candidate.version)
            assertEquals(setOf(ignored), ignoredVersions(ignored.toString()))
            assertFalse(VersionPresentationPolicy(manager.id, ignored.toString()).includes(candidate))
            val policy = ProjectPolicySnapshot("""{"schemaVersion":1,"rules":[{
                "ecosystem":"${manager.id}","package":"@scope/alpha","allowedUpdates":[]
            }]}""", "", "")
            assertNull(policy.shared.diagnostic)
            assertFalse(VersionPresentationPolicy(manager.id, "", policy).includes(candidate))
        }
    }

    fun testPnpmBootstrapSettingsAreRejectedWithoutExecutingTheManager() {
        val directory = Files.createTempDirectory("version-checker-pnpm-config-")
        try {
            for (setting in listOf("configDependencies:\n  alpha: 1.0.0", "useNodeVersion: 22.23.1")) {
                Files.writeString(directory.resolve("pnpm-workspace.yaml"), setting)
                try {
                    NpmRegistry.validatePnpmConfiguration(directory, mapOf("HOME" to directory.toString()))
                    fail("Bootstrap configuration must be rejected")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("install"))
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    fun testMalformedPnpmYamlNeverCreatesIndependentNpmProjects() {
        for ((index, yaml) in listOf("packages: [broken", "packages: ['packages/*']\npackages: []", "packages: 3").withIndex()) {
            myFixture.addFileToProject("invalid-$index/package.json", """{"packageManager":"pnpm@12.15.0"}""")
            myFixture.addFileToProject("invalid-$index/pnpm-workspace.yaml", yaml)
            val member = myFixture.addFileToProject("invalid-$index/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            assertTrue(BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, member.virtualFile.path)).isEmpty())
        }
    }
}
