package io.github.khopland.versionchecker

/** Immutable evidence request; contains no classes from the optional Maven plugin. */
internal data class MavenVerificationTarget(
    val pomPath: String, val groupId: String, val artifactId: String, val kind: String,
    val expectedVersion: String, val afterGeneration: Long,
)

internal enum class EffectiveVersionStatus { UNKNOWN, MISMATCH, CONFIRMED }

internal data class EffectiveVersionResult(val status: EffectiveVersionStatus, val detail: String)

/** A declaration edit or a reload request alone cannot confirm a resolved version. */
internal fun compareEffectiveVersion(target: MavenVerificationTarget, generation: Long?, inputsCurrent: Boolean,
                                     versions: Set<String>?): EffectiveVersionResult = when {
    !inputsCurrent -> EffectiveVersionResult(EffectiveVersionStatus.UNKNOWN, "Build files changed since this update or resolution. Reload and review a fresh update.")
    generation == null || generation <= target.afterGeneration ->
        EffectiveVersionResult(EffectiveVersionStatus.UNKNOWN, "No subsequent Maven dependency resolution observed. Reload Maven, wait for completion, then verify again.")
    target.kind != "DEPENDENCY" -> EffectiveVersionResult(EffectiveVersionStatus.UNKNOWN, "Effective verification currently covers resolved dependencies; this ${target.kind.lowercase()} needs manual review.")
    versions.isNullOrEmpty() -> EffectiveVersionResult(EffectiveVersionStatus.UNKNOWN, "Dependency absent from the resolved model or resolution failed.")
    versions == setOf(target.expectedVersion) -> EffectiveVersionResult(EffectiveVersionStatus.CONFIRMED, "Imported dependency version confirmed: ${target.expectedVersion}.")
    else -> EffectiveVersionResult(EffectiveVersionStatus.MISMATCH, "Expected ${target.expectedVersion}; imported dependency model contains ${versions.sorted().joinToString()}.")
}
