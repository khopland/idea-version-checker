package io.github.khopland.versionchecker.core

import io.github.khopland.versionchecker.UpdateMode

/** One result observation authorizes edits only while its identity, generation and deadline survive. */
internal class VersionResultLease(
    val context: BuildContextId,
    val mode: UpdateMode,
    val revision: VersionResultCache.Revision,
    val checkedAtNanos: Long,
    val expiresAtNanos: Long,
    private val current: () -> Boolean
) {
    fun isCurrent(): Boolean = current()
}
