package io.github.khopland.versionchecker.maven

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.Change
import io.github.khopland.versionchecker.core.Edits
import io.github.khopland.versionchecker.repo.MavenLookup

class MavenProviderTest : BasePlatformTestCase() {
    private val pom = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
          <properties>
            <junit.version>4.12</junit.version>
            <alias.version>${'$'}{junit.version}</alias.version>
          </properties>
          <dependencies>
            <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>${'$'}{junit.version}</version></dependency>
            <dependency><groupId>org.hamcrest</groupId><artifactId>hamcrest</artifactId><version>${'$'}{alias.version}</version></dependency>
            <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version> 33.0.0-jre </version></dependency>
            <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>[1.7,2.0)</version></dependency>
            <dependency><groupId>org.managed</groupId><artifactId>managed</artifactId></dependency>
          </dependencies>
          <build><plugins>
            <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.0.0</version></plugin>
          </plugins></build>
          <profiles><profile><id>p</id><dependencies>
            <dependency><groupId>org.p</groupId><artifactId>p</artifactId><version>1.0</version></dependency>
          </dependencies></profile></profiles>
        </project>
    """.trimIndent()

    fun testCollectsLiteralAndPropertyVersions() {
        val file = myFixture.configureByText("pom.xml", pom)
        val declarations = MavenProvider().collect(file)
        assertEquals(listOf("junit:junit", "org.hamcrest:hamcrest", "com.google.guava:guava", "org.apache.maven.plugins:maven-surefire-plugin", "org.p:p"),
            declarations.map { it.name })
        assertEquals(listOf("4.12", "4.12", "33.0.0-jre", "3.0.0", "1.0"), declarations.map { it.current })
        val text = file.text
        assertEquals("\${junit.version}", declarations[0].range.substring(text))
        assertEquals("33.0.0-jre", declarations[2].range.substring(text))
        // Both property users edit the literal definition.
        assertEquals("4.12", declarations[0].edit!!.range.substring(text))
        assertEquals(declarations[0].edit!!.key, declarations[1].edit!!.key)
        assertEquals("junit.version", declarations[1].edit!!.label)
        assertEquals("org.apache.maven.plugins:maven-surefire-plugin", (declarations[3].lookup as MavenLookup).let { "${it.groupId}:${it.artifactId}" })
    }

    fun testEditsApplyInOneCommandAndSkipStaleText() {
        val file = myFixture.configureByText("pom.xml", pom)
        val declarations = MavenProvider().collect(file)
        val junit = declarations[0].edit!!
        val guava = declarations[2].edit!!
        val applied = Edits.apply(project, listOf(Change(junit, "4.13.2"), Change(guava, "33.4.0-jre"),
            Change(io.github.khopland.versionchecker.core.Edit(guava.file, guava.range, "stale"), "x")), "Update")
        assertEquals(2, applied.size)
        assertTrue(myFixture.editor.document.text.contains("<junit.version>4.13.2</junit.version>"))
        assertTrue(myFixture.editor.document.text.contains("<version> 33.4.0-jre </version>"))
    }
}
