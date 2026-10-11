package io.github.khopland.versionchecker.core

import io.github.khopland.versionchecker.UpdateMode

/** Observed results authorize edits only while their bound identities, revision and deadline survive. */
internal class VersionResultLease(
    val context: BuildContextId,
    val mode: UpdateMode,
    val revision: VersionResultCache.Revision,
    val checkedAtNanos: Long,
    val expiresAtNanos: Long,
    private val declarationIds: Set<DeclarationId>? = null,
    private val bindDeclarations: ((Set<DeclarationId>) -> VersionResultLease)? = null,
    private val current: () -> Boolean
) {
    fun isCurrent(): Boolean = current()

    /** Bind while rendering a fix; complete and remapped observations keep their whole-result guard. */
    fun forDeclarations(ids: Set<DeclarationId>): VersionResultLease {
        if (declarationIds != null && (ids.isEmpty() || !declarationIds.containsAll(ids)))
            return VersionResultLease(context, mode, revision, checkedAtNanos, expiresAtNanos) { false }
        return bindDeclarations?.invoke(ids) ?: this
    }
}
