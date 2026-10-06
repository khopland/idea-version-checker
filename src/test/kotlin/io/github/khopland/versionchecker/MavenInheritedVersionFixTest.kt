package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.maven.*
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenParent
import org.jetbrains.idea.maven.model.MavenArtifactInfo
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

class MavenInheritedVersionFixTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-parent-fixes-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
    }

    override fun tearDown() {
        try {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            directory.toFile().deleteRecursively()
        }
        finally { super.tearDown() }
    }

    private fun imported(name: String, body: String, parent: String? = null): XmlFile {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            ${parent?.let { "<parent><groupId>demo</groupId><artifactId>$it</artifactId><version>1</version></parent>" }.orEmpty()}
            <groupId>demo</groupId><artifactId>$name</artifactId><version>1</version>$body</project>
        """.trimIndent())
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val file = PsiManager.getInstance(project).findFile(virtualFile) as XmlFile
        val effectiveProperties = java.util.Properties().apply {
            parent?.let { manager.findProject(MavenId("demo", it, "1"))?.properties }?.let(::putAll)
            file.rootTag!!.findFirstSubTag("properties")?.subTags?.forEach { setProperty(it.localName, it.value.trimmedText) }
        }
        val parentProject = parent?.let { manager.findProject(MavenId("demo", it, "1")) }
        val managed = if (file.rootTag!!.findFirstSubTag("dependencyManagement") != null)
            listOf(MavenArtifactInfo("junit", "junit", "4.12", "jar", null))
        else parentProject?.managedDependencies()?.values?.toList().orEmpty()
        val model = MavenModel().apply {
            mavenId = MavenId("demo", name, "1")
            setProperties(effectiveProperties)
            parentProject?.let { setParent(MavenParent(it.mavenId, "../${parent}/pom.xml")) }
        }
        val imported = MavenProject(file.virtualFile).apply {
            updateState(model, managed, "21", emptyList(), MavenExplicitProfiles.NONE,
                emptySet(), emptyMap(), this@MavenInheritedVersionFixTest.directory.resolve("repository"), false)
        }
        manager.projectsTree.putVirtualFileToProjectMapping(imported, imported.mavenId)
        manager.projectsTree.setIgnoredState(listOf(imported), false)
        return file
    }

    private fun management(version: String) = """<dependencyManagement><dependencies><dependency>
        <groupId>junit</groupId><artifactId>junit</artifactId><version>$version</version><scope>test</scope>
        </dependency></dependencies></dependencyManagement>"""

    private fun dependency(version: String = "") = """<dependencies><dependency>
        <groupId>junit</groupId><artifactId>junit</artifactId>$version<scope>test</scope>
        </dependency></dependencies>"""

    private fun declaration(file: XmlFile) = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single()

    private fun fixes(file: XmlFile): Array<LocalQuickFix> {
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!,
            manager.findProject(file.virtualFile)!!, mapOf(DependencyVersion("junit", "junit", "4.12") to "4.13.2"))
        val tag = declaration(file)
        val problem = analysis.problem(tag) ?: error("Expected an inherited-version update")
        return analysis.quickFixes(tag, problem)
    }

    private fun apply(fix: LocalQuickFix, tag: XmlTag) {
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            tag, "Newer version available", fix, ProblemHighlightType.WARNING, true)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
    }

    fun testLocalChoiceAddsVersionWithoutChangingParentManagement() {
        val parent = imported("parent", management("4.12"))
        val child = imported("child", dependency(), "parent")
        val parentText = parent.text
        val options = fixes(child)
        assertEquals(2, options.size)
        assertEquals("Override version locally with 4.13.2", options[0].name)
        assertTrue(options[1].name.contains("parent version"))
        apply(options[0], declaration(child))
        assertEquals(parentText, parent.text)
        assertEquals("4.13.2", declaration(child).findFirstSubTag("version")!!.value.trimmedText)
        assertEquals(listOf("groupId", "artifactId", "version", "scope"), declaration(child).subTags.map { it.localName })
        assertEquals("test", declaration(child).findFirstSubTag("scope")!!.value.trimmedText)
    }

    fun testParentChoiceChangesManagedLiteralAndMakesParentWritable() {
        val parent = imported("parent", management("4.12"))
        val child = imported("child", dependency(), "parent")
        val childText = child.text
        val option = fixes(child)[1]
        assertTrue(option.name.contains("parent/pom.xml"))
        assertEquals(parent, option.getElementToMakeWritable(child)!!.containingFile)
        apply(option, declaration(child))
        assertTrue(parent.text.contains("<version>4.13.2</version>"))
        assertEquals(childText, child.text)
    }

    fun testParentChoiceFollowsPropertyThroughTwoParentLevels() {
        val root = imported("root", "<properties><junit.version>4.12</junit.version></properties>" + management("\${junit.version}"))
        imported("middle", "", "root")
        val child = imported("child", dependency(), "middle")
        val option = fixes(child)[1]
        assertTrue(option.name.contains("root/pom.xml"))
        apply(option, declaration(child))
        assertEquals("4.13.2", root.rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
        assertTrue(root.text.contains("<version>\${junit.version}</version>"))
        assertNull(declaration(child).findFirstSubTag("version"))
    }

    fun testInheritedPropertyReferenceCanBeOverriddenLocally() {
        val parent = imported("parent", "<properties><junit.version>4.12</junit.version></properties>")
        val child = imported("child", dependency("<version>\${junit.version}</version>"), "parent")
        val options = fixes(child)
        assertEquals(2, options.size)
        apply(options[0], declaration(child))
        assertEquals("4.13.2", declaration(child).findFirstSubTag("version")!!.value.trimmedText)
        assertEquals("4.12", parent.rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
    }

    fun testInheritedPropertyReferenceCanBeUpdatedInParent() {
        val parent = imported("parent", "<properties><junit.version>4.12</junit.version></properties>")
        val child = imported("child", dependency("<version>\${junit.version}</version>"), "parent")
        apply(fixes(child)[1], declaration(child))
        assertEquals("\${junit.version}", declaration(child).findFirstSubTag("version")!!.value.trimmedText)
        assertEquals("4.13.2", parent.rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
    }

    fun testManagingPropertyChainUpdatesOnlyControllingLiteral() {
        val parent = imported("parent", "<properties><junit.version>\${shared.version}</junit.version><shared.version>4.12</shared.version></properties>" +
            management("\${junit.version}"))
        val child = imported("child", dependency(), "parent")
        apply(fixes(child)[1], declaration(child))
        val properties = parent.rootTag!!.findFirstSubTag("properties")!!
        assertEquals("4.13.2", properties.findFirstSubTag("shared.version")!!.value.trimmedText)
        assertEquals("\${shared.version}", properties.findFirstSubTag("junit.version")!!.value.trimmedText)
        assertNull(declaration(child).findFirstSubTag("version"))
    }

    fun testUnrelatedProjectIsNotMistakenForAParent() {
        val unrelated = imported("unrelated", management("4.12"))
        val child = imported("child", dependency("<version>\${missing.version}</version>"))
        // Even a known effective version must not be matched to an unrelated declaration by coordinates alone.
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(child)!!,
            manager.findProject(child.virtualFile)!!, emptyMap())
        val tag = declaration(child)
        val problem = DependencyProblem(DependencyVersion("junit", "junit", "4.12"), "4.13.2", tag,
            null, io.github.khopland.versionchecker.core.VersionChangeKind.PATCH, VersionSeverity.WARNING)
        val options = analysis.quickFixes(tag, problem)
        assertEquals(1, options.size)
        assertTrue(options.single() is OverrideDependencyVersionFix)
        assertTrue(unrelated.text.contains("<version>4.12</version>"))
    }

    fun testNearestManagementWinsOverGrandparent() {
        val root = imported("root", management("4.12"))
        val middle = imported("middle", management("4.12"), "root")
        val child = imported("child", dependency(), "middle")
        val rootText = root.text
        val option = fixes(child)[1]
        assertTrue(option.name.contains("middle/pom.xml"))
        apply(option, declaration(child))
        assertEquals(rootText, root.text)
        assertTrue(middle.text.contains("<version>4.13.2</version>"))
    }

    fun testStaleParentOrChildRejectsBothChoices() {
        val parent = imported("parent", management("4.12"))
        val child = imported("child", dependency(), "parent")
        val options = fixes(child)
        val document = FileDocumentManager.getInstance().getDocument(parent.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("4.12", "4.13.1"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        options.forEach { apply(it, declaration(child)) }
        assertNull(declaration(child).findFirstSubTag("version"))
        assertTrue(parent.text.contains("<version>4.13.1</version>"))
    }

    fun testIgnoredParentIsNotOfferedForEditing() {
        val parent = imported("parent", management("4.12"))
        val child = imported("child", dependency(), "parent")
        manager.projectsTree.setIgnoredState(listOf(manager.findProject(parent.virtualFile)!!), true)
        val options = fixes(child)
        assertEquals(1, options.size)
        assertTrue(options.single() is OverrideDependencyVersionFix)
    }

    fun testEditingChildDeclarationRejectsBothChoices() {
        val parent = imported("parent", management("4.12"))
        val child = imported("child", dependency(), "parent")
        val parentText = parent.text
        val options = fixes(child)
        WriteCommandAction.runWriteCommandAction(project) {
            declaration(child).findFirstSubTag("artifactId")!!.value.setText("different")
        }
        options.forEach { apply(it, declaration(child)) }
        assertEquals(parentText, parent.text)
        assertNull(declaration(child).findFirstSubTag("version"))
    }

    fun testExplicitChildVersionKeepsItsExistingSingleFix() {
        imported("parent", management("4.12"))
        val child = imported("child", dependency("<version>4.12</version>"), "parent")
        val options = fixes(child)
        assertEquals(1, options.size)
        assertEquals("Update version to 4.13.2", options.single().name)
    }
}
