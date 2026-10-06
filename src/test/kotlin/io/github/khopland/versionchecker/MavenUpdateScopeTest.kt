package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import io.github.khopland.versionchecker.core.*

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

class MavenUpdateScopeTest : BasePlatformTestCase() {
    private fun imported(manager: MavenProjectsManager, name: String): MavenProject {
        val file = myFixture.addFileToProject("$name/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>scope.demo</groupId><artifactId>$name</artifactId><version>1</version></project>
        """.trimIndent())
        return MavenProject(file.virtualFile).apply {
            updateMavenId(MavenId("scope.demo", name, "1"))
            manager.projectsTree.putVirtualFileToProjectMapping(this, mavenId)
        }
    }

    fun testCurrentFileSelectionNeverFallsBackToWholeProject() {
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        try {
            val root = imported(manager, "root")
            val child = imported(manager, "child")
            val adapter = MavenBuildSystemAdapter()
            assertTrue(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, child.file.path)))
            assertTrue(adapter.supports(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
            assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE)))
            val text = myFixture.addFileToProject("README.txt", "Not a Maven POM")
            assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, text.virtualFile.path)))
            manager.projectsTree.setIgnoredState(listOf(child), true)
            assertFalse(adapter.supports(project, BuildSelection(UpdateScope.CURRENT_FILE, child.file.path)))
            assertTrue(adapter.supports(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
            manager.projectsTree.setIgnoredState(listOf(root), true)
            assertFalse(adapter.supports(project, BuildSelection(UpdateScope.WHOLE_PROJECT)))
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
        }
    }

    fun testCurrentFileActionsPreferActiveEditorOverProjectViewSelection() {
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        try {
            val root = imported(manager, "parent")
            val child = imported(manager, "module")
            myFixture.configureFromExistingVirtualFile(child.file)
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, myFixture.editor).add(CommonDataKeys.VIRTUAL_FILE, root.file).build()
            val event = AnActionEvent.createFromDataContext("test", null, context)
            assertEquals(child.file, currentBuildFile(event))
            CurrentFilePatchUpdateAction().update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
            manager.projectsTree.setIgnoredState(listOf(child), true)
            CurrentFilePatchUpdateAction().update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
            PatchUpdateAction().update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
        }
    }
}
