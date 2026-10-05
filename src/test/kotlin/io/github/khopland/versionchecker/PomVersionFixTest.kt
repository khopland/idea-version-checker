package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PomVersionFixTest : BasePlatformTestCase() {
    private fun pom(body: String): XmlTag = (myFixture.configureByText("pom.xml", """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>
          $body
        </project>
    """.trimIndent()) as XmlFile).rootTag!!

    fun testLiteralVersionFix() {
        val root = pom("<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></dependency></dependencies>")
        val version = root.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!
        apply(UpdateDependencyVersionFix(version, "2.0"), version)
        assertEquals("2.0", version.value.trimmedText)
    }

    fun testSharedPropertyFixPreservesReferences() {
        val root = pom("""
            <properties><library.version>1.0</library.version></properties>
            <dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>${'$'}{library.version}</version></dependency></dependencies>
        """.trimIndent())
        val version = root.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!
        val target = findLocalVersionProperty(version, version.value.trimmedText, "1.0")!!
        apply(UpdateDependencyVersionFix(target, "2.0"), version)
        assertEquals("2.0", target.value.trimmedText)
        assertEquals("\${library.version}", version.value.trimmedText)
    }

    fun testProfilePropertyTakesPrecedence() {
        val root = pom("""
            <properties><library.version>0.5</library.version></properties>
            <profiles><profile><id>custom</id><properties><library.version>1.0</library.version></properties>
            <dependencies><dependency><version>${'$'}{library.version}</version></dependency></dependencies></profile></profiles>
        """.trimIndent())
        val profile = root.findFirstSubTag("profiles")!!.subTags.single()
        val version = profile.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!
        val target = findLocalVersionProperty(version, version.value.trimmedText, "1.0")!!
        assertSame(profile.findFirstSubTag("properties")!!.subTags.single(), target)
    }

    fun testInheritedAndCompositePropertiesHaveNoAutomaticFix() {
        val root = pom("<dependencies><dependency><version>\${inherited.version}</version></dependency></dependencies>")
        val version = root.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!
        assertNull(findLocalVersionProperty(version, "\${inherited.version}", "1.0"))
        assertNull(findLocalVersionProperty(version, "\${major}.0", "1.0"))
    }

    fun testFixDoesNotOverwriteAnEditedVersion() {
        val root = pom("<dependencies><dependency><version>1.0</version></dependency></dependencies>")
        val version = root.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!
        val fix = UpdateDependencyVersionFix(version, "2.0")
        WriteCommandAction.runWriteCommandAction(project) { version.value.setText("3.0") }
        apply(fix, version)
        assertEquals("3.0", version.value.trimmedText)
    }

    fun testOnlyProjectDependenciesAreInspected() {
        val root = pom("""
            <dependencies><dependency><version>1</version></dependency></dependencies>
            <dependencyManagement><dependencies><dependency><version>1</version></dependency></dependencies></dependencyManagement>
            <build><plugins><plugin><dependencies><dependency><version>1</version></dependency></dependencies></plugin></plugins></build>
        """.trimIndent())
        val dependencies = PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java).filter { it.localName == "dependency" }
        assertEquals(listOf(true, true, false), dependencies.map(::isProjectDependency))
    }

    private fun apply(fix: UpdateDependencyVersionFix, version: XmlTag) {
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            version, "Newer version available", fix, ProblemHighlightType.GENERIC_ERROR, true
        )
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
    }
}
