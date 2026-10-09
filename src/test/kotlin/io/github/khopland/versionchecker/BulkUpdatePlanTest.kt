package io.github.khopland.versionchecker


import io.github.khopland.versionchecker.maven.*

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.psi.PsiDocumentManager
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
        return MavenBulkUpdatePlan.create(mapOf(file to MavenDependencyAnalysis(model, MavenProject(file.virtualFile), updates, options)))
    }
    private fun dep(name: String, version: String): String =
        "<dependency><groupId>g</groupId><artifactId>$name</artifactId><version>$version</version></dependency>"

    fun testDisablingEditorMessagesDoesNotDisableBulkUpdates() {
        val plan = plan("<dependencies>" + dep("a", "1.0") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"),
            VersionCheckerSettings.Options(majorSeverity = VersionSeverity.DISABLED))
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
    fun testSkipsPropertyUsedInPluginConfigurationAttributes() {
        for (configuration in listOf(
            "<component version=\"\${shared}\"/>",
            "<component version=\"prefix-\${shared}\"><child/></component>"
        )) {
            val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" + dep("a", "\${shared}") +
                "</dependencies><build><plugins><plugin><groupId>g</groupId><artifactId>generator</artifactId>" +
                "<version>1.0</version><configuration>$configuration</configuration></plugin></plugins></build>",
                mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
            assertTrue(configuration, plan.changes.isEmpty())
            assertTrue(plan.skipped.single().contains("other uses"))
        }
    }
    fun testSkipsCompositeRepeatedAndNestedPropertyUses() {
        for (configuration in listOf(
            "<value>prefix-\${shared}-suffix</value>",
            "<value>\${other}-\${shared}-\${shared}</value>",
            "<value>\${outer-\${shared}}</value>",
            "<value>\${unfinished-\${shared}</value>",
            "<component first=\"\${other}\" second=\"\${outer-\${shared}}\"><child/></component>"
        )) {
            val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" + dep("a", "\${shared}") +
                "</dependencies><build><plugins><plugin><groupId>g</groupId><artifactId>generator</artifactId>" +
                "<version>1.0</version><configuration>$configuration</configuration></plugin></plugins></build>",
                mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
            assertTrue(configuration, plan.changes.isEmpty())
            assertTrue(plan.skipped.single().contains("other uses"))
        }
    }

    fun testSimilarlyNamedReferencesDoNotBlockAnIndependentProperty() {
        val plan = plan("<properties><shared>1.0</shared><shared.extra>unchanged</shared.extra></properties>" +
            "<dependencies>" + dep("a", "\${shared}") + "</dependencies>" +
            "<build><plugins><plugin><configuration><value>\${shared.extra}-\${shared-prefix}</value>" +
            "<component version=\"\${shared.extra}\"/></configuration></plugin></plugins></build>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        assertEquals(1, plan.changes.size)
        assertTrue(plan.skipped.isEmpty())
    }

    fun testEachPropertyKeepsItsOwnConsumersAndReviewDecision() {
        val plan = plan("<properties><safe>1.0</safe><blocked>1.0</blocked></properties><dependencies>" +
            dep("a", "\${safe}") + dep("b", "\${safe}") + dep("c", "\${blocked}") +
            "</dependencies><description>\${blocked}</description>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "2.0",
                DependencyVersion("g", "c", "1.0") to "3.0"))
        assertEquals(listOf("2.0"), plan.changes.map { it.latest })
        assertEquals("safe", (plan.changes.single().element as com.intellij.psi.xml.XmlTag).localName)
        assertTrue(plan.skipped.single().startsWith("blocked:"))
    }

    fun testPropertyUsesInInactiveProfilesStillNeedReview() {
        val plan = plan("<properties><shared>1.0</shared></properties><dependencies>" + dep("a", "\${shared}") +
            "</dependencies><profiles><profile><id>inactive</id><dependencies>" + dep("b", "\${shared}") +
            "</dependencies></profile></profiles>", mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("other uses"))
    }
    fun testStalePreviewDoesNotPartiallyApply() {
        val plan = plan("<dependencies>" + dep("a", "1.0") + dep("b", "1.0") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "2.0"))
        assertEquals(2, plan.changes.size)
        WriteCommandAction.runWriteCommandAction(project) { (plan.changes.last().element as com.intellij.psi.xml.XmlTag).value.setText("3.0") }
        assertFalse(plan.apply(project))
        assertEquals("1.0", (plan.changes.first().element as com.intellij.psi.xml.XmlTag).value.trimmedText)
    }

    fun testMultipleMavenEditsUndoTogether() {
        val plan = plan("<dependencies>" + dep("a", "1.0") + dep("b", "1.0") + "</dependencies>",
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0", DependencyVersion("g", "b", "1.0") to "2.0"))
        val editor = FileEditorManager.getInstance(project).openFile(myFixture.file.virtualFile, true)
            .filterIsInstance<TextEditor>().single()
        assertTrue(plan.apply(project))
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val versions = (myFixture.file as XmlFile).rootTag!!.findFirstSubTag("dependencies")!!.subTags
            .map { it.findFirstSubTag("version")!!.value.trimmedText }
        assertEquals(listOf("1.0", "1.0"), versions)
    }

    fun testCurrentFilePropertySafetyIncludesUsesInOtherModules() {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>g</groupId><artifactId>parent</artifactId><version>1</version>
            <properties><shared>1.0</shared></properties><dependencies>${dep("a", "\${shared}")}</dependencies></project>
        """.trimIndent())
        val child = myFixture.addFileToProject("child/pom.xml", "<project><dependencies>${dep("b", "\${shared}")}</dependencies></project>")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile),
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis), usageFiles = listOf(file, child))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("inherited"))
    }
    fun testCurrentFilePropertySafetyIncludesAttributesInUnselectedModules() {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>g</groupId><artifactId>parent</artifactId><version>1</version>
            <properties><shared>1.0</shared></properties><dependencies>${dep("a", "\${shared}")}</dependencies></project>
        """.trimIndent())
        val child = myFixture.addFileToProject("child/pom.xml", """
            <project><build><plugins><plugin><configuration>
            <component version="${'$'}{shared}"/>
            </configuration></plugin></plugins></build></project>
        """.trimIndent())
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile),
            mapOf(DependencyVersion("g", "a", "1.0") to "2.0"))
        val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis), usageFiles = listOf(file, child))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("other uses"))
    }
}
