package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.VersionChangeKind

import io.github.khopland.versionchecker.maven.*

import org.junit.Assert.*
import org.junit.Test

class VersionSeverityTest {
    @Test fun `classifies numeric and qualified Maven versions`() {
        assertEquals(VersionChangeKind.PATCH, MavenVersionSemantics.between("32.1.1-jre", "32.1.3-jre"))
        assertEquals(VersionChangeKind.MINOR, MavenVersionSemantics.between("4.12", "4.13.2"))
        assertEquals(VersionChangeKind.MAJOR, MavenVersionSemantics.between("1.7.36", "2.0.20"))
        assertEquals(VersionChangeKind.OTHER, MavenVersionSemantics.between("RELEASE_1", "RELEASE_2"))
    }

    @Test fun `updates use the inspection severity and deprecations are struck through`() {
        VersionChangeKind.entries.filter { it != VersionChangeKind.DEPRECATED }.forEach {
            assertEquals(com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING, it.highlightType())
        }
        assertEquals(com.intellij.codeInspection.ProblemHighlightType.LIKE_DEPRECATED, VersionChangeKind.DEPRECATED.highlightType())
    }

    @Test fun `deprecation policy only accepts explicit coordinates`() {
        assertEquals(mapOf("g:a" to "Use g:b instead", "old:library" to "Deprecated by project policy"),
            deprecatedDependencies("# Policy\ng:a = Use g:b instead\nold:library\ninvalid\ng:a:1.0 = invalid"))
    }
}
