package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import org.junit.Assert.*
import org.junit.Test

class DependencyUpdateReportTest {
    @Test fun `parses direct and managed dependencies and keeps current version in the key`() {
        val updates = DependencyUpdateReport.parse("""
            The following dependencies in Dependencies have newer versions:
              org.example:library ........ 1.9 -> 1.10
            The following dependencies in Dependency Management have newer versions:
              org.example:library ........ 2.0 -> 3.0
              org.example:bom ............ 1.0 -> 2.0
        """.trimIndent())
        assertEquals("1.10", updates[DependencyVersion("org.example", "library", "1.9")])
        assertEquals("3.0", updates[DependencyVersion("org.example", "library", "2.0")])
        assertEquals("2.0", updates[DependencyVersion("org.example", "bom", "1.0")])
        assertNull(updates[DependencyVersion("org.example", "library", "1.8")])
    }

    @Test fun `parses wrapped long coordinates`() {
        assertEquals(mapOf(DependencyVersion("org.example", "long-artifact", "1.0") to "2.0"),
            DependencyUpdateReport.parse("  org.example:long-artifact ...\n      1.0 -> 2.0\n"))
    }

    @Test fun `ignores snapshots dynamic versions and prereleases`() {
        val lines = listOf("2.0-RC1", "2.0-rc.1", "2.0-alpha2", "2.0-beta", "2.0-M1", "2.0-milestone-1", "2.0-ea", "2.0-preview.2", "2.0-SNAPSHOT")
        for (latest in lines) assertTrue(latest, DependencyUpdateReport.parse("  g:a ... 1.0 -> $latest").isEmpty())
        for (current in listOf("1.0-SNAPSHOT", "[1.0,2.0)", "LATEST", "RELEASE", "\${version}")) {
            assertTrue(current, DependencyUpdateReport.parse("  g:a ... $current -> 3.0").isEmpty())
        }
    }

    @Test fun `accepts stable qualifiers and four part versions`() {
        for (latest in listOf("2.0.Final", "2.0.GA", "2.0.0.1", "2.0-jre", "2.0-redhat-00001")) {
            assertEquals(latest, DependencyUpdateReport.parse("  g:a ... 1.0 -> $latest")[DependencyVersion("g", "a", "1.0")])
        }
    }

    @Test fun `ignores headings unchanged versions and malformed lines`() {
        assertTrue(DependencyUpdateReport.parse("""
            The following dependencies have newer versions:
            g:a ... 1.0 -> 1.0
            g:a ...
            No dependencies in Dependencies have newer versions.
            1.0 -> 2.0
            g:a ... 1.0 ->
        """.trimIndent()).isEmpty())
    }

    @Test fun `parses parent updates with and without padding dots`() {
        val parent = DependencyVersion("no.example.felles.infrastructure", "infrastructure", "5.40.0", MavenArtifactKind.PARENT)
        assertEquals("5.41.0", DependencyUpdateReport.parseParent("""
            The parent project has a newer version:
              no.example.felles.infrastructure:infrastructure ........ 5.40.0 -> 5.41.0
        """.trimIndent(), parent))
        val long = parent.copy(groupId = "no.skatteetaten.fastsetting.formueinntekt.felles.infrastructure")
        assertEquals("5.41.0", DependencyUpdateReport.parseParent(
            "  ${long.groupId}:infrastructure  5.40.0 -> 5.41.0", long))
        assertNull(DependencyUpdateReport.parseParent("The parent project is the latest version:\n  ${parent.groupId}:infrastructure ... 5.40.0", parent))
        assertNull(DependencyUpdateReport.parseParent("  ${parent.groupId}:infrastructure ... 5.40.0 -> 6.0.0-RC1", parent))
        assertNull(DependencyUpdateReport.parseParent("  ${parent.groupId}:other ... 5.40.0 -> 5.41.0", parent))
    }
}
