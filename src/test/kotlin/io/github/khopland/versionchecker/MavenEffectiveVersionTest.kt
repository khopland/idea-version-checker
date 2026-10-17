package io.github.khopland.versionchecker

import org.junit.Assert.*
import org.junit.Test

class MavenEffectiveVersionTest {
    private val target = MavenVerificationTarget("/child/pom.xml", "org.example", "library", "DEPENDENCY", "2.0", 7)
    @Test fun `declaration edits and existing model cannot confirm an update`() {
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target, null, true, setOf("2.0")).status)
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target, 7, true, setOf("2.0")).status)
    }
    @Test fun `subsequent native resolution must have unchanged inputs and one expected version`() {
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target, 8, false, setOf("2.0")).status)
        assertEquals(EffectiveVersionStatus.CONFIRMED, compareEffectiveVersion(target, 8, true, setOf("2.0")).status)
        assertEquals(EffectiveVersionStatus.MISMATCH, compareEffectiveVersion(target, 8, true, setOf("1.0")).status)
        assertEquals(EffectiveVersionStatus.MISMATCH, compareEffectiveVersion(target, 8, true, setOf("1.0", "2.0")).status)
    }
    @Test fun `missing or failed dependency resolution and unsupported consumers remain unknown`() {
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target, 8, true, emptySet()).status)
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target, 8, true, null).status)
        assertEquals(EffectiveVersionStatus.UNKNOWN, compareEffectiveVersion(target.copy(kind = "PLUGIN"), 8, true, setOf("2.0")).status)
    }
}
