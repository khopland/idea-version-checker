package io.github.khopland.versionchecker.vuln

import org.junit.Assert.*
import org.junit.Test

/** Scores from FIRST's CVSS v4.0 examples, https://www.first.org/cvss/v4.0/examples, which the reference calculator agrees with. */
class Cvss4Test {
    private fun assertScore(expected: Double, metrics: String) = assertEquals(metrics, expected, Cvss.score("CVSS:4.0/$metrics")!!, 0.0)

    @Test
    fun baseScores() {
        assertScore(9.3, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N")
        assertScore(9.4, "AV:L/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H")
        assertScore(8.7, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N")
        assertScore(7.8, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:L/VA:N/SC:L/SI:N/SA:H")
        assertScore(7.3, "AV:L/AC:L/AT:P/PR:L/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N")
        assertScore(7.1, "AV:N/AC:L/AT:N/PR:L/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N")
        assertScore(6.4, "AV:A/AC:L/AT:N/PR:N/UI:N/VC:N/VI:L/VA:N/SC:H/SI:N/SA:H")
        assertScore(5.9, "AV:L/AC:L/AT:N/PR:H/UI:N/VC:N/VI:N/VA:N/SC:H/SI:N/SA:N")
        assertScore(5.4, "AV:P/AC:L/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N")
        assertScore(5.1, "AV:N/AC:L/AT:N/PR:N/UI:A/VC:N/VI:L/VA:N/SC:N/SI:N/SA:N")
        assertScore(4.6, "AV:L/AC:L/AT:N/PR:N/UI:A/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N")
        // Supplemental metrics do not change the score.
        assertScore(9.4, "AV:N/AC:L/AT:N/PR:L/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H/S:P/AU:Y/V:C/RE:L")
    }

    @Test
    fun threatMetrics() {
        // E:A is the default, so it changes nothing; E:P and E:U lower the score.
        assertScore(8.7, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N/E:A")
        assertScore(9.2, "AV:N/AC:L/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:A")
        assertScore(8.2, "AV:N/AC:H/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:P")
        assertScore(5.2, "AV:N/AC:L/AT:P/PR:N/UI:P/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:U")
        assertScore(5.6, "AV:L/AC:L/AT:N/PR:H/UI:N/VC:H/VI:H/VA:N/SC:N/SI:N/SA:N/E:U")
    }

    @Test
    fun environmentalMetrics() {
        assertScore(8.1, "AV:N/AC:L/AT:P/PR:N/UI:N/VC:H/VI:L/VA:L/SC:N/SI:N/SA:N/CR:H/IR:L/AR:L/MAV:N/MAC:H/MVC:H/MVI:L/MVA:L")
        assertScore(5.5, "AV:N/AC:H/AT:P/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:P/MAC:L/MAT:N/MVC:N/MVI:N/MVA:L")
        assertScore(5.7, "AV:L/AC:L/AT:N/PR:N/UI:P/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:U/MUI:A")
        assertScore(8.9, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H/E:A/CR:H/IR:H/AR:H/MAV:L/MAT:P")
        assertScore(9.7, "AV:A/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:N/SC:N/SI:N/SA:N/MSI:S/S:P")
        assertScore(7.0, "AV:N/AC:L/AT:N/PR:L/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H/E:P/CR:L/IR:H/AR:L/MAV:L/MAC:H/MAT:N/MPR:N/MUI:N/MVC:N/MVI:H/MVA:L/MSC:N/MSI:S/MSA:L")
        assertScore(2.7, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N/E:U/CR:L/IR:L/AR:L")
        assertScore(2.4, "AV:N/AC:L/AT:N/PR:L/UI:N/VC:N/VI:N/VA:N/SC:H/SI:L/SA:H/E:U/MAV:A/R:U/V:C")
        // Modified impacts of None leave no impact at all.
        assertScore(0.0, "AV:L/AC:L/AT:N/PR:L/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:A/MVC:N/MVI:N/MVA:N")
        // Explicit X values are the same as leaving the metrics out.
        assertScore(9.3, "AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:X/CR:X/IR:X/AR:X/MAV:X/MSI:X/U:X")
    }

    @Test
    fun ratings() {
        assertEquals("CRITICAL", Cvss.rating("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
        assertEquals("HIGH", Cvss.rating("CVSS:4.0/AV:N/AC:L/AT:N/PR:L/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N"))
        assertEquals("MODERATE", Cvss.rating("CVSS:4.0/AV:L/AC:L/AT:N/PR:N/UI:A/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N"))
        assertEquals("LOW", Cvss.rating("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N/E:U/CR:L/IR:L/AR:L"))
        assertNull(Cvss.rating("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:N/SA:N"))
    }

    @Test
    fun malformedVectorsHaveNoScore() {
        listOf(
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N", // SA is missing
            "CVSS:4.0/AC:L/AV:N/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N", // out of order
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/CR:H/E:A", // out of order
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:A/E:A", // twice
            "CVSS:4.0/AV:X/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N", // base metrics cannot be X
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:S/SA:N", // S is only for MSI and MSA
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/XX:Y",
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/",
            "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N:H",
            "CVSS:4.1/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N",
            "CVSS:4.0/",
            "CVSS:4.0",
        ).forEach { assertNull(it, Cvss.score(it)) }
    }
}
