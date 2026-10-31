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
            "1.0-preview", "21-ea", "LATEST", "[1.0,2.0)", "1.0.0-next.4", "2.0-rc")
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
