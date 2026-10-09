package io.github.khopland.versionchecker

import com.intellij.json.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.npm.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable

class NpmAdapterTest : BasePlatformTestCase() {
    private val adapter = NpmBuildSystemAdapter()
    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations.filter { it.baseline.isNotEmpty() }.map {
        val selector = NpmSelector.parse(it.artifact.name, it.selector)!!
        UpdateCandidate(it, "1.2.9", selector.replace("1.2.9"), VersionChangeKind.PATCH)
    })
    private fun plan(reports: Map<BuildSnapshot, UpdateReport>): BulkUpdatePlan {
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { adapter.prepareUpdates(project, reports) }
        })
        return PlatformTestUtil.waitForFuture(future, 30_000)
    }
    fun testResolutionContextReusesSelectorEditsButKeepsPreviewOwnershipStrict() {
        val root = myFixture.addFileToProject("metadata/package.json", """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3"}}""")
        val child = myFixture.addFileToProject("metadata/packages/app/package.json", """{"dependencies":{"alpha":"~1.2.3"}}""")
        val before = NpmBuildInputs.resolutionContext(project, root.virtualFile.parent)
        val snapshot = adapter.snapshot(project, child.virtualFile)!!
        val document = FileDocumentManager.getInstance().getDocument(root.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("^1.2.3", "^1.2.8")) }
        assertEquals(before, NpmBuildInputs.resolutionContext(project, root.virtualFile.parent))
        assertFalse(adapter.isCurrent(project, snapshot))
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("packages/*", "packages/**")) }
        assertFalse(before == NpmBuildInputs.resolutionContext(project, root.virtualFile.parent))
    }
    fun testResolutionContextIsolatesRootsAndDetectsUnsavedRegistryAndRuntimeConfiguration() {
        val first = myFixture.addFileToProject("contexts/first/package.json", "{}")
        val second = myFixture.addFileToProject("contexts/second/package.json", "{}")
        val config = myFixture.addFileToProject("contexts/first/.npmrc", "registry=https://registry.example/\n//registry.example/:_authToken=private-fixture-token\n")
        val runtime = myFixture.addFileToProject("contexts/first/.nvmrc", "22\n")
        val before = NpmBuildInputs.resolutionContext(project, first.virtualFile.parent)
        assertFalse(before == NpmBuildInputs.resolutionContext(project, second.virtualFile.parent))
        assertFalse(before.toString().contains("private-fixture-token"))
        val configDocument = FileDocumentManager.getInstance().getDocument(config.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { configDocument.setText("registry=https://other.example/\n") }
        val changedConfig = NpmBuildInputs.resolutionContext(project, first.virtualFile.parent)
        assertFalse(before == changedConfig)
        val runtimeDocument = FileDocumentManager.getInstance().getDocument(runtime.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { runtimeDocument.setText("24\n") }
        assertFalse(changedConfig == NpmBuildInputs.resolutionContext(project, first.virtualFile.parent))
    }
    fun testAllDependencySectionsAndAliasesUpdateOnlyManifestStrings() {
        val file = myFixture.addFileToProject("npm/package.json", """{
          "name":"demo", "version":"1.2.3", "scripts":{"test":"echo 1.2.3"},
          "dependencies":{"alpha":"^1.2.3"}, "devDependencies":{"beta":"~1.2.3"},
          "optionalDependencies":{"alias":"npm:@scope/pkg@^1.2.3"}, "peerDependencies":{"peer":"1.2.3"},
          "overrides":{"alpha":"1.2.3"}
        }""")
        val lock = myFixture.addFileToProject("npm/package-lock.json", "unchanged lockfile")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertEquals(4, snapshot.declarations.size)
        assertTrue(snapshot.declarations.single { it.id.location.startsWith("peerDependencies/") }.baseline.isEmpty())
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        assertEquals(3, prepared.changes.size)
        assertTrue(prepared.followUp.single().contains("npm install"))
        val combined = BulkUpdatePlan.combine(listOf(BulkUpdatePlan(emptyList(), emptyList()), prepared, prepared))
        assertEquals(prepared.followUp, combined.followUp)
        assertTrue(prepared.skipped.single().contains("peer compatibility"))
        assertTrue(prepared.apply(project))
        val root = (file as JsonFile).topLevelValue as JsonObject
        assertEquals("1.2.3", (root.findProperty("version")!!.value as JsonStringLiteral).value)
        assertEquals("npm:@scope/pkg@^1.2.9", ((root.findProperty("optionalDependencies")!!.value as JsonObject).propertyList.single().value as JsonStringLiteral).value)
        assertEquals("1.2.3", ((root.findProperty("overrides")!!.value as JsonObject).propertyList.single().value as JsonStringLiteral).value)
        assertEquals("unchanged lockfile", lock.text)
        assertEquals(setOf("^1.2.9", "~1.2.9", "npm:@scope/pkg@^1.2.9", "1.2.3"), NpmManifest.values(file).values.map { it.value }.toSet())
    }
    fun testWorkspaceReferencesAndComplexRangesRequireReview() {
        myFixture.addFileToProject("workspace/package.json", """{"private":true,"workspaces":["packages/*"]}""")
        myFixture.addFileToProject("workspace/packages/library/package.json", """{"name":"@local/library","version":"1.0.0"}""")
        val file = myFixture.addFileToProject("workspace/packages/app/package.json", """{"name":"app","dependencies":{
          "@local/library":"^1.2.3", "complex":">=1 <2", "local":"file:../library", "protocol":"workspace:*"
        }}""")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        assertTrue(snapshot.declarations.all { it.baseline.isEmpty() })
        val prepared = plan(mapOf(snapshot to UpdateReport()))
        assertTrue(prepared.changes.isEmpty())
        assertTrue(prepared.followUp.isEmpty())
        assertEquals(4, prepared.skipped.size)
        assertTrue(prepared.skipped.any { "@local/library" in it && "local workspace" in it })
    }
    fun testUnsavedChangesToWorkspaceSiblingInvalidateTheEntirePreview() {
        myFixture.addFileToProject("stale/package.json", """{"workspaces":["packages/*"]}""")
        val file = myFixture.addFileToProject("stale/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val other = myFixture.addFileToProject("stale/packages/other/package.json", "{}")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        val document = FileDocumentManager.getInstance().getDocument(other.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("{\"name\":\"changed\"}") }
        assertFalse(prepared.apply(project))
        assertEquals("^1.2.3", NpmManifest.values(file).values.single().value)
    }
    fun testIndependentProjectEditsDoNotInvalidatePreview() {
        val file = myFixture.addFileToProject("independent/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val other = myFixture.addFileToProject("independent/other/package.json", "{}")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        val document = FileDocumentManager.getInstance().getDocument(other.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("{\"name\":\"changed\"}") }
        assertTrue(prepared.apply(project))
        assertEquals("^1.2.9", NpmManifest.values(file).values.single().value)
    }
    fun testSavedChangesAndWorkspaceRenamesInvalidateCachedSnapshots() {
        myFixture.addFileToProject("cached/package.json", """{"workspaces":["packages/*"]}""")
        val member = myFixture.addFileToProject("cached/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val library = myFixture.addFileToProject("cached/packages/library/package.json", """{"name":"beta"}""")
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        val name = (NpmManifest.root(library)!!.findProperty("name")!!.value as JsonStringLiteral)
        WriteCommandAction.runWriteCommandAction(project) { NpmVersionEdit(name, "alpha", "name").apply() }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(adapter.isCurrent(project, snapshot))
        assertTrue(adapter.snapshot(project, member.virtualFile)!!.declarations.single().baseline.isEmpty())
    }
    fun testDeletingWorkspaceMemberInvalidatesSnapshot() {
        myFixture.addFileToProject("deleted/package.json", """{"workspaces":["packages/*"]}""")
        val member = myFixture.addFileToProject("deleted/packages/app/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val library = myFixture.addFileToProject("deleted/packages/library/package.json", "{}")
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { library.virtualFile.delete(this) }
        assertFalse(adapter.isCurrent(project, snapshot))
    }
    fun testNpmrcChangesInvalidatePreviewWithoutAnyPartialEdits() {
        val file = myFixture.addFileToProject("config/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val config = myFixture.addFileToProject("config/.npmrc", "registry=https://registry.npmjs.org/\n")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        val document = FileDocumentManager.getInstance().getDocument(config.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("registry=https://other.example/\n") }
        assertFalse(prepared.apply(project))
    }
    fun testCurrentFileNeverFallsBackAndGeneratedManifestsAreIgnored() {
        val file = myFixture.addFileToProject("selection/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        val ignored = myFixture.addFileToProject("selection/node_modules/b/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        assertTrue(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, file.virtualFile.path)))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, ignored.virtualFile.path)))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE)))
        assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, "unknown")))
        assertNull(adapter.snapshot(project, ignored.virtualFile))
        for (directory in listOf("dist", "build", "vendor", "coverage", ".next", ".yarn")) {
            val generated = myFixture.addFileToProject("selection/$directory/package.json", "{}")
            assertNull(adapter.snapshot(project, generated.virtualFile))
        }
    }
    fun testOtherPackageManagersAndMalformedOrDuplicateDeclarationsAreNotEdited() {
        val pnpm = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@10.0.0","dependencies":{"a":"1.2.3"}}""")
        assertNull(adapter.snapshot(project, pnpm.virtualFile))
        val duplicate = myFixture.addFileToProject("duplicate/package.json", """{"dependencies":{"a":"1.2.3","a":"2.0.0"}}""")
        assertTrue(adapter.snapshot(project, duplicate.virtualFile)!!.declarations.isEmpty())
        val malformed = myFixture.addFileToProject("invalid/package.json", "{ invalid }")
        assertNull(adapter.snapshot(project, malformed.virtualFile))
    }
    fun testPackageManagerMarkersAndWorkspaceOwnership() {
        for (marker in listOf("pnpm-lock.yaml", "pnpm-workspace.yaml", "yarn.lock", "bun.lock", "bun.lockb")) {
            val file = myFixture.addFileToProject("managers/$marker/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
            myFixture.addFileToProject("managers/$marker/$marker", "")
            assertNull(marker, adapter.snapshot(project, file.virtualFile))
        }
        val root = myFixture.addFileToProject("owners/package.json", """{"packageManager":"pnpm@10.0.0","workspaces":["packages/*"]}""")
        val member = myFixture.addFileToProject("owners/packages/member/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        assertNull(adapter.snapshot(project, member.virtualFile))
        val independent = myFixture.addFileToProject("owners/independent/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        assertEquals(independent.virtualFile.parent.path, adapter.snapshot(project, independent.virtualFile)!!.context.root)
        val explicitNpm = myFixture.addFileToProject("explicit/package.json", """{"packageManager":"npm@11.0.0","dependencies":{"alpha":"1.2.3"}}""")
        myFixture.addFileToProject("explicit/yarn.lock", "old lockfile")
        assertNotNull(adapter.snapshot(project, explicitNpm.virtualFile))
        val engine = myFixture.addFileToProject("engine/package.json", """{"devEngines":{"packageManager":{"name":"bun"}},"dependencies":{"alpha":"1.2.3"}}""")
        assertNull(adapter.snapshot(project, engine.virtualFile))
        assertNull(adapter.snapshot(project, root.virtualFile))
    }
    fun testDevEnginesNpmObjectAndArraysAreSupported() {
        for ((index, manager) in listOf(
            """{"name":"npm"}""",
            """[{"name":"npm"}]""",
            """[{"name":"npm","version":"^10"},{"name":"npm","version":"^11"}]"""
        ).withIndex()) {
            val file = myFixture.addFileToProject("engine-array/$index/package.json", """{
                "devEngines":{"packageManager":$manager},"dependencies":{"alpha":"1.2.3"}
            }""")
            myFixture.addFileToProject("engine-array/$index/yarn.lock", "leftover")
            assertNotNull(manager, adapter.snapshot(project, file.virtualFile))
        }
    }
    fun testDevEnginesNpmArrayAtWorkspaceRootSupportsMembers() {
        val root = myFixture.addFileToProject("engine-workspace/package.json", """{
            "devEngines":{"packageManager":[{"name":"npm"}]},"workspaces":["packages/*"]
        }""")
        val member = myFixture.addFileToProject("engine-workspace/packages/app/package.json",
            """{"dependencies":{"alpha":"1.2.3"}}""")
        assertNotNull(adapter.snapshot(project, root.virtualFile))
        assertEquals(root.virtualFile.parent.path, adapter.snapshot(project, member.virtualFile)!!.context.root)
    }
    fun testMixedAndMalformedDevEngineManagersAreExcluded() {
        for ((index, manager) in listOf(
            """[{"name":"npm"},{"name":"pnpm"}]""", """[{"name":"npm"},{}]""",
            """[{"name":"npm"},null]""", """[{"name":42}]""", "[]", "null", "42", "\"npm\""
        ).withIndex()) {
            val file = myFixture.addFileToProject("engine-invalid/$index/package.json", """{
                "packageManager":"npm@11.0.0","devEngines":{"packageManager":$manager},
                "dependencies":{"alpha":"1.2.3"}
            }""")
            assertNull(manager, adapter.snapshot(project, file.virtualFile))
        }
    }
    fun testOnlyWorkspaceMembersShareRegistryContextAndLocalNames() {
        val root = myFixture.addFileToProject("members/package.json", """{"workspaces":["./packages/*","!packages/excluded"]}""")
        myFixture.addFileToProject("members/packages/library/package.json", """{"name":"@local/library","version":"1.0.0"}""")
        val member = myFixture.addFileToProject("members/packages/app/package.json", """{"dependencies":{"@local/library":"^1.2.3"}}""")
        val excluded = myFixture.addFileToProject("members/packages/excluded/package.json", """{"dependencies":{"@local/library":"^1.2.3"}}""")
        val snapshot = adapter.snapshot(project, member.virtualFile)!!
        assertEquals(root.virtualFile.parent.path, snapshot.context.root)
        assertTrue(snapshot.declarations.single().baseline.isEmpty())
        val independent = adapter.snapshot(project, excluded.virtualFile)!!
        assertEquals(excluded.virtualFile.parent.path, independent.context.root)
        assertEquals("1.2.3", independent.declarations.single().baseline)
    }
    fun testAddingAnotherPackageManagerInvalidatesPreparedUpdates() {
        val file = myFixture.addFileToProject("manager-change/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        myFixture.addFileToProject("manager-change/pnpm-lock.yaml", "lockfileVersion: '9.0'")
        assertFalse(prepared.apply(project))
        assertEquals("^1.2.3", NpmManifest.values(file).values.single().value)
    }
    fun testGlobstarMatchesZeroAndMultipleDirectoriesAndPreservesLocalNames() {
        val root = myFixture.addFileToProject("glob/package.json", """{"workspaces":["./packages/**/{app,library}/"]}""")
        myFixture.addFileToProject("glob/packages/library/package.json", """{"name":"@local/library","version":"1.0.0"}""")
        val members = listOf("packages/app", "packages/nested/app", "packages/nested/deeper/app").map { path ->
            myFixture.addFileToProject("glob/$path/package.json", """{"dependencies":{"@local/library":"^1.2.3"}}""")
        }
        for (member in members) {
            val snapshot = adapter.snapshot(project, member.virtualFile)!!
            assertEquals(root.virtualFile.parent.path, snapshot.context.root)
            assertTrue(snapshot.declarations.single().baseline.isEmpty())
        }
        val independent = myFixture.addFileToProject("glob/packages/other/package.json", "{}")
        assertEquals(independent.virtualFile.parent.path, adapter.snapshot(project, independent.virtualFile)!!.context.root)
    }
    fun testGlobstarExclusionsAlsoMatchZeroDirectoryLevels() {
        val root = myFixture.addFileToProject("glob-exclusion/package.json", """{"workspaces":["packages/**","!packages/**/excluded"]}""")
        val app = myFixture.addFileToProject("glob-exclusion/packages/app/package.json", "{}")
        assertEquals(root.virtualFile.parent.path, adapter.snapshot(project, app.virtualFile)!!.context.root)
        for (path in listOf("packages/excluded", "packages/nested/excluded")) {
            val excluded = myFixture.addFileToProject("glob-exclusion/$path/package.json", "{}")
            assertEquals(excluded.virtualFile.parent.path, adapter.snapshot(project, excluded.virtualFile)!!.context.root)
        }
    }
    fun testQuickFixPreservesRangeAndRejectsStaleSelectors() {
        val file = myFixture.addFileToProject("fix/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val value = NpmManifest.values(file).values.single()
        val fix = UpdateNpmVersionFix(value, "^1.2.9")
        val descriptor = com.intellij.codeInspection.InspectionManager.getInstance(project)
            .createProblemDescriptor(value, "update", fix, com.intellij.codeInspection.ProblemHighlightType.WARNING, false)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        assertEquals("^1.2.9", NpmManifest.values(file).values.single().value)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        assertEquals("^1.2.9", NpmManifest.values(file).values.single().value)
    }
}
