package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test

class DeclarationCoverageAccumulatorTest {
    private val physicalId = DeclarationId("build.gradle.kts", "dependency")

    private fun snapshot(adapterId: String = "gradle", shared: Boolean = false): BuildSnapshot {
        val id = if (shared) physicalId.copy(location = "shared-owner") else physicalId
        return BuildSnapshot(
            BuildContextId(adapterId, "/project", if (shared) "parent" else "child"), physicalId.file,
            BuildFingerprint(emptyMap(), "test"),
            listOf(VersionDeclaration(id, ArtifactId(adapterId, "library"), "1.0", "1.0",
                sourceId = physicalId.takeIf { shared }))
        )
    }

    private fun report(snapshot: BuildSnapshot, outcome: DeclarationOutcome) =
        UpdateReport(coverage = mapOf(snapshot.declarations.single().id to DeclarationCoverage(outcome)))

    @Test fun `shared declaration keeps the most actionable outcome regardless of scan order`() {
        val priority = listOf(DeclarationOutcome.SKIPPED, DeclarationOutcome.IGNORED, DeclarationOutcome.CHECKED,
            DeclarationOutcome.MANUAL_REVIEW, DeclarationOutcome.FAILED)
        for ((index, winner) in priority.withIndex()) {
            for (other in priority.take(index + 1)) {
                for (order in listOf(listOf(winner, other), listOf(other, winner))) {
                    val accumulator = DeclarationCoverageAccumulator()
                    order.forEachIndexed { i, outcome ->
                        val snapshot = snapshot(shared = i == 0)
                        accumulator.add(snapshot, report(snapshot, outcome))
                    }
                    assertEquals("$order", CoverageSummary.from(mapOf(physicalId to DeclarationCoverage(winner))),
                        accumulator.summary())
                }
            }
        }
    }

    @Test fun `identical physical ids in different adapters count separately`() {
        val accumulator = DeclarationCoverageAccumulator()
        for (adapterId in listOf("gradle", "npm")) {
            val snapshot = snapshot(adapterId)
            accumulator.add(snapshot, report(snapshot, DeclarationOutcome.CHECKED))
        }
        assertEquals(CoverageSummary(checked = 2), accumulator.summary())
    }

    @Test fun `missing outcomes include skipped declarations in pending count`() {
        val accumulator = DeclarationCoverageAccumulator()
        val snapshot = snapshot().copy(skippedDeclarations = mapOf(physicalId.copy(location = "unsupported") to "Dynamic version"))
        accumulator.add(snapshot, UpdateReport(coverage = emptyMap()))
        assertEquals(CoverageSummary(pending = 2), accumulator.summary())
        accumulator.add(snapshot, report(snapshot, DeclarationOutcome.CHECKED))
        assertEquals(CoverageSummary(checked = 1, pending = 1), accumulator.summary())
    }

    @Test fun `unknown coverage remains unknown when followed by complete reports`() {
        val accumulator = DeclarationCoverageAccumulator()
        val snapshot = snapshot()
        accumulator.add(snapshot, UpdateReport())
        accumulator.add(snapshot, report(snapshot, DeclarationOutcome.CHECKED))
        assertNull(accumulator.summary())
    }
}
