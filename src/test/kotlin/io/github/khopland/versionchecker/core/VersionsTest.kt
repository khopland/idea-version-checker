package io.github.khopland.versionchecker.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    @Test
    fun stableVersions() {
        listOf("1.0", "4.13.2", "33.0.0-jre", "5.6.15.Final", "5.2.9.RELEASE", "1.0.GA", "20030203.000550", "v2.1.0", "33.0-android")
            .forEach { assertTrue(it, Versions.isStable(it)) }
        listOf("1.0-SNAPSHOT", "2.0.0-M1", "1.0-RC1", "1.0.0.CR1", "2.1.0-Beta1", "1.0.0-alpha.3", "3.0-b2", "5.0.0-dev.1",
            "1.0-preview", "21-ea", "LATEST", "1.0.0.Beta.1", "1.0.0.Beta1", "1.0.0-beta-1", "1.0.0.Alpha.2", "2.0.0.M1",
            "1.0.0.RC.1", "1.0.0-RC1", "3.0.0.CR2", "1.0.0-preview.3", "1.0.0-SNAPSHOT", "2.0.0-alpha01", "1.0.0-beta.1+build.5", "[1.0,2.0)", "1.0.0-next.4", "2.0-rc")
            .forEach { assertFalse(it, Versions.isStable(it)) }
    }

    @Test
    fun ordering() {
        assertTrue(Versions.compare("1.10", "1.9") > 0)
        assertTrue(Versions.compare("1.0.1", "1.0") > 0)
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
        assertTrue(Versions.compare("5.3.39", "5.2.9.RELEASE") > 0)
        assertTrue(Versions.compare("33.1.0-jre", "33.0.0-jre") > 0)
    }

    @Test
    fun flavorKeepsVariants() {
        assertEquals("jre", Versions.flavor("33.0.0-jre"))
        assertEquals("", Versions.flavor("5.6.15.Final"))
        assertEquals("", Versions.flavor("5.2.9.RELEASE"))
        assertEquals(listOf("33.1.0-jre"), Versions.candidates("33.0.0-jre", listOf("33.1.0-android", "33.1.0-jre", "32.0.0-jre")))
        assertEquals(listOf("6.0.0", "5.3.39"), Versions.candidates("5.2.9.RELEASE", listOf("5.3.39", "6.0.0", "5.2.1.RELEASE")))
    }

    @Test
    fun kinds() {
        assertEquals(UpdateKind.PATCH, Versions.kind("1.2.3", "1.2.9"))
        assertEquals(UpdateKind.MINOR, Versions.kind("1.2.3", "1.3.0"))
        assertEquals(UpdateKind.MAJOR, Versions.kind("1.2.3", "2.0.0"))
        assertEquals(UpdateKind.PATCH, Versions.kind("1.2", "1.2.1"))
    }

    @Test
    fun prereleasesAreOfferedTheirFinalRelease() = runBlocking {
        val maven = listOf("3.9.9", "3.9.11", "4.0.0-rc-4", "4.0.0-rc-5", "4.0.0", "4.0.1")
        assertEquals(Targets("4.0.1", "4.0.1", "4.0.1"), Targets.pick("4.0.0-rc-5", maven) { true })
        assertEquals(Targets("3.9.11", "3.9.11", "4.0.1"), Targets.pick("3.9.9", maven) { true })
        assertEquals(Targets("2.0.0", "2.1.0", "2.1.0"), Targets.pick("2.0.0-M1", listOf("2.0.0-M1", "2.0.0-M2", "2.0.0", "2.1.0")) { true })
        assertEquals("2.0.0", Targets.pick("2.0.0-alpha.3", listOf("2.0.0-beta.1", "2.0.0")) { true }.patch)
        assertEquals("6.0.0.Final", Targets.pick("6.0.0.Beta1", listOf("6.0.0.CR1", "6.0.0.Final")) { true }.patch)
        assertEquals("2.1.0", Targets.pick("2.1.0-Beta1", listOf("2.1.0-RC", "2.1.0", "2.1.20-Beta1")) { true }.patch)
        assertEquals("1.0.0.Final", Targets.pick("1.0.0.Beta.1", listOf("1.0.0.Beta.2", "1.0.0.CR1", "1.0.0.Final")) { true }.patch)
        assertTrue(Versions.compare("1.0.0.Beta.2", "1.0.0.Beta.1") > 0)
        // Apache incubator releases are real releases with an `-incubating` variant.
        assertTrue(Versions.isStable("1.0.0-incubating"))
        assertEquals(listOf("1.1.0-incubating"), Versions.candidates("1.0.0-incubating", listOf("1.1.0-incubating", "1.1.0")))
        // Prereleases sort before their release and by marker.
        assertTrue(Versions.compare("4.0.0-rc-5", "4.0.0-rc-4") > 0)
        assertTrue(Versions.compare("4.0.0", "4.0.0-rc-5") > 0)
        assertTrue(Versions.compare("2.0.0-beta", "2.0.0-alpha") > 0)
    }

    @Test
    fun variantsWithVersionsStayOnTheirVariant() = runBlocking {
        // Spock publishes one build per Groovy version.
        val spock = listOf("2.3-groovy-3.0", "2.3-groovy-4.0", "2.4-M4-groovy-3.0", "2.4-M4-groovy-4.0", "2.4-groovy-3.0", "2.4-groovy-4.0")
        assertEquals("groovy.4.0", Versions.flavor("2.3-groovy-4.0"))
        assertEquals("groovy.4.0", Versions.flavor("2.4-M4-groovy-4.0"))
        assertFalse(Versions.isStable("2.4-M4-groovy-4.0"))
        assertEquals(Targets(null, "2.4-groovy-3.0", "2.4-groovy-3.0"), Targets.pick("2.3-groovy-3.0", spock) { true })
        assertEquals(Targets(null, "2.4-groovy-4.0", "2.4-groovy-4.0"), Targets.pick("2.3-groovy-4.0", spock) { true })
        assertEquals("2.4-groovy-4.0", Targets.pick("2.4-M4-groovy-4.0", spock) { true }.patch)
        assertEquals(Targets.NONE, Targets.pick("2.4-groovy-3.0", spock) { true })
        // Other variants keep their name and number.
        assertEquals(listOf("1.79-jdk8"), Versions.candidates("1.78-jdk8", listOf("1.79-jdk8", "1.79-jdk11", "1.79")))
    }

    @Test
    fun versionsThatAreNotSemver() = runBlocking {
        // Two parts: the second part is the minor version.
        assertEquals(Targets("1.2.1", "1.3", "2.0"), Targets.pick("1.2", listOf("1.2.1", "1.3", "2.0")) { true })
        // Four parts: everything after the minor version is a patch.
        assertEquals(Targets("5.4.3.2", "5.5.0.0", "5.5.0.0"), Targets.pick("5.4.3.1", listOf("5.4.3.2", "5.5.0.0")) { true })
        // Date versions are one number, so any change is major.
        assertEquals(Targets(null, null, "20240303"), Targets.pick("20231013", listOf("20240303", "20231013")) { true })
        // Calendar versions: the year is the major version.
        assertEquals(Targets("2024.1.2", "2024.2.0", "2025.1.0"), Targets.pick("2024.1.0", listOf("2024.1.2", "2024.2.0", "2025.1.0")) { true })
        // Build numbers after the version, as webjars use.
        assertEquals("1.2.3-2", Targets.pick("1.2.3-1", listOf("1.2.3-1", "1.2.3-2")) { true }.patch)
        // Old Spring names with .RELEASE update to plain versions.
        assertEquals("5.3.39", Targets.pick("5.2.9.RELEASE", listOf("5.2.10.RELEASE", "5.3.39")) { true }.minor)
        // Versions that do not start with a number are never suggested.
        assertFalse(Versions.isStable("r09"))
    }

    @Test
    fun targetsPerMode() = runBlocking {
        val versions = listOf("4.12", "4.12.1", "4.13", "4.13.2", "5.0.0", "5.1.0-M1")
        val targets = Targets.pick("4.12", versions) { true }
        assertEquals(Targets("4.12.1", "4.13.2", "5.0.0"), targets)
        assertEquals(Targets.NONE, Targets.pick("5.0.0", versions) { true })
    }

    @Test
    fun quarantineSkipsRecentVersionsAndDatesEachVersionOnce() = runBlocking {
        val asked = mutableListOf<String>()
        val recent = setOf("5.0.0", "4.13.2")
        val targets = Targets.pick("4.12", listOf("4.12.1", "4.13", "4.13.2", "5.0.0")) { asked += it; it !in recent }
        assertEquals(Targets("4.12.1", "4.13", "4.13"), targets)
        assertEquals(listOf("4.12.1", "4.13.2", "4.13", "5.0.0"), asked) // each version is dated once
    }

    @Test
    fun sharedEditsNeedAgreement() {
        val file = com.intellij.testFramework.LightVirtualFile("pom.xml")
        val edit = Edit(file, com.intellij.openapi.util.TextRange(10, 14), "4.12")
        val lookup = object : Lookup {
            override val key = "k"
            override suspend fun fetch() = Releases(emptyList())
        }
        val a = Declaration("a", edit.range, "4.12", lookup, edit)
        val b = Declaration("b", edit.range, "4.12", lookup, edit)
        val fixes = VersionService.fixes(mapOf(
            a to Outcome.Updates(Targets("4.12.1", "4.13", "5.0"), null),
            b to Outcome.Updates(Targets("4.12.1", "4.13", "4.13"), null)))
        assertEquals(mapOf(UpdateKind.PATCH to "4.12.1", UpdateKind.MINOR to "4.13"), fixes[a])
    }
}
