package io.github.khopland.versionchecker

import com.intellij.json.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.util.PsiTreeUtil
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
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        assertEquals(4, prepared.changes.size)
        assertTrue(prepared.apply(project))
        val root = (file as JsonFile).topLevelValue as JsonObject
        assertEquals("1.2.3", (root.findProperty("version")!!.value as JsonStringLiteral).value)
        assertEquals("npm:@scope/pkg@^1.2.9", ((root.findProperty("optionalDependencies")!!.value as JsonObject).propertyList.single().value as JsonStringLiteral).value)
        assertEquals("1.2.3", ((root.findProperty("overrides")!!.value as JsonObject).propertyList.single().value as JsonStringLiteral).value)
        assertEquals("unchanged lockfile", lock.text)
        assertEquals(setOf("^1.2.9", "~1.2.9", "npm:@scope/pkg@^1.2.9", "1.2.9"), NpmManifest.values(file).values.map { it.value }.toSet())
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
        assertEquals(4, prepared.skipped.size)
        assertTrue(prepared.skipped.any { "@local/library" in it && "local workspace" in it })
    }
    fun testUnsavedChangesToAnotherManifestInvalidateTheEntirePreview() {
        val file = myFixture.addFileToProject("stale/package.json", """{"dependencies":{"alpha":"^1.2.3"}}""")
        val other = myFixture.addFileToProject("stale/other/package.json", "{}")
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val prepared = plan(mapOf(snapshot to report(snapshot)))
        val document = FileDocumentManager.getInstance().getDocument(other.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("{\"name\":\"changed\"}") }
        assertFalse(prepared.apply(project))
        assertEquals("^1.2.3", NpmManifest.values(file).values.single().value)
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
    }
    fun testOtherPackageManagersAndMalformedOrDuplicateDeclarationsAreNotEdited() {
        val pnpm = myFixture.addFileToProject("pnpm/package.json", """{"packageManager":"pnpm@10.0.0","dependencies":{"a":"1.2.3"}}""")
        assertNull(adapter.snapshot(project, pnpm.virtualFile))
        val duplicate = myFixture.addFileToProject("duplicate/package.json", """{"dependencies":{"a":"1.2.3","a":"2.0.0"}}""")
        assertTrue(adapter.snapshot(project, duplicate.virtualFile)!!.declarations.isEmpty())
        val malformed = myFixture.addFileToProject("invalid/package.json", "{ invalid }")
        assertNull(adapter.snapshot(project, malformed.virtualFile))
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
