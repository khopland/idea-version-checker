package io.github.khopland.versionchecker

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProject

class BulkUpdatePlanTest : BasePlatformTestCase() {
    private fun plan(body: String, updates: Map<DependencyVersion, String>, options: VersionCheckerSettings.Options = VersionCheckerSettings.Options()): BulkUpdatePlan {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>
              $body
            </project>
        """.trimIndent())
        val model = MavenDomUtil.getMavenDomProjectModel(file)!!
        return BulkUpdatePlan.create(mapOf(file to MavenDependencyAnalysis(model, MavenProject(file.virtualFile), updates, options)))
    }
    private fun dep(name: String, version: String): String =
        "<dependency><groupId>g</groupId><artifactId>$name</artifactId><version>$version</version></dependency>"

    fun testDisablingEditorMessagesDoesNotDisableBulkUpdates() {
        val plan = plan("<dependencies>" + dep("a", "1.0") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"),
            VersionCheckerSettings.Options(majorSeverity = DependencySeverity.DISABLED))
        assertEquals(1, plan.changes.size)
    }

    fun testDeduplicatesCompatibleSharedProperty() {
        val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" +
            dep("a", "\${shared}") + dep("b", "\${shared}") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "2.0"))
        assertEquals(1, plan.changes.size)
        assertTrue(plan.apply(project))
        assertEquals("2.0", (myFixture.file as XmlFile).rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
    }
    fun testSkipsConflictingSharedProperty() {
        val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" +
            dep("a", "\${shared}") + dep("b", "\${shared}") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "3.0"))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.isNotEmpty())
    }
    fun testSkipsPropertyUsedByDependencyWithoutAnUpdate() {
        val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" +
            dep("a", "\${shared}") + dep("b", "\${shared}") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        assertTrue(plan.changes.isEmpty())
    }
    fun testSkipsPropertyUsedByBuildPlugin() {
        val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" + dep("a", "\${shared}") +
            "</dependencies><build><plugins><plugin><version>\${shared}</version></plugin></plugins></build>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        assertTrue(plan.changes.isEmpty())
    }
    fun testStalePreviewDoesNotPartiallyApply() {
        val plan = plan("<dependencies>" + dep("a", "1.0") + dep("b", "1.0") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "2.0"))
        assertEquals(2, plan.changes.size)
        WriteCommandAction.runWriteCommandAction(project) { plan.changes.last().pointer.element!!.value.setText("3.0") }
        assertFalse(plan.apply(project))
        assertEquals("1.0", plan.changes.first().pointer.element!!.value.trimmedText)
    }

    fun testCurrentPomPropertySafetyIncludesUsesInOtherModules() {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>g</groupId><artifactId>parent</artifactId><version>1</version>
            <properties><shared>1.0</shared></properties><dependencies>${dep("a", "\${shared}")}</dependencies></project>
        """.trimIndent())
        val child = myFixture.addFileToProject("child/pom.xml", "<project><dependencies>${dep("b", "\${shared}")}</dependencies></project>")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile),
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        val plan = BulkUpdatePlan.create(mapOf(file to analysis), MavenArtifactKind.DEPENDENCY, listOf(file, child))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("inherited"))
    }
}
