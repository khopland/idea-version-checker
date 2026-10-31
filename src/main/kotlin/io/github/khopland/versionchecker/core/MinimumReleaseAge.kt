package io.github.khopland.versionchecker.core

import java.util.concurrent.TimeUnit

/**
 * Withholds versions published less than a minimum age ago, for example to match a repository
 * firewall quarantine that lists a new release but refuses to serve it yet.
 */
class MinimumReleaseAge(val hours: Int, now: Long = System.currentTimeMillis()) {
    /** Epoch milliseconds; versions published at or before this instant are old enough. */
    val cutoff = now - TimeUnit.HOURS.toMillis(hours.toLong())
    fun oldEnough(publishedMillis: Long) = publishedMillis <= cutoff

    companion object {
        fun of(hours: Int): MinimumReleaseAge? = if (hours > 0) MinimumReleaseAge(hours) else null
    }
}
