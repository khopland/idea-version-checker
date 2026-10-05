package io.github.khopland.versionchecker

import org.junit.Assert.*
import org.junit.Test

class UpdateModeTest {
    @Test fun `patch keeps the major and minor segments`() {
        assertTrue(UpdateMode.PATCH.allows("1.2.3", "1.2.10"))
        assertFalse(UpdateMode.PATCH.allows("1.2.3", "1.3.0"))
        assertFalse(UpdateMode.PATCH.allows("1.2.3", "2.2.4"))
        assertTrue(UpdateMode.PATCH.allows("1.2", "1.2.1"))
    }
    @Test fun `minor keeps the major segment`() {
        assertTrue(UpdateMode.MINOR.allows("1.2.3", "1.10.0"))
        assertFalse(UpdateMode.MINOR.allows("1.2.3", "2.0.0"))
        assertTrue(UpdateMode.MAJOR.allows("1.2.3", "2.0.0"))
    }
    @Test fun `restricted updates require numeric segments`() {
        assertFalse(UpdateMode.PATCH.allows("release-one", "release-two"))
        assertFalse(UpdateMode.MINOR.allows("release-one", "2.0"))
        assertTrue(UpdateMode.PATCH.allows("1.2.3.Final", "1.2.4.Final"))
    }
}
