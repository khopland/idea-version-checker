package io.github.khopland.versionchecker

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.UpdateCandidate
import io.github.khopland.versionchecker.core.VersionChangeKind
import io.github.khopland.versionchecker.core.BuildSnapshot
import io.github.khopland.versionchecker.core.BuildSystemAdapter
import io.github.khopland.versionchecker.core.UpdateReport
import io.github.khopland.versionchecker.npm.*

class NpmWorkspaceVersionFixTest : BasePlatformTestCase() {
    private val adapter = NpmBuildSystemAdapter()
    private fun add(path: String, text: String) = myFixture.addFileToProject("workspace-$name/$path", text)
    private fun root() = add("package.json", """{
        "packageManager":"npm@11.0.0",
        "workspaces":["packages/*","!packages/excluded"],
        "dependencies":{"alpha":"^1.2.3"},
        "devDependencies":{"alias":"npm:alpha@~1.2.3"},
        "overrides":{"alpha":"1.2.3"},"version":"1.2.3"
    }""")
    private fun child() = add("packages/app/package.json", """{
        "dependencies":{"alpha":"1.2.3"},"optionalDependencies":{"alpha-alias":"npm:alpha@^1.2.3"},
        "peerDependencies":{"alpha":"^1.2.3"},"scripts":{"test":"echo 1.2.3"}
    }""")
    private fun fixes(file: PsiFile): Array<LocalQuickFix> {
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val declaration = snapshot.declarations.first { it.id.location == "dependencies/alpha" }
        return adapter.quickFixes(project, snapshot,
            UpdateCandidate(declaration, "1.2.9", NpmSelector.parse("alpha", declaration.selector)!!.replace("1.2.9"), VersionChangeKind.PATCH),
            NpmManifest.values(file)[declaration.id]!!)
    }
    private fun apply(file: PsiFile, fix: LocalQuickFix) {
        val value = NpmManifest.values(file).entries.first { it.key.location == "dependencies/alpha" }.value
        val descriptor = InspectionManager.getInstance(project)
            .createProblemDescriptor(value, "update", fix, ProblemHighlightType.WARNING, false)
        if (fix.startInWriteAction()) WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        else fix.applyFix(project, descriptor)
    }
    private fun selectors(file: PsiFile) = NpmManifest.values(file).mapKeys { it.key.location }.mapValues { it.value.value }

    private fun reminders(): MutableList<Notification> {
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Version Checker") notifications += notification
            }
        })
        return notifications
    }

    private fun inspect(file: PsiFile, versions: Map<String, String> = emptyMap()): List<ProblemDescriptor> {
        val snapshot = adapter.snapshot(project, file.virtualFile)!!
        val checking = object : BuildSystemAdapter by adapter {
            override val capabilities = adapter.capabilities.copy(incrementalInspections = false)
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                UpdateReport(snapshot.declarations.filter { it.baseline.isNotEmpty() }.map {
                    val latest = versions[it.id.location] ?: "1.2.9"
                    UpdateCandidate(it, latest, NpmSelector.parse(it.artifact.name, it.selector)!!.replace(latest), VersionChangeKind.PATCH)
                })
        }
        val service = project.service<VersionCheckService>()
        service.updates(checking, snapshot)
        PlatformTestUtil.waitWithEventsDispatching("npm report cached", { service.cached(snapshot) != null }, 10)
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
        val visitor = NewerNpmDependencyInspection().buildVisitor(holder, true)
        PsiTreeUtil.processElements(file) { element -> element.accept(visitor); true }
        return holder.results
    }

    private fun apply(problem: ProblemDescriptor, index: Int) {
        val fix = problem.fixes!![index] as LocalQuickFix
        if (fix.startInWriteAction()) WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, problem) }
        else fix.applyFix(project, problem)
    }

    fun testInspectionSharesWorkspaceActionButKeepsLocalAliasesAndPassesSeparate() {
        val root = root()
        val child = child()
        val problems = inspect(root)
        assertEquals(2, problems.size)
        assertSame(problems[0].fixes!![1], problems[1].fixes!![1])
        assertNotSame(problems[0].fixes!![0], problems[1].fixes!![0])
        assertNotSame("Prepared edits must stay within their inspection pass", problems[0].fixes!![1], inspect(root)[0].fixes!![1])
        val alias = problems.single { "npm:alpha@~1.2.3" in it.psiElement.text }
        apply(alias, 0)
        assertEquals("npm:alpha@~1.2.9", selectors(root)["devDependencies/alias"])
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
        assertEquals("npm:alpha@^1.2.3", selectors(child)["optionalDependencies/alpha-alias"])
    }

    fun testInspectionKeepsDifferentTargetsAndArtifactsSeparate() {
        val root = add("package.json", """{"workspaces":["packages/*"],"dependencies":{
            "one":"npm:alpha@^1.2.3","two":"npm:alpha@~1.2.3","beta":"^1.2.3"}}""")
        val child = add("packages/app/package.json", """{"dependencies":{"alpha":"1.2.3","beta":"~1.2.3"}}""")
        val problems = inspect(root, mapOf("dependencies/one" to "1.2.9", "dependencies/two" to "1.3.0"))
        val lower = problems.single { "npm:alpha@^" in it.psiElement.text }
        val higher = problems.single { "npm:alpha@~" in it.psiElement.text }
        val beta = problems.single { it.descriptionTemplate.contains("of beta ") }
        assertNotSame(lower.fixes!![1], higher.fixes!![1])
        assertNotSame(lower.fixes!![1], beta.fixes!![1])
        assertEquals("Update alpha across workspace to 1.2.9 (3 declarations)", lower.fixes!![1].name)
        assertEquals("Update alpha across workspace to 1.3.0 (3 declarations)", higher.fixes!![1].name)
        assertEquals("Update beta across workspace to 1.2.9 (2 declarations)", beta.fixes!![1].name)
        apply(higher, 1)
        assertEquals("npm:alpha@^1.3.0", selectors(root)["dependencies/one"])
        assertEquals("npm:alpha@~1.3.0", selectors(root)["dependencies/two"])
        assertEquals("1.3.0", selectors(child)["dependencies/alpha"])
        assertEquals("^1.2.3", selectors(root)["dependencies/beta"])
        assertEquals("~1.2.3", selectors(child)["dependencies/beta"])
    }

    fun testSharedInspectionWorkspaceActionRejectsChangedSiblingForEveryAlias() {
        val root = root()
        val child = child()
        val problems = inspect(root)
        assertSame(problems[0].fixes!![1], problems[1].fixes!![1])
        WriteCommandAction.runWriteCommandAction(project) {
            val value = NpmManifest.values(child).entries.single { it.key.location == "dependencies/alpha" }.value
            NpmVersionEdit(value, "1.2.8", "test").apply()
        }
        val before = listOf(root.text, child.text)
        problems.forEach { apply(it, 1) }
        assertEquals(before, listOf(root.text, child.text))
    }

    fun testInspectionKeepsSingleLocalActionsWhenOtherMembersAlreadyUseNewerVersions() {
        val root = root()
        val newer = add("packages/newer/package.json", """{"dependencies":{"alpha":"2.0.0"},
            "devDependencies":{"alias":"npm:alpha@^1.2.10"}}""")
        val before = newer.text
        val problems = inspect(root)
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.fixes!!.size == 1 })
        val alias = problems.single { "npm:alpha@~1.2.3" in it.psiElement.text }
        assertEquals("Update declared version to npm:alpha@~1.2.9", alias.fixes!!.single().name)
        apply(alias, 0)
        assertEquals("npm:alpha@~1.2.9", selectors(root)["devDependencies/alias"])
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
        assertEquals(before, newer.text)
    }

    fun testInspectionWorkspaceFixesKeepAliasesGroupedByArtifactAndExcludeOtherPackages() {
        val root = add("package.json", """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.2.3","beta":"^1.2.3"}}""")
        val child = add("packages/app/package.json", """{"dependencies":{"alias":"npm:alpha@~1.2.3","beta":"~1.2.3"}}""")
        val problems = inspect(root)
        assertEquals(2, problems.size)
        val alpha = problems.first { it.descriptionTemplate.contains("of alpha ") }
        val beta = problems.first { it.descriptionTemplate.contains("of beta ") }
        assertEquals("Update alpha across workspace to 1.2.9 (2 declarations)", alpha.fixes!![1].name)
        assertEquals("Update beta across workspace to 1.2.9 (2 declarations)", beta.fixes!![1].name)
        apply(root, alpha.fixes!![1] as LocalQuickFix)
        assertEquals("^1.2.9", selectors(root)["dependencies/alpha"])
        assertEquals("npm:alpha@~1.2.9", selectors(child)["dependencies/alias"])
        assertEquals("^1.2.3", selectors(root)["dependencies/beta"])
        assertEquals("~1.2.3", selectors(child)["dependencies/beta"])
    }
    fun testSuccessfulLocalAndWorkspaceFixesShowSynchronizationReminder() {
        val rootFile = root()
        val child = child()
        val notifications = reminders()
        apply(child, fixes(child)[0])
        assertEquals(1, notifications.size)
        assertTrue(notifications.single().content.contains("npm install"))
        notifications.clear()
        apply(rootFile, fixes(rootFile)[1])
        assertEquals(1, notifications.size)
        assertTrue(notifications.single().content.contains("lockfiles"))
    }
    fun testStaleLocalAndWorkspaceFixesOfferRefreshWithoutSynchronizationReminder() {
        val root = root()
        val child = child()
        val retained = fixes(child)
        val notifications = reminders()
        WriteCommandAction.runWriteCommandAction(project) {
            NpmVersionEdit(NpmManifest.values(root).values.first(), "^1.2.8", "test").apply()
        }
        retained.forEach { apply(child, it) }
        PlatformTestUtil.waitWithEventsDispatching("Stale fixes offer one recovery action", {
            notifications.any { it.title == "Version update needs a fresh check" }
        }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val recovery = notifications.single { it.title == "Version update needs a fresh check" }
        assertTrue(recovery.actions.any { it.templatePresentation.text == "Refresh This File" })
        assertTrue(notifications.none { it.content.contains("npm install") })
        recovery.expire()
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
    }
    fun testLocalChoiceUpdatesOnlySelectedDeclaration() {
        val root = root()
        val child = child()
        val fixes = fixes(child)
        assertEquals(2, fixes.size)
        assertEquals("Update locally to 1.2.9", fixes[0].name)
        apply(child, fixes[0])
        assertEquals("1.2.9", selectors(child)["dependencies/alpha"])
        assertEquals("npm:alpha@^1.2.3", selectors(child)["optionalDependencies/alpha-alias"])
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
    }
    fun testWorkspaceChoiceUpdatesRootMembersAndAliasesWithOriginalOperators() {
        val root = root()
        val child = child()
        val lock = add("package-lock.json", "unchanged")
        val fixes = fixes(child)
        assertEquals("Update alpha across workspace to 1.2.9 (4 declarations)", fixes[1].name)
        assertFalse(fixes[1].startInWriteAction())
        apply(child, fixes[1])
        assertEquals("^1.2.9", selectors(root)["dependencies/alpha"])
        assertEquals("npm:alpha@~1.2.9", selectors(root)["devDependencies/alias"])
        assertEquals("1.2.9", selectors(child)["dependencies/alpha"])
        assertEquals("npm:alpha@^1.2.9", selectors(child)["optionalDependencies/alpha-alias"])
        assertEquals("^1.2.3", selectors(child)["peerDependencies/alpha"])
        assertTrue(root.text.contains("\"overrides\":{\"alpha\":\"1.2.3\"}"))
        assertTrue(root.text.contains("\"version\":\"1.2.3\""))
        assertTrue(child.text.contains("echo 1.2.3"))
        assertEquals("unchanged", lock.text)
        assertNull(root.virtualFile.parent.findChild("node_modules"))
    }
    fun testRootDeclarationAlsoOffersBothChoices() {
        val root = root()
        val child = child()
        assertEquals(2, fixes(root).size)
        apply(root, fixes(root)[0])
        assertEquals("^1.2.9", selectors(root)["dependencies/alpha"])
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
    }
    fun testWorkspaceUpdateIsOneUndoableCommand() {
        val root = root()
        val child = child()
        val editor = FileEditorManager.getInstance(project).openFile(child.virtualFile, true).filterIsInstance<TextEditor>().single()
        apply(child, fixes(child)[1])
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
        assertEquals("npm:alpha@~1.2.3", selectors(root)["devDependencies/alias"])
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
        assertEquals("npm:alpha@^1.2.3", selectors(child)["optionalDependencies/alpha-alias"])
    }
    fun testStandaloneAndSingleManifestKeepSingleFix() {
        val root = root()
        assertEquals(1, fixes(root).size)
        val independent = add("independent/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        assertEquals(1, fixes(independent).size)
        assertEquals("Update declared version to 1.2.9", fixes(independent).single().name)
    }
    fun testWorkspaceWithoutRootDependencyStillOffersMemberUpdates() {
        add("package.json", """{"workspaces":{"packages":["packages/*"]}}""")
        val child = child()
        val other = add("packages/other/package.json", """{"devDependencies":{"alpha":"~1.2.3"}}""")
        apply(child, fixes(child)[1])
        assertEquals("~1.2.9", selectors(other)["devDependencies/alpha"])
    }
    fun testExcludedIndependentAndNestedWorkspaceDeclarationsRemainUnchanged() {
        val root = root()
        val child = child()
        val untouched = listOf(
            add("packages/excluded/package.json", """{"dependencies":{"alpha":"1.2.3"}}"""),
            add("independent/package.json", """{"dependencies":{"alpha":"1.2.3"}}"""),
            add("packages/nested/package.json", """{"workspaces":["nested/*"],"dependencies":{"alpha":"1.2.3"}}"""),
            add("packages/nested/nested/app/package.json", """{"dependencies":{"alpha":"1.2.3"}}"""),
            add("packages/pnpm/package.json", """{"packageManager":"pnpm@10.0.0","dependencies":{"alpha":"1.2.3"}}"""),
            add("packages/bun/package.json", """{"devEngines":{"packageManager":{"name":"bun"}},"dependencies":{"alpha":"1.2.3"}}"""),
            add("node_modules/library/package.json", """{"dependencies":{"alpha":"1.2.3"}}""")
        )
        val before = untouched.map { it.text }
        apply(child, fixes(child)[1])
        assertEquals(before, untouched.map { it.text })
        assertEquals("^1.2.9", selectors(root)["dependencies/alpha"])
    }
    fun testNewerComplexLocalAndPeerDeclarationsRemainUnchanged() {
        root()
        val child = child()
        val other = add("packages/other/package.json", """{
            "dependencies":{"alpha":"^2.0.0"},"devDependencies":{"alpha":"latest"},
            "optionalDependencies":{"alpha":"file:../external"},"peerDependencies":{"alpha":"1.2.3"}
        }""")
        val before = other.text
        apply(child, fixes(child)[1])
        assertEquals(before, other.text)
    }
    fun testChangedSiblingRejectsEntireWorkspaceFix() {
        val root = root()
        val child = child()
        val fix = fixes(child)[1]
        WriteCommandAction.runWriteCommandAction(project) { NpmVersionEdit(NpmManifest.values(root).values.first(), "^1.2.8", "test").apply() }
        val before = root.text
        apply(child, fix)
        assertEquals(before, root.text)
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
    }
    fun testChangedSelectedDeclarationRejectsEntireWorkspaceFix() {
        val root = root()
        val child = child()
        val fix = fixes(child)[1]
        WriteCommandAction.runWriteCommandAction(project) { NpmVersionEdit(NpmManifest.values(child).values.first(), "1.2.8", "test").apply() }
        apply(child, fix)
        assertEquals("1.2.8", selectors(child)["dependencies/alpha"])
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
    }
    fun testMembershipChangesRejectLocalAndWorkspaceFixes() {
        val root = root()
        val child = child()
        val fixes = fixes(child)
        add("packages/new/package.json", """{"name":"new","dependencies":{"alpha":"1.2.3"}}""")
        fixes.forEach { apply(child, it) }
        assertEquals("1.2.3", selectors(child)["dependencies/alpha"])
        assertEquals("^1.2.3", selectors(root)["dependencies/alpha"])
    }
}
