package io.github.khopland.versionchecker

import junit.framework.TestCase

class VersionPreviewExpiryTest : TestCase() {
    private val plan = BulkUpdatePlan(emptyList(), emptyList())
    private fun prepared(checkedAt: Long, deadline: Long?) =
        PreparedVersionPreview(plan, checkedAt, 0, 1, expiresAtNanos = deadline)

    fun testLoadedAlternativesUseTheEarliestRealDeadlineRatherThanTheOldestObservation() {
        val primary = prepared(2, 50)
        val patch = prepared(1, 100)
        val minor = prepared(3, 20)
        val combined = primary.withTargetChoices(mapOf(UpdateMode.PATCH to patch, UpdateMode.MINOR to minor))
        assertEquals(1L, combined.checkedAtNanos)
        assertEquals(20L, combined.expiresAtNanos)
        assertSame(primary.plan, combined.plan)
        assertSame(patch.plan, combined.targetPlans[UpdateMode.PATCH])
        assertSame(minor.plan, combined.targetPlans[UpdateMode.MINOR])
        assertEquals(50L, primary.expiresAtNanos)
    }

    fun testAnyUnknownLoadedDeadlineKeepsTheAggregateUnknown() {
        val known = prepared(1, 100)
        val unknown = prepared(1, null)
        assertNull(known.withTargetChoices(mapOf(UpdateMode.PATCH to unknown)).expiresAtNanos)
        assertNull(unknown.withTargetChoices(mapOf(UpdateMode.PATCH to known)).expiresAtNanos)
        assertNull(unknown.withTargetChoices(emptyMap()).expiresAtNanos)
        assertEquals(100L, known.withTargetChoices(emptyMap()).expiresAtNanos)
    }
}
