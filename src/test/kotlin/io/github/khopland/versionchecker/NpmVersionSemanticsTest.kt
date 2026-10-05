package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.npm.*
import io.github.khopland.versionchecker.core.VersionChangeKind
import org.junit.Assert.*
import org.junit.Test

class NpmVersionSemanticsTest {
    @Test fun preservesExactCaretTildeAndScopedAliasSelectors() {
        for ((selector, expected) in listOf("1.2.3" to "1.2.9", "^1.2.3" to "^1.2.9", "~1.2.3" to "~1.2.9",
            "npm:@scope/pkg@^1.2.3" to "npm:@scope/pkg@^1.2.9")) {
            val parsed = NpmSelector.parse("alias", selector)!!
            assertEquals(expected, parsed.replace("1.2.9"))
            assertEquals(if (selector.startsWith("npm:")) "@scope/pkg" else "alias", parsed.packageName)
        }
    }
    @Test fun unsupportedSelectorsAreNeverRewritten() {
        for (selector in listOf("*", "latest", "workspace:*", "file:../pkg", "git+https://example.test/repo.git", ">=1.0.0 <2", "1.x", "1.2", "^1.0.0-beta.1", "npm:other@latest", "^01.2.3")) {
            assertNull(selector, NpmSelector.parse("package", selector))
        }
        assertNull(NpmSelector.parse("--registry=bad", "1.0.0"))
    }
    @Test fun eachModeFindsTheNewestEligibleStableVersionWithinItsBranch() {
        val metadata = NpmPackageMetadata(listOf("1.2.3", "1.2.9", "1.9.0", "2.0.0", "3.0.0-beta.1", "4.0.0"), "2.0.0")
        val baseline = NpmVersion.parse("1.2.3")!!
        assertEquals("1.2.9", NpmRegistry.eligible(metadata, baseline, UpdateMode.PATCH).first())
        assertEquals("1.9.0", NpmRegistry.eligible(metadata, baseline, UpdateMode.MINOR).first())
        assertEquals("2.0.0", NpmRegistry.eligible(metadata, baseline, UpdateMode.MAJOR).first())
        assertTrue(NpmRegistry.eligible(metadata, NpmVersion.parse("4.0.0")!!, UpdateMode.MAJOR).isEmpty())
    }
    @Test fun zeroMajorModesAreNumericRatherThanCaretCompatibilityRules() {
        val baseline = NpmVersion.parse("0.2.1")!!
        assertFalse(baseline.allows(NpmVersion.parse("0.3.0")!!, UpdateMode.PATCH))
        assertTrue(baseline.allows(NpmVersion.parse("0.3.0")!!, UpdateMode.MINOR))
        assertEquals(VersionChangeKind.MINOR, baseline.change(NpmVersion.parse("0.3.0")!!))
        assertEquals(0, baseline.compareTo(NpmVersion.parse("0.2.1+build.10")!!))
    }
    @Test fun handlesRegistryMetadataWithOnlyOnePublishedVersion() {
        assertEquals(listOf("1.0.0"), NpmRegistry.parseMetadata("""{"versions":"1.0.0","dist-tags":{"latest":"1.0.0"}}""").versions)
    }
}
