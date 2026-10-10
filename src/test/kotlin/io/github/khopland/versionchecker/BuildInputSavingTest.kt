package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.GradleBuildSystemAdapter
import io.github.khopland.versionchecker.npm.NpmBuildSystemAdapter
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings

class BuildInputSavingTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            val settings = GradleSettings.getInstance(project)
            settings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.forEach(settings::unlinkExternalProject)
        } finally { super.tearDown() }
    }
    private val documents get() = FileDocumentManager.getInstance()
    private fun change(file: PsiFile, suffix: String = " ") {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = documents.getDocument(file.virtualFile)!!
            document.insertString(document.textLength, suffix)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
    fun testCurrentGradleFileSavesSiblingConfigurationAndLeavesSourceAndIndependentBuildUnsaved() {
        val settings = myFixture.addFileToProject("saving-gradle/settings.gradle", "rootProject.name = 'saving'")
        val build = myFixture.addFileToProject("saving-gradle/build.gradle", "dependencies { implementation 'g:a:1.0' }")
        val sibling = myFixture.addFileToProject("saving-gradle/sub/build.gradle.kts", "// configuration")
        val properties = myFixture.addFileToProject("saving-gradle/gradle.properties", "example=true")
        val source = myFixture.addFileToProject("saving-gradle/src/App.java", "class App {}")
        val unrelated = myFixture.addFileToProject("independent/build.gradle", "// another project")
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings().apply {
            externalProjectPath = settings.virtualFile.parent.path
        })
        listOf(settings, build, sibling, properties, source, unrelated).forEach(::change)
        val adapter = GradleBuildSystemAdapter()
        assertNull(adapter.snapshot(project, build.virtualFile))
        saveBuildInputs(project, BuildSelection(UpdateScope.CURRENT_FILE, build.virtualFile.path), listOf(adapter))
        listOf(settings, build, sibling, properties).forEach { assertFalse(documents.isFileModified(it.virtualFile)) }
        listOf(source, unrelated).forEach { assertTrue(documents.isFileModified(it.virtualFile)) }
        assertNotNull(adapter.snapshot(project, build.virtualFile))
    }
    fun testNpmWorkspaceSavesMemberManifestsAndRegistryConfigurationWithoutSavingOtherProjects() {
        val root = myFixture.addFileToProject("saving-npm/package.json", """{"workspaces":["packages/*"],"dependencies":{"alpha":"^1.0.0"}}""")
        val member = myFixture.addFileToProject("saving-npm/packages/app/package.json", """{"name":"app","dependencies":{"alpha":"~1.0.0"}}""")
        val config = myFixture.addFileToProject("saving-npm/.npmrc", "prefer-online=true")
        val source = myFixture.addFileToProject("saving-npm/app.js", "const value = 1;")
        val unrelated = myFixture.addFileToProject("other-npm/package.json", """{"name":"other"}""")
        listOf(root, member, config, source, unrelated).forEach(::change)
        saveBuildInputs(project, BuildSelection(UpdateScope.CURRENT_FILE, member.virtualFile.path), listOf(NpmBuildSystemAdapter()))
        listOf(root, member, config).forEach { assertFalse(documents.isFileModified(it.virtualFile)) }
        listOf(source, unrelated).forEach { assertTrue(documents.isFileModified(it.virtualFile)) }
    }
    fun testPostApplySavingOnlySavesSelectedEditFiles() {
        val first = myFixture.addFileToProject("save-selected/a.txt", "1.0")
        val second = myFixture.addFileToProject("save-selected/b.txt", "1.0")
        change(first); change(second)
        val edit = object : VersionEdit {
            override val element = first
            override val expected = "1.0 "
            override val latest = "2.0"
            override val location = "first"
            override fun isValid() = true
            override fun apply() = Unit
        }
        saveChangedVersionFiles(BulkUpdatePlan(listOf(edit), emptyList()))
        assertFalse(documents.isFileModified(first.virtualFile))
        assertTrue(documents.isFileModified(second.virtualFile))
    }
}
