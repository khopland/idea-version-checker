package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.UpdateScope

/** Dialog intent only. Provider input validation and result leases still authorize every edit. */
internal data class VersionPreviewState(
    val mode: UpdateMode,
    val scope: UpdateScope,
    val generation: Long = 0,
    val phase: Phase = Phase.Ready,
) {
    sealed interface Phase {
        data object Ready : Phase
        data object Checking : Phase
        data class Failed(val message: String) : Phase
        data object Applying : Phase
        data object Accepted : Phase
        data object Closed : Phase
    }

    val checking get() = phase == Phase.Checking
    val canApply get() = phase == Phase.Ready
    val canInspect get() = phase == Phase.Ready || phase == Phase.Applying
    private val terminal get() = phase == Phase.Accepted || phase == Phase.Closed

    fun authorizes(observedGeneration: Long, selectedMode: UpdateMode, selectedScope: UpdateScope): Boolean =
        generation == observedGeneration && mode == selectedMode && scope == selectedScope &&
            (phase == Phase.Ready || phase == Phase.Applying || phase == Phase.Accepted)

    fun recheck(): VersionPreviewState = if (terminal) this
        else copy(generation = generation + 1, phase = Phase.Checking)

    fun prepared(mode: UpdateMode, scope: UpdateScope): VersionPreviewState = if (terminal) this
        else copy(mode = mode, scope = scope, generation = generation + 1, phase = Phase.Ready)

    fun configured(scope: UpdateScope): VersionPreviewState = if (terminal || this.scope == scope) this
        else copy(scope = scope, generation = generation + 1)

    fun failed(message: String): VersionPreviewState = if (terminal) this else copy(phase = Phase.Failed(message))

    fun applying(): VersionPreviewState = if (canApply) copy(phase = Phase.Applying) else this

    /** A late apply callback must not clear a newer check or its failure. */
    fun applyFinished(observedGeneration: Long): VersionPreviewState =
        if (generation == observedGeneration && phase == Phase.Applying) copy(phase = Phase.Ready) else this

    /** Accepted modal reviews apply after disposal; cancelled/disposed reviews retire held plans. */
    fun closed(accepted: Boolean): VersionPreviewState = if (terminal) this else copy(
        phase = if (accepted && (phase == Phase.Ready || phase == Phase.Applying)) Phase.Accepted else Phase.Closed)
}
