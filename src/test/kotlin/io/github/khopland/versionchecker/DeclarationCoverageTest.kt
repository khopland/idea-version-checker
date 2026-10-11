package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test

class DeclarationCoverageTest {
    private fun declaration(index: Int) = VersionDeclaration(DeclarationId("package.json", "dependencies/$index"),
        ArtifactId("npm", "package-$index"), "1.0.0", "1.0.0")

    @Test fun `no updates among checked declarations differs from all unsupported and unknown coverage`() {
        val checked = UpdateReport(coverage = (1..24).associate { declaration(it).id to DeclarationCoverage(DeclarationOutcome.CHECKED) })
        val skipped = UpdateReport(coverage = (1..8).associate { declaration(it).id to DeclarationCoverage(DeclarationOutcome.SKIPPED, "Unsupported selector") })
        assertEquals("24 checked", checked.coverageSummary()!!.description)
        assertEquals("0 checked · 8 skipped", skipped.coverageSummary()!!.description)
        assertNull(UpdateReport().coverageSummary())
        assertEquals(CoverageSummary(), UpdateReport(coverage = emptyMap()).coverageSummary())
        val status = VersionCheckStatus(CheckPhase.CHECKED, coverage = skipped.coverageSummary())
        assertEquals("Versions: needs review", status.text)
        assertTrue(status.tooltip.contains("0 checked · 8 skipped"))
        assertFalse(status.tooltip.contains("Checked the supported declarations"))
    }

    @Test fun `ignoring alias targets counts checked declarations without mutating raw report or its age`() {
        val declarations = (1..3).map(::declaration)
        val snapshot = BuildSnapshot(BuildContextId("npm", "/root", "manifest"), "package.json",
            BuildFingerprint(emptyMap(), "npm"), declarations)
        val report = UpdateReport(declarations.map { UpdateCandidate(it, "2.0.0") }, validUntilNanos = 500,
            checkedAtNanos = 100, coverage = declarations.associate { it.id to DeclarationCoverage(DeclarationOutcome.CHECKED) })
        val policy = VersionPresentationPolicy("npm", "npm package-1 = 2.0.0\nnpm package-2 = 2.0.0")
        val filtered = policy.filter(report)
        assertEquals(1, filtered.candidates.size)
        assertEquals(CoverageSummary(checked = 3, ignored = 2), policy.coverageSummary(report, snapshot))
        assertEquals(policy.coverageSummary(report, snapshot), filtered.coverageSummary(snapshot))
        assertEquals(500L, filtered.validUntilNanos)
        assertEquals(100L, filtered.checkedAtNanos)
        assertTrue(report.coverage!!.values.all { it.outcome == DeclarationOutcome.CHECKED })
        assertSame(report, VersionPresentationPolicy("npm", "").filter(report))
    }
}
