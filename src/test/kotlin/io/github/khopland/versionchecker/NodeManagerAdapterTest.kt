package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*

class NodeManagerAdapterTest : BasePlatformTestCase() {
    private val selection = BuildSelection(UpdateScope.WHOLE_PROJECT)
    private fun adapter(manager: NodePackageManager) = BuildSystemAdapter.find(manager.id) as NpmBuildSystemAdapter
    fun testEachManagerOwnsOnlyItsExplicitManifests() {
        for (manager in NodePackageManager.entries) {
            val file = myFixture.addFileToProject("${manager.id}/package.json", """{
                "packageManager":"${manager.id}@1.0.0", "dependencies":{"alpha":"^1.2.3"}
            }""")
            val current = BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)
            assertEquals(listOf(manager.id), BuildSystemAdapter.matching(project, current).map { it.id })
            val snapshot = adapter(manager).snapshot(project, file.virtualFile)!!
            assertEquals(manager.id, snapshot.context.adapterId)
            assertEquals("1.2.3", snapshot.declarations.single().baseline)
        }
        assertEquals(NodePackageManager.entries.map { it.id }.toSet(), BuildSystemAdapter.matching(project, selection).map { it.id }.toSet())
    }
    fun testManagerMarkersAndAmbiguities() {
        for (manager in NodePackageManager.entries) for (marker in manager.markers) {
            val file = myFixture.addFileToProject("markers/$marker/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            myFixture.addFileToProject("markers/$marker/$marker", if (marker == "pnpm-workspace.yaml") "packages: []" else "")
            assertNotNull(marker, adapter(manager).snapshot(project, file.virtualFile))
            assertEquals(1, BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)).size)
        }
        val conflict = myFixture.addFileToProject("conflict/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        myFixture.addFileToProject("conflict/yarn.lock", "")
        myFixture.addFileToProject("conflict/bun.lock", "")
        assertTrue(BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, conflict.virtualFile.path)).isEmpty())
        val explicit = myFixture.addFileToProject("explicit/package.json", """{"packageManager":"bun@1.4.2","dependencies":{"alpha":"1.2.3"}}""")
        myFixture.addFileToProject("explicit/yarn.lock", "old lockfile")
        assertNotNull(adapter(NodePackageManager.BUN).snapshot(project, explicit.virtualFile))
    }
    fun testPnpmYamlMembershipLocalNamesAndExcludedProjects() {
        val root = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@12.0.0"}""")
        myFixture.addFileToProject("pnpm/pnpm-workspace.yaml", """
            packages:
              - './packages/*'
              - '!packages/excluded'
            catalog:
              alpha: ^1.2.3
        """.trimIndent())
        myFixture.addFileToProject("pnpm/packages/lib/package.json", """{"name":"@local/lib","version":"1.0.0"}""")
        val member = myFixture.addFileToProject("pnpm/packages/app/package.json", """{"dependencies":{"@local/lib":"^1.0.0","alpha":"^1.2.3"}}""")
        val excluded = myFixture.addFileToProject("pnpm/packages/excluded/package.json", """{"dependencies":{"@local/lib":"^1.0.0"}}""")
        val pnpm = adapter(NodePackageManager.PNPM)
        val snapshot = pnpm.snapshot(project, member.virtualFile)!!
        assertEquals(root.virtualFile.parent.path, snapshot.context.root)
        assertEquals("", snapshot.declarations.single { it.artifact.name == "@local/lib" }.baseline)
        assertEquals("1.2.3", snapshot.declarations.single { it.artifact.name == "alpha" }.baseline)
        assertNull(pnpm.snapshot(project, excluded.virtualFile))
        assertEquals("1.0.0", adapter(NodePackageManager.NPM).snapshot(project, excluded.virtualFile)!!.declarations.single().baseline)
    }
    fun testPnpmUsesYamlInsteadOfPackageJsonWorkspacePatterns() {
        myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@12.0.0","workspaces":["ignored/*"]}""")
        myFixture.addFileToProject("pnpm/pnpm-workspace.yaml", "packages: ['packages/*']")
        val member = myFixture.addFileToProject("pnpm/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        val other = myFixture.addFileToProject("pnpm/ignored/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        assertNotNull(adapter(NodePackageManager.PNPM).snapshot(project, member.virtualFile))
        assertNull(adapter(NodePackageManager.PNPM).snapshot(project, other.virtualFile))
    }
    fun testEmptyPnpmWorkspaceStillIncludesRoot() {
        val root = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@12.0.0","dependencies":{"alpha":"1.2.3"}}""")
        myFixture.addFileToProject("pnpm/pnpm-workspace.yaml", "")
        assertNotNull(adapter(NodePackageManager.PNPM).snapshot(project, root.virtualFile))
    }
    fun testInvalidOrDuplicateYamlCannotCreateAccidentalNpmMembers() {
        for ((index, yaml) in listOf("packages: [broken", "packages: ['packages/*']\npackages: ['different/*']", "packages: wrong").withIndex()) {
            myFixture.addFileToProject("invalid$index/package.json", """{"packageManager":"pnpm@12.0.0"}""")
            myFixture.addFileToProject("invalid$index/pnpm-workspace.yaml", yaml)
            val member = myFixture.addFileToProject("invalid$index/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            assertTrue(BuildSystemAdapter.matching(project, BuildSelection(UpdateScope.CURRENT_FILE, member.virtualFile.path)).isEmpty())
        }
    }
    fun testYarnAndBunSharePackageJsonWorkspacesAndQuickFixes() {
        for (manager in listOf(NodePackageManager.YARN, NodePackageManager.BUN)) {
            val root = myFixture.addFileToProject("${manager.id}/package.json", """{"packageManager":"${manager.id}@1.0.0",
                "workspaces":{"packages":["packages/*"]},"dependencies":{"alpha":"^1.2.3"}}""")
            val child = myFixture.addFileToProject("${manager.id}/packages/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            val adapter = adapter(manager)
            val snapshot = adapter.snapshot(project, child.virtualFile)!!
            assertEquals(root.virtualFile.parent.path, snapshot.context.root)
            val candidate = UpdateCandidate(snapshot.declarations.single(), "1.2.9", "1.2.9", VersionChangeKind.PATCH)
            assertEquals(2, adapter.quickFixes(project, snapshot, candidate, NpmManifest.values(child).values.single()).size)
        }
    }
    fun testManagerSpecificUnsavedConfigInvalidatesSnapshot() {
        for ((manager, name, text) in listOf(Triple(NodePackageManager.PNPM,"pnpm-workspace.yaml","packages: []"),
            Triple(NodePackageManager.YARN,".yarnrc.yml","npmRegistryServer: https://registry.npmjs.org"),
            Triple(NodePackageManager.BUN,"bunfig.toml","[install]\nregistry = 'https://registry.npmjs.org'"))) {
            val file = myFixture.addFileToProject("${manager.id}/package.json", """{"packageManager":"${manager.id}@1.0.0","dependencies":{"alpha":"1.2.3"}}""")
            val config = myFixture.addFileToProject("${manager.id}/$name", text)
            val adapter = adapter(manager)
            val snapshot = adapter.snapshot(project, file.virtualFile)!!
            val document = FileDocumentManager.getInstance().getDocument(config.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text + "\n# config changed") }
            assertFalse(adapter.isCurrent(project, snapshot))
        }
    }
    fun testCatalogReferencesAndYarnProtocolsRequireReview() {
        val file = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@12.0.0",
            "dependencies":{"alpha":"catalog:","beta":"catalog:testing","local":"workspace:*","portal":"portal:../local"}}""")
        val declarations = adapter(NodePackageManager.PNPM).snapshot(project, file.virtualFile)!!.declarations
        assertTrue(declarations.all { it.baseline.isEmpty() })
        assertTrue(declarations.filter { it.selector.startsWith("catalog:") }.all { "catalog declaration" in NpmManifest.reviewReason(it).orEmpty() })
    }
    fun testYarnRegistryProtocolPreservesItsPrefixAndDoesNotEnableItForNpm() {
        val file = myFixture.addFileToProject("yarn/package.json", """{"packageManager":"yarn@4.18.1","dependencies":{"alpha":"npm:^1.2.3"}}""")
        assertEquals("1.2.3", adapter(NodePackageManager.YARN).snapshot(project, file.virtualFile)!!.declarations.single().baseline)
        assertEquals("npm:^1.2.9", NpmSelector.parse("alpha", "npm:^1.2.3", true)!!.replace("1.2.9"))
        assertNull(NpmSelector.parse("alpha", "npm:^1.2.3"))
    }
    fun testManagerNativeReadOnlyArgumentsAndYarnJsonFormats() {
        assertEquals(listOf("view","alpha","versions","--json"), NpmRegistry.arguments(NodePackageManager.PNPM,listOf("alpha","versions"),false))
        assertEquals(listOf("npm","info","alpha","--fields","versions","--json"), NpmRegistry.arguments(NodePackageManager.YARN,listOf("alpha","versions"),true))
        assertEquals(listOf("info","alpha","versions","--json"), NpmRegistry.arguments(NodePackageManager.BUN,listOf("alpha","versions"),false))
        assertEquals(listOf("1.2.3","1.2.9"), NpmRegistry.parseLegacyMetadata("""{"type":"inspect","data":["1.2.3","1.2.9"]}
            {"type":"finished","data":1}""").versions)
        assertEquals(listOf("1.2.9"), NpmRegistry.parseLegacyMetadata("""{"name":"alpha","versions":["1.2.9"]}""").versions)
    }
}
