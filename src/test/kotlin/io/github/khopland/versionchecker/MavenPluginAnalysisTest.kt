package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.xml.XmlFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProject

class MavenPluginAnalysisTest : BasePlatformTestCase() {
    private fun file(body: String) = myFixture.configureByText("pom.xml", """
        <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
        <groupId>demo</groupId><artifactId>app</artifactId><version>1</version>$body</project>
    """.trimIndent())

    fun testDefaultGroupLiteralPluginAndPropertyManagedPlugin() {
        val file = file("""<properties><surefire.version>3.2.1</surefire.version></properties><build>
            <plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.12.0</version></plugin></plugins>
            <pluginManagement><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><version>${'$'}{surefire.version}</version></plugin></plugins></pluginManagement>
            </build>""")
        val updates = mapOf(
            DependencyVersion("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0", MavenArtifactKind.PLUGIN) to "3.12.1",
            DependencyVersion("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.1", MavenArtifactKind.PLUGIN) to "3.2.5")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile), updates)
        val problems = analysis.problems(file)
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.severity == DependencySeverity.WARNING && it.message.contains("Maven plugin") })
        val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis), MavenArtifactKind.PLUGIN)
        assertEquals(2, plan.changes.size)
        assertTrue(plan.apply(project))
        assertEquals("3.2.5", (file as XmlFile).rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
    }

    fun testDoesNotInspectPluginDependenciesOrConfigurationElementsAsPlugins() {
        val file = file("""<build><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId><version>1.0</version>
            <dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></dependency></dependencies>
            <configuration><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId><version>1.0</version></plugin></plugins></configuration>
            </plugin></plugins></build>""")
        val updates = mapOf(DependencyVersion("g", "p", "1.0", MavenArtifactKind.PLUGIN) to "2.0", DependencyVersion("g", "a", "1.0") to "2.0")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile), updates)
        assertEquals(1, analysis.problems(file).size)
    }

    fun testSharedDependencyPluginPropertyRequiresReviewInPluginOnlyMode() {
        val file = file("""<properties><shared>1.0</shared></properties>
            <dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>${'$'}{shared}</version></dependency></dependencies>
            <build><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId><version>${'$'}{shared}</version></plugin></plugins></build>""")
        val updates = mapOf(DependencyVersion("g", "p", "1.0", MavenArtifactKind.PLUGIN) to "2.0", DependencyVersion("g", "a", "1.0") to "2.0")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile), updates)
        assertTrue(MavenBulkUpdatePlan.create(mapOf(file to analysis), MavenArtifactKind.PLUGIN).changes.isEmpty())
        assertEquals(1, MavenBulkUpdatePlan.create(mapOf(file to analysis)).changes.size)
    }

    fun testInactiveProfilePluginsAndUnversionedDefaultsAreNotUpdated() {
        val file = file("""<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>
            <profiles><profile><id>off</id><build><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId><version>1.0</version></plugin></plugins></build></profile></profiles>""")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile),
            mapOf(DependencyVersion("g", "p", "1.0", MavenArtifactKind.PLUGIN) to "2.0"))
        assertTrue(analysis.problems(file).isEmpty())
    }
    fun testCombinedUpdatesSkipConflictingDependencyAndPluginProperty() {
        val file = file("""<properties><shared>1.0</shared></properties>
            <dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>${'$'}{shared}</version></dependency></dependencies>
            <build><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId><version>${'$'}{shared}</version></plugin></plugins></build>""")
        val updates = mapOf(DependencyVersion("g", "p", "1.0", MavenArtifactKind.PLUGIN) to "3.0", DependencyVersion("g", "a", "1.0") to "2.0")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!, MavenProject(file.virtualFile), updates)
        val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("different updates"))
    }

}
