package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.CapturedEditInputs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class SharedProjectPolicyIntegrationTest : BasePlatformTestCase() {
    private val policyPath: Path get() = Path.of(project.basePath!!, PROJECT_POLICY_FILE)
    private val empty = """{"schemaVersion":1,"rules":[]}"""
    private val restricted = """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","allowedUpdates":["patch"]}]}"""

    override fun setUp() {
        super.setUp()
        // Light projects expose a base path without creating its physical directory.
        Files.createDirectories(policyPath.parent)
        VfsRootAccess.allowRootAccess(testRootDisposable, policyPath.parent.toString())
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(policyPath.parent)
    }

    private fun writePolicy(text: String) = WriteCommandAction.runWriteCommandAction(project, Runnable {
        val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
        val file = root.findChild(PROJECT_POLICY_FILE) ?: root.createChildData(this, PROJECT_POLICY_FILE)
        file.setBinaryContent(text.toByteArray())
    })
    private fun capture(expected: ProjectPolicySnapshot): CapturedEditInputs = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runBlocking { prepareProjectPolicyValidation(project, expected) }
        }), 30_000)

    override fun tearDown() {
        try {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                LocalFileSystem.getInstance().findFileByNioFile(policyPath)?.delete(this)
            })
        } finally { super.tearDown() }
    }

    fun testUnsavedPolicyChangesRetireExistingAuthorizationWithoutWritingTrackedPolicy() {
        writePolicy(empty)
        val prepared = projectPolicy(project)
        val file = LocalFileSystem.getInstance().findFileByNioFile(policyPath)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project, Runnable { document.setText(restricted) })
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertEquals(restricted, projectPolicy(project).sharedText)
        assertFalse(prepared.isCurrent(project))
        assertEquals(empty, Files.readString(policyPath))
        assertFalse(capture(prepared).isCurrent())
    }

    fun testSameTimestampExternalChangeAndExternalDeletionRejectPreparedEdits() {
        writePolicy(empty)
        val prepared = projectPolicy(project)
        val timestamp = Files.getLastModifiedTime(policyPath)
        Files.writeString(policyPath, restricted)
        Files.setLastModifiedTime(policyPath, timestamp)
        assertFalse(capture(prepared).isCurrent())
        writePolicy(empty)
        val beforeDelete = projectPolicy(project)
        Files.delete(policyPath)
        assertFalse(capture(beforeDelete).isCurrent())
    }

    fun testExternalCreationOfPreviouslyAbsentPolicyIsNotBypassed() {
        assertNull(projectPolicy(project).sharedText)
        val prepared = projectPolicy(project)
        Files.writeString(policyPath, restricted)
        assertFalse(capture(prepared).isCurrent())
    }

    fun testWorkspaceRulesCannotRemoveSharedRulesAndChangingThemRetiresHeldPlans() {
        writePolicy(restricted)
        val settings = project.service<VersionCheckerSettings>().state
        settings.ignoredVersions = "npm other = 1.1.0"
        val prepared = projectPolicy(project)
        assertEquals(setOf(io.github.khopland.versionchecker.core.VersionChangeKind.PATCH),
            prepared.shared.rules.single().allowedUpdates)
        settings.ignoredVersions = ""
        assertFalse(prepared.isCurrent(project))
        assertEquals(restricted, Files.readString(policyPath))
    }

    fun testRepeatedStatusAndGuardReadsReuseParsedPolicyAndOversizedFilesHaveBoundedDiagnostics() {
        writePolicy(empty)
        val expected = projectPolicy(project)
        val store = project.service<ProjectPolicyStore>()
        val parsed = store.parseCount
        repeat(100) {
            assertSame(expected, projectPolicy(project))
            assertTrue(expected.isCurrent(project))
        }
        assertEquals(parsed, store.parseCount)
        project.service<VersionCheckerSettings>().state.ignoredVersions = "npm alpha = 1.1.0"
        projectPolicy(project)
        assertEquals(parsed, store.parseCount)
        writePolicy(" ".repeat(1_048_577))
        assertTrue(projectPolicy(project).shared.diagnostic!!.contains("at most 1 MiB"))
    }

    fun testExpiryTransitionRetiresHeldAuthorizationWithoutReparsingSource() {
        val expiry = java.time.Instant.now().minusSeconds(1)
        val text = """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","ignoredVersions":[{"version":"1.1.0","until":"$expiry"}]}]}"""
        writePolicy(text)
        val beforeExpiry = projectPolicy(project).copy(observedAt = expiry.minusSeconds(1))
        assertFalse(beforeExpiry.isCurrent(project))
    }

    fun testWorkspaceActionChecksEachMembersActualUpdateLevel() {
        writePolicy("""{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"alpha","allowedUpdates":["patch"]}]}""")
        val root = myFixture.addFileToProject("policy-workspace/package.json",
            """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3"}}""")
        myFixture.addFileToProject("policy-workspace/packages/app/package.json",
            """{"dependencies":{"alpha":"1.0.0"}}""")
        val adapter = io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, root.virtualFile)!!
        val declaration = snapshot.declarations.single()
        val candidate = io.github.khopland.versionchecker.core.UpdateCandidate(declaration, "1.2.9", "^1.2.9",
            io.github.khopland.versionchecker.core.VersionChangeKind.PATCH)
        val declarations = adapter.workspaceDeclarations(project, snapshot)
        assertNull(adapter.workspaceFix(project, snapshot, candidate, declarations))
        val value = io.github.khopland.versionchecker.npm.NpmManifest.values(root).getValue(declaration.id)
        assertEquals(1, adapter.quickFixes(project, snapshot, candidate, value, declarations).size)
        assertTrue(root.text.contains("^1.2.3"))
    }

    fun testSelectedCombinedPlanCannotBypassChangedPolicy() {
        writePolicy(empty)
        val file = myFixture.addFileToProject("policy-selection/build.txt", "1.0")
        val edit = io.github.khopland.versionchecker.gradle.GradleVersionEdit(
            file, com.intellij.openapi.util.TextRange(0, 3), "1.1", "version")
        val original = BulkUpdatePlan(listOf(edit), emptyList()).withProjectPolicy(project, projectPolicy(project))
        val selected = BulkUpdatePlan.combine(listOf(original)).select(setOf(0))
        writePolicy(restricted)
        assertFalse(selected.apply(project))
        assertEquals("1.0", file.text)
    }

    fun testValidPolicyCaptureAcceptsUnchangedInputsButRejectsLaterEditorChanges() {
        writePolicy(empty)
        val expected = projectPolicy(project)
        val captured = capture(expected)
        assertTrue(captured.isCurrent())
        val file = LocalFileSystem.getInstance().findFileByNioFile(policyPath)!!
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            FileDocumentManager.getInstance().getDocument(file)!!.setText(restricted)
        })
        assertFalse(captured.isCurrent())
    }
}
