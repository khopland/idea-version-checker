package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProject
import java.io.StringReader

class DependencyNoticeTest : BasePlatformTestCase() {
    private val coordinate = DependencyVersion("old", "library", "1.0")

    fun testRelocationUsesOmittedCoordinateDefaults() {
        val xml = """<project xmlns="http://maven.apache.org/POM/4.0.0"><distributionManagement><relocation>
            <groupId>new</groupId><message>Use the maintained library</message>
            </relocation></distributionManagement></project>"""
        assertEquals("new:library:1.0 (Use the maintained library)", MavenRelocation.parse(JDOMUtil.load(StringReader(xml)), coordinate))
        assertNull(MavenRelocation.parse(JDOMUtil.load(StringReader("<project/>")), coordinate))
        assertNull(MavenRelocation.parse(JDOMUtil.load(StringReader("<project><distributionManagement><relocation/></distributionManagement></project>")), coordinate))
    }

    fun testDeprecatedDependencyHasNoticeWithoutNewerVersion() {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>demo</groupId><artifactId>app</artifactId><version>1</version><dependencies>
            <dependency><groupId>old</groupId><artifactId>library</artifactId><version>1.0</version></dependency>
            </dependencies></project>
        """.trimIndent())
        val options = VersionCheckerSettings.Options(deprecatedDependencies = "old:library = Use new:library")
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!,
            MavenProject(file.virtualFile), emptyMap(), options)
        val notice = analysis.problems(file).single()
        assertEquals(DependencySeverity.ERROR, notice.severity)
        assertTrue(notice.message.contains("marked deprecated: Use new:library"))
        assertNull(notice.latest)
        assertNull(notice.target)
        val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.single().contains("Use new:library"))
    }

    fun testPluginRelocationDoesNotTreatUnchangedCoordinatesAsRelocated() {
        val plugin = coordinate.copy(artifactKind = MavenArtifactKind.PLUGIN)
        assertNull(MavenRelocation.parse(JDOMUtil.load(StringReader("<project><distributionManagement><relocation/></distributionManagement></project>")), plugin))
        assertEquals("new:library:1.0", MavenRelocation.parse(JDOMUtil.load(StringReader("<project><distributionManagement><relocation><groupId>new</groupId></relocation></distributionManagement></project>")), plugin))
    }

    fun testRelocatedDependencyNeedsCoordinateReviewEvenWithVersionUpdate() {
        val file = myFixture.configureByText("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>demo</groupId><artifactId>app</artifactId><version>1</version><dependencies>
            <dependency><groupId>old</groupId><artifactId>library</artifactId><version>1.0</version></dependency>
            </dependencies></project>
        """.trimIndent())
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!,
            MavenProject(file.virtualFile), mapOf(coordinate to "2.0"), relocations = mapOf(coordinate to "new:library:1.0"))
        val notice = analysis.problems(file).single()
        assertEquals(DependencyChangeKind.DEPRECATED, notice.kind)
        assertTrue(notice.message.contains("relocated to new:library:1.0"))
        assertNull(notice.target)
        assertTrue(MavenBulkUpdatePlan.create(mapOf(file to analysis)).changes.isEmpty())
    }
}
