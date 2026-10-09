package io.github.khopland.versionchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.maven.DependencyVersion
import io.github.khopland.versionchecker.maven.MavenBulkUpdatePlan
import io.github.khopland.versionchecker.maven.MavenDependencyAnalysis
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProject
import kotlin.system.measureNanoTime

/** Opt-in headless plan preparation, excluding native Maven, dialog rendering and writes. */
class MavenPreparationBenchmarkTest : BasePlatformTestCase() {
    fun testSharedPropertyPreviewPreparation() {
        for ((properties, consumers) in listOf(10 to 2, 100 to 2, 500 to 2, 1 to 1_000)) {
            val updates = linkedMapOf<DependencyVersion, String>()
            val text = buildString {
                append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>")
                append("<groupId>example</groupId><artifactId>app</artifactId><version>1</version><properties>")
                repeat(properties) { append("<library.$it>1.2.3</library.$it>") }
                append("</properties><dependencies>")
                repeat(properties) { property ->
                    repeat(consumers) { consumer ->
                        val artifact = "library-$property-$consumer"
                        updates[DependencyVersion("g", artifact, "1.2.3")] = "1.2.9"
                        append("<dependency><groupId>g</groupId><artifactId>$artifact</artifactId>")
                        append("<version>\${library.$property}</version></dependency>")
                    }
                }
                append("</dependencies></project>")
            }
            val file = myFixture.addFileToProject("maven-preparation-$name-$properties-$consumers/pom.xml", text)
            val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!,
                MavenProject(file.virtualFile), updates)
            fun preview(): Long {
                lateinit var plan: BulkUpdatePlan
                val elapsed = measureNanoTime { plan = MavenBulkUpdatePlan.create(mapOf(file to analysis)) }
                assertEquals(properties, plan.changes.size)
                assertTrue(plan.skipped.isEmpty())
                assertEquals((0 until properties).map { "library.$it" }, plan.changes.map { (it.element as com.intellij.psi.xml.XmlTag).localName })
                assertTrue(plan.changes.all { it.expected == "1.2.3" && it.latest == "1.2.9" })
                return elapsed
            }
            repeat(2) { preview() }
            val millis = List(5) { preview() / 1_000_000.0 }
            println("maven-preparation properties=$properties consumersPerProperty=$consumers samplesMs=$millis medianMs=${millis.sorted()[2]} maxMs=${millis.max()}")
        }
    }
}
