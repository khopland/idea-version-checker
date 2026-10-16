package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DependencyReviewFreshnessTest {
    private val row = DependencyReviewRow("npm", "/project", DeclarationId("/project/package.json", "dependencies/example"),
        "example", "1.0", "2.0", VersionChangeKind.MAJOR, "Update", "", UpdateMode.MAJOR, false)
    private fun seconds(value: Long) = TimeUnit.SECONDS.toNanos(value)

    @Test fun `unknown future and inconsistent timing never implies a fresh observation`() {
        for (unknown in listOf(row, row.copy(expiresAtNanos = seconds(10)),
            row.copy(checkedAtNanos = seconds(2), expiresAtNanos = seconds(10)))) {
            assertNull(DependencyReviewFreshness.elapsedMillis(unknown, seconds(1)))
            assertEquals("Unknown", DependencyReviewFreshness.formatAge(unknown, seconds(1)))
            assertEquals("Unknown", DependencyReviewFreshness.formatExpiry(unknown, seconds(1)))
            assertEquals("unknown", DependencyReviewFreshness.expiryStatus(unknown, seconds(1)))
        }
        val invalidExpiry = row.copy(checkedAtNanos = seconds(1), expiresAtNanos = seconds(1))
        assertEquals("Unknown", DependencyReviewFreshness.formatExpiry(invalidExpiry, seconds(2)))
        assertNull(DependencyReviewFreshness.remainingMillis(invalidExpiry, seconds(2)))
    }

    @Test fun `age advances and expiry reaches the exact monotonic boundary`() {
        val timed = row.copy(checkedAtNanos = seconds(1), expiresAtNanos = seconds(61))
        assertEquals("Just now", DependencyReviewFreshness.formatAge(timed, seconds(1)))
        assertEquals("Expires in 1m", DependencyReviewFreshness.formatExpiry(timed, seconds(1)))
        assertEquals(59_000L, DependencyReviewFreshness.elapsedMillis(timed, seconds(60)))
        assertEquals("59s ago", DependencyReviewFreshness.formatAge(timed, seconds(60)))
        assertEquals("Expires in 1s", DependencyReviewFreshness.formatExpiry(timed, seconds(60)))
        assertEquals("Expires in <1s", DependencyReviewFreshness.formatExpiry(timed, seconds(61) - 1))
        assertEquals(1L, DependencyReviewFreshness.remainingMillis(timed, seconds(61) - 1))
        assertEquals("Expired", DependencyReviewFreshness.formatExpiry(timed, seconds(61)))
        assertEquals(0L, DependencyReviewFreshness.remainingMillis(timed, seconds(61)))
        assertEquals("expired", DependencyReviewFreshness.expiryStatus(timed, seconds(61)))
        assertEquals("1m ago", DependencyReviewFreshness.formatAge(timed, seconds(61)))
    }

    @Test fun `negative nano origins and zero expiry are valid timing not unknown sentinels`() {
        val timed = row.copy(checkedAtNanos = seconds(-2), expiresAtNanos = 0)
        assertEquals(1_000L, DependencyReviewFreshness.elapsedMillis(timed, seconds(-1)))
        assertEquals("Expires in 1s", DependencyReviewFreshness.formatExpiry(timed, seconds(-1)))
        assertEquals("Expired", DependencyReviewFreshness.formatExpiry(timed, 0))
        assertEquals(0L, DependencyReviewFreshness.elapsedMillis(row.copy(checkedAtNanos = 0), 0))
    }

    @Test fun `overflow cannot turn future or invalid timing into a fresh observation`() {
        val overflow = row.copy(checkedAtNanos = Long.MAX_VALUE, expiresAtNanos = Long.MIN_VALUE + 1)
        assertNull(DependencyReviewFreshness.elapsedMillis(overflow, Long.MIN_VALUE))
        assertNull(DependencyReviewFreshness.remainingMillis(overflow, Long.MIN_VALUE))
        assertEquals("Unknown", DependencyReviewFreshness.formatAge(overflow, Long.MIN_VALUE))
        assertEquals("Unknown", DependencyReviewFreshness.formatExpiry(overflow, Long.MIN_VALUE))
        assertEquals("unknown", DependencyReviewFreshness.expiryStatus(overflow, Long.MIN_VALUE))
        val invalidInterval = row.copy(checkedAtNanos = Long.MIN_VALUE, expiresAtNanos = Long.MAX_VALUE)
        assertEquals(0L, DependencyReviewFreshness.elapsedMillis(invalidInterval, Long.MIN_VALUE + 1))
        assertNull(DependencyReviewFreshness.remainingMillis(invalidInterval, Long.MIN_VALUE + 1))
        assertEquals("Unknown", DependencyReviewFreshness.formatExpiry(invalidInterval, Long.MIN_VALUE + 1))
        val reversed = row.copy(checkedAtNanos = seconds(2), expiresAtNanos = seconds(1))
        assertNull(DependencyReviewFreshness.remainingMillis(reversed, seconds(3)))
        assertEquals("unknown", DependencyReviewFreshness.expiryStatus(reversed, seconds(3)))
    }

    @Test fun `duration formatting remains useful at hour and day boundaries`() {
        val timed = row.copy(checkedAtNanos = 0)
        assertEquals("1h ago", DependencyReviewFreshness.formatAge(timed, seconds(3_600)))
        assertEquals("2d ago", DependencyReviewFreshness.formatAge(timed, seconds(172_800)))
    }

    @Test fun `row projection uses cache observation timing and keeps pending declarations unknown`() {
        val a = VersionDeclaration(row.source, ArtifactId("npm", "example"), "1.0", "1.0")
        val b = a.copy(id = a.id.copy(location = "dependencies/pending"), artifact = ArtifactId("npm", "pending"))
        val snapshot = BuildSnapshot(BuildContextId("npm", "/project", "root"), row.source.file,
            BuildFingerprint(emptyMap(), ""), listOf(a, b))
        val report = UpdateReport(coverage = mapOf(a.id to DeclarationCoverage(DeclarationOutcome.SKIPPED, "Unsupported selector")))
        val observation = VersionResultCache.Observation(snapshot, report, UpdateMode.MAJOR, true,
            checkedAtNanos = seconds(1), expiresAtNanos = seconds(61))
        val projected = DependencyReviewRows.from(listOf(observation))
        val skipped = projected.single { it.source == a.id }
        assertEquals(seconds(1), skipped.checkedAtNanos)
        assertEquals(seconds(61), skipped.expiresAtNanos)
        val pending = projected.single { it.source == b.id }
        assertEquals("Pending", pending.outcome)
        assertNull(pending.checkedAtNanos)
        assertNull(pending.expiresAtNanos)
        assertEquals("Unknown", DependencyReviewFreshness.formatAge(pending, seconds(2)))
        val completeButUnreported = DependencyReviewRows.from(listOf(observation.copy(checking = false)))
            .single { it.source == b.id }
        assertEquals("Coverage unreported", completeButUnreported.outcome)
        assertNull(completeButUnreported.checkedAtNanos)
        assertNull(completeButUnreported.expiresAtNanos)
        val unknown = DependencyReviewRows.from(listOf(observation.copy(checkedAtNanos = null, expiresAtNanos = null,
            report = report.copy(checkedAtNanos = seconds(1), validUntilNanos = seconds(61)))))
        assertTrue("Report timestamps alone must not invent a cache observation", unknown.all { it.checkedAtNanos == null })
    }
}
