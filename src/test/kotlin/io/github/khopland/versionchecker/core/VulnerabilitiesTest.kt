package io.github.khopland.versionchecker.core

import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VulnerabilitiesTest {
    private fun advisory(id: String, severity: String?, vararg ranges: AffectedRange, cve: String? = null, summary: String? = null) =
        Advisory(id, listOfNotNull(cve), severity, summary, ranges.mapNotNull { it.fixed }, ranges.toList())

    private val log4shell = advisory("GHSA-jfh8-c2jp-5v3q", "CRITICAL", AffectedRange("2.13.0", "2.15.0"), AffectedRange("2.4", "2.12.2"),
        cve = "CVE-2021-44228", summary = "Remote code injection in Log4j.")
    private val followUp = advisory("GHSA-8489-44mv-ggj8", "MODERATE", AffectedRange("2.13.0", "2.17.1"), AffectedRange("2.4", "2.12.4"), cve = "CVE-2021-44832")
    private val log4jVersions = listOf("2.12.1", "2.12.2", "2.12.4", "2.14.1", "2.15.0", "2.16.0", "2.17.0", "2.17.1", "2.18.0", "3.0.0-beta1")

    @Test
    fun nearestSafeVersionFixesEveryAdvisory() = runBlocking {
        assertEquals("2.15.0", Vulnerabilities.nearestSafe("2.14.1", log4jVersions, listOf(log4shell)))
        assertEquals("2.17.1", Vulnerabilities.nearestSafe("2.14.1", log4jVersions, listOf(log4shell, followUp)))
        // The fixed release on the same branch counts, even though newer branches had fixes too.
        assertEquals("2.12.4", Vulnerabilities.nearestSafe("2.12.1", log4jVersions, listOf(log4shell, followUp)))
        assertEquals("2.18.0", Vulnerabilities.nearestSafe("2.14.1", log4jVersions, listOf(log4shell, followUp)) { it != "2.17.1" })
        assertNull(Vulnerabilities.nearestSafe("2.18.0", log4jVersions, listOf(advisory("X", null, AffectedRange("0")))))
    }

    @Test
    fun nearestSafeVersionKeepsTheVariant() = runBlocking {
        val guava = Advisory("GHSA-7g45-4rm6-3mm3", listOf("CVE-2023-2976"), "MODERATE", null, listOf("32.0.0"))
        assertEquals("32.0.0-jre", Vulnerabilities.nearestSafe("31.1-jre", listOf("31.1-android", "32.0.0-android", "32.0.0-jre", "32.0.1-jre"), listOf(guava)))
    }

    @Test
    fun withoutRangesEveryVersionBelowAFixIsAffected() {
        val advisory = Advisory("X", emptyList(), null, null, listOf("1.2.5", "2.0.1"))
        assertEquals(listOf(true, true, false), listOf("1.2.4", "1.2.6", "2.0.1").map(advisory::affects))
        assertTrue(Advisory("Y", emptyList(), null, null, emptyList()).affects("9.9"))
    }

    @Test
    fun messages() {
        val declaration = Declaration("org.apache.logging.log4j:log4j-core", TextRange(0, 1), "2.14.1", object : Lookup {
            override val key = "k"
            override suspend fun fetch() = Releases(emptyList())
        }, null)
        assertEquals("CVE-2021-44228 (critical): Remote code injection in Log4j. Fixed in 2.15.0",
            VulnerabilityAnnotator.message(declaration, Vulnerable(listOf(log4shell), null)))
        assertEquals("CVE-2021-44228, CVE-2021-44832 (critical). Fixed in 2.17.1",
            VulnerabilityAnnotator.message(declaration, Vulnerable(listOf(followUp, log4shell), "2.17.1")))
        assertEquals("GHSA-x (low)", VulnerabilityAnnotator.message(declaration, Vulnerable(listOf(advisory("GHSA-x", "LOW", AffectedRange("0"))), null)))
        assertEquals("GHSA-y", VulnerabilityAnnotator.message(declaration, Vulnerable(listOf(advisory("GHSA-y", "UNKNOWN", AffectedRange("0"))), null)))
        val many = (1..7).map { advisory("GHSA-$it", "LOW", AffectedRange("0", "3.0")) }
        assertEquals("GHSA-1, GHSA-2, GHSA-3, GHSA-4, GHSA-5 and 2 more (low). Fixed in 3.0", VulnerabilityAnnotator.message(declaration, Vulnerable(many, null)))
    }
}
