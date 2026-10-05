package io.github.khopland.versionchecker

import org.junit.Assert.*
import org.junit.Test

class MavenPluginUpdatesTest {
    private val compiler = DependencyVersion("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0", MavenArtifactKind.PLUGIN)

    @Test fun `parses shortened plugin coordinates and rejects downgrades and incompatible Maven versions`() {
        val report = """
            Require Maven 2.0 to use the following plugin updates:
              maven-compiler-plugin ... 3.12.0 -> 2.0.2
            Require Maven 3.6.3 to use the following plugin updates:
              maven-compiler-plugin ... 3.12.0 -> 3.12.1
              maven-compiler-plugin ... 3.12.0 -> 3.16.0
            Require Maven 4.0.0 to use the following plugin updates:
              maven-compiler-plugin ... 3.12.0 -> 4.0.0
        """.trimIndent()
        assertEquals(mapOf(compiler to "3.16.0"), MavenPluginUpdates.parse(report, listOf(compiler), UpdateMode.MAJOR, "3.9.16"))
        assertEquals(mapOf(compiler to "3.12.1"), MavenPluginUpdates.parse(report, listOf(compiler), UpdateMode.PATCH, "3.9.16"))
    }

    @Test fun `uses explicit plugin coordinates and excludes unpinned and unstable versions`() {
        val plugin = DependencyVersion("custom", "plugin", "1.0", MavenArtifactKind.PLUGIN)
        val report = """
            The following plugin updates are available:
              custom:plugin ... 1.0 -> 1.1
              custom:plugin ... 1.0 -> 2.0-RC1
              custom:plugin ... LATEST -> 2.0
              other:plugin ... 1.0 -> 3.0
        """.trimIndent()
        assertEquals(mapOf(plugin to "1.1"), MavenPluginUpdates.parse(report, listOf(plugin), UpdateMode.MAJOR, "3.9.16"))
        assertFalse(plugin == DependencyVersion("custom", "plugin", "1.0"))
    }

    @Test fun `restricts search before querying rather than discarding a newer major result`() {
        for ((mode, allowed, rejected) in listOf(
            Triple(UpdateMode.PATCH, "3.12.1", "3.13.0"), Triple(UpdateMode.MINOR, "3.16.0", "4.0.0")
        )) {
            val branch = MavenPluginUpdates.branch(compiler.version, mode)!!
            val ignored = Regex(MavenPluginUpdates.ignoredVersions(branch))
            assertFalse(ignored.matches(allowed))
            assertTrue(ignored.matches(rejected))
            assertTrue(ignored.matches("3.12.2-RC1"))
        }
        assertNull(MavenPluginUpdates.branch("custom-version", UpdateMode.PATCH))
        assertEquals("", MavenPluginUpdates.branch("custom-version", UpdateMode.MAJOR))
    }
}
