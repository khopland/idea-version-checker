package io.github.khopland.versionchecker

import java.util.concurrent.TimeUnit

/** Display-only monotonic timing. Unexpired observations do not prove unchanged inputs or authorize edits. */
internal object DependencyReviewFreshness {
    fun elapsedMillis(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): Long? {
        val checkedAt = row.checkedAtNanos ?: return null
        val elapsed = nowNanos - checkedAt
        return elapsed.takeIf { it >= 0 }?.let(TimeUnit.NANOSECONDS::toMillis)
    }

    fun remainingMillis(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): Long? {
        val checkedAt = row.checkedAtNanos ?: return null
        val expiresAt = row.expiresAtNanos ?: return null
        if (elapsedMillis(row, nowNanos) == null || expiresAt - checkedAt <= 0) return null
        val remaining = expiresAt - nowNanos
        return if (remaining <= 0) 0 else (remaining - 1) / 1_000_000 + 1
    }

    fun formatAge(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String {
        val elapsed = elapsedMillis(row, nowNanos) ?: return "Unknown"
        return if (elapsed < 1_000) "Just now" else "${duration(elapsed)} ago"
    }

    fun formatExpiry(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String {
        val remaining = remainingMillis(row, nowNanos) ?: return "Unknown"
        val expiresAt = row.expiresAtNanos ?: return "Unknown"
        return when {
            expiresAt - nowNanos <= 0 -> "Expired"
            remaining < 1_000 -> "Expires in <1s"
            else -> "Expires in ${duration(remaining)}"
        }
    }

    fun expiryStatus(row: DependencyReviewRow, nowNanos: Long = System.nanoTime()): String = when {
        remainingMillis(row, nowNanos) == null -> "unknown"
        row.expiresAtNanos!! - nowNanos <= 0 -> "expired"
        else -> "unexpired"
    }

    private fun duration(millis: Long): String = when {
        millis < 60_000 -> "${millis / 1_000}s"
        millis < 3_600_000 -> "${millis / 60_000}m"
        millis < 86_400_000 -> "${millis / 3_600_000}h"
        else -> "${millis / 86_400_000}d"
    }
}
