package io.github.khopland.versionchecker

import java.util.concurrent.TimeUnit

/** Display-only monotonic timing. Unexpired observations do not prove unchanged inputs or authorize edits. */
internal object DependencyReviewFreshness {
    fun elapsedMillis(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): Long? =
        elapsedNanos(row, nowNanos)?.let(TimeUnit.NANOSECONDS::toMillis)

    fun remainingMillis(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): Long? =
        remainingNanos(row, nowNanos)?.let(::remainingMillis)

    fun formatAge(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String {
        val elapsed = elapsedMillis(row, nowNanos) ?: return "Unknown"
        return if (elapsed < 1_000) "Just now" else "${duration(elapsed)} ago"
    }

    fun formatExpiry(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String {
        val remaining = remainingNanos(row, nowNanos) ?: return "Unknown"
        return when {
            remaining <= 0 -> "Expired"
            remainingMillis(remaining) < 1_000 -> "Expires in <1s"
            else -> "Expires in ${duration(remainingMillis(remaining))}"
        }
    }

    fun expiryStatus(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String =
        remainingNanos(row, nowNanos)?.let { if (it <= 0) "expired" else "unexpired" } ?: "unknown"

    private fun elapsedNanos(row: DependencyReviewRow, nowNanos: Long): Long? =
        row.checkedAtNanos?.let { difference(nowNanos, it)?.takeIf { elapsed -> elapsed >= 0 } }

    private fun remainingNanos(row: DependencyReviewRow, nowNanos: Long): Long? {
        val checkedAt = row.checkedAtNanos ?: return null
        val expiresAt = row.expiresAtNanos ?: return null
        if (elapsedNanos(row, nowNanos) == null || difference(expiresAt, checkedAt)?.let { it > 0 } != true) return null
        return difference(expiresAt, nowNanos)
    }

    /** Overflow cannot distinguish a wrapped clock from inconsistent timing; display unknown conservatively. */
    private fun difference(later: Long, earlier: Long): Long? = try {
        Math.subtractExact(later, earlier)
    } catch (_: ArithmeticException) { null }

    private fun remainingMillis(nanos: Long): Long = if (nanos <= 0) 0 else (nanos - 1) / 1_000_000 + 1

    private fun duration(millis: Long): String = when {
        millis < 60_000 -> "${millis / 1_000}s"
        millis < 3_600_000 -> "${millis / 60_000}m"
        millis < 86_400_000 -> "${millis / 3_600_000}h"
        else -> "${millis / 86_400_000}d"
    }
}
