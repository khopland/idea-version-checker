package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*

import org.junit.Assert.*
import org.junit.Test

class UpdateModeTest {
    @Test fun `patch keeps the major and minor segments`() {
        assertTrue(MavenVersionSemantics.allows(UpdateMode.PATCH, "1.2.3", "1.2.10"))
        assertFalse(MavenVersionSemantics.allows(UpdateMode.PATCH, "1.2.3", "1.3.0"))
        assertFalse(MavenVersionSemantics.allows(UpdateMode.PATCH, "1.2.3", "2.2.4"))
        assertTrue(MavenVersionSemantics.allows(UpdateMode.PATCH, "1.2", "1.2.1"))
    }
    @Test fun `minor keeps the major segment`() {
        assertTrue(MavenVersionSemantics.allows(UpdateMode.MINOR, "1.2.3", "1.10.0"))
        assertFalse(MavenVersionSemantics.allows(UpdateMode.MINOR, "1.2.3", "2.0.0"))
        assertTrue(MavenVersionSemantics.allows(UpdateMode.MAJOR, "1.2.3", "2.0.0"))
    }
    @Test fun `restricted updates require numeric segments`() {
        assertFalse(MavenVersionSemantics.allows(UpdateMode.PATCH, "release-one", "release-two"))
        assertFalse(MavenVersionSemantics.allows(UpdateMode.MINOR, "release-one", "2.0"))
        assertTrue(MavenVersionSemantics.allows(UpdateMode.PATCH, "1.2.3.Final", "1.2.4.Final"))
    }
}
