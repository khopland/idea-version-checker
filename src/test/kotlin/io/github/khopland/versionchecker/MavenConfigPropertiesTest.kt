package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MavenConfigPropertiesTest {
    @Test fun `preserves quoted and separate properties without confusing other options`() {
        assertEquals(mapOf("name" to "two words", "flag" to "true", "token" to "a=b", "other" to "yes"),
            MavenConfigProperties.parse("""
                # ignored
                -s settings.xml -Dname="two words" -Dflag
                -Dtoken=a=b -D other=yes
            """.trimIndent()))
    }
    @Test fun `uses the nearest Maven root for submodules`() {
        val root = Files.createTempDirectory("maven-config-test-")
        try {
            Files.createDirectory(root.resolve(".mvn"))
            Files.writeString(root.resolve(".mvn/jvm.config"), "-Dvalue=jvm")
            Files.writeString(root.resolve(".mvn/maven.config"), "-Dvalue=maven -Droot=true")
            val child = Files.createDirectories(root.resolve("child/nested"))
            assertEquals(mapOf("value" to "maven", "root" to "true"), MavenConfigProperties.read(child))
        } finally { root.toFile().deleteRecursively() }
    }
}
