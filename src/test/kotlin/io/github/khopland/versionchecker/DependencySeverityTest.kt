package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import org.junit.Assert.*
import org.junit.Test

class DependencySeverityTest {
    @Test fun `classifies numeric and qualified Maven versions`() {
        assertEquals(DependencyChangeKind.PATCH, MavenVersionSemantics.between("32.1.1-jre", "32.1.3-jre"))
        assertEquals(DependencyChangeKind.MINOR, MavenVersionSemantics.between("4.12", "4.13.2"))
        assertEquals(DependencyChangeKind.MAJOR, MavenVersionSemantics.between("1.7.36", "2.0.20"))
        assertEquals(DependencyChangeKind.OTHER, MavenVersionSemantics.between("RELEASE_1", "RELEASE_2"))
    }

    @Test fun `updates default to warnings and explicit notices to errors`() {
        val options = VersionCheckerSettings.Options()
        DependencyChangeKind.entries.filter { it != DependencyChangeKind.DEPRECATED }.forEach {
            assertEquals(DependencySeverity.WARNING, it.severity(options))
        }
        assertEquals(DependencySeverity.ERROR, DependencyChangeKind.DEPRECATED.severity(options))
        options.majorSeverity = DependencySeverity.INFORMATION
        options.patchSeverity = DependencySeverity.DISABLED
        assertEquals(DependencySeverity.INFORMATION, DependencyChangeKind.MAJOR.severity(options))
        assertNull(DependencyChangeKind.PATCH.severity(options).highlight)
    }

    @Test fun `deprecation policy only accepts explicit coordinates`() {
        assertEquals(mapOf("g:a" to "Use g:b instead", "old:library" to "Deprecated by project policy"),
            deprecatedDependencies("# Policy\ng:a = Use g:b instead\nold:library\ninvalid\ng:a:1.0 = invalid"))
    }
}
