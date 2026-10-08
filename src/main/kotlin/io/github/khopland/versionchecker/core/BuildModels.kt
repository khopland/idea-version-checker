package io.github.khopland.versionchecker.core

import io.github.khopland.versionchecker.UpdateMode

/** Coordinates, declarations and repository contexts have separate identities. */
internal data class ArtifactId(val namespace: String, val name: String)

enum class UpdateScope(val label: String) { CURRENT_FILE("Current File"), WHOLE_PROJECT("Whole Project") }
internal data class BuildSelection(val scope: UpdateScope, val currentFile: String? = null)
internal data class AdapterCapabilities(
    val updateModes: Set<UpdateMode> = UpdateMode.entries.toSet()
)
internal data class BuildContextId(val adapterId: String, val root: String, val resolutionId: String)
internal data class DeclarationId(val file: String, val location: String)
internal data class VersionDeclaration(
    val id: DeclarationId, val artifact: ArtifactId,
    val selector: String, val baseline: String, val resolvedVersion: String? = null
)
internal data class BuildFingerprint(val files: Map<String, String>, val configuration: String)
internal data class BuildSnapshot(
    val context: BuildContextId, val sourceFile: String, val fingerprint: BuildFingerprint,
    val declarations: List<VersionDeclaration>
)
internal enum class VersionChangeKind { PATCH, MINOR, MAJOR, OTHER, DEPRECATED }
internal data class UpdateCandidate(
    val declaration: VersionDeclaration, val version: String,
    val replacementSelector: String = version, val kind: VersionChangeKind = VersionChangeKind.OTHER
)
internal enum class NoticeKind { RELOCATED, DEPRECATED, MANUAL_REVIEW }
internal data class UpdateNotice(val declaration: VersionDeclaration, val kind: NoticeKind, val message: String)

/** A failed check is distinct from a successful check with no available updates. */
internal data class UpdateReport(
    val candidates: List<UpdateCandidate> = emptyList(), val notices: List<UpdateNotice> = emptyList(),
    val failure: String? = null,
    /** Monotonic deadline of the native metadata used, so report caching cannot extend its freshness. */
    val validUntilNanos: Long? = null
) {
    val successful: Boolean get() = failure == null
}
