package io.github.khopland.versionchecker.npm

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.VersionDeclaration

/** Provider eligibility only: result leases and fresh input validation still authorize application. */
internal data class NpmPreparedUpdate(
    val target: DeclarationId,
    val affectedDeclarations: Set<DeclarationId>,
    val replacement: String,
    val blockingReasons: List<String>,
    val edit: NpmVersionEdit?,
    val followUp: List<String>
) {
    companion object {
        fun prepare(project: Project, file: VirtualFile, declaration: VersionDeclaration, value: JsonStringLiteral?,
                    replacement: String): NpmPreparedUpdate {
            // Review blockers explain unavailable edits; stale eligible targets fail explicitly.
            val selector = NpmSelector.parse(declaration.artifact.name, declaration.selector)
            val reason = NpmManifest.overrideReviewReason(project, file, declaration)
                ?: NpmManifest.reviewReason(declaration)
                ?: when {
                    selector == null -> NpmManifest.reviewReason(declaration.copy(baseline = ""))
                    selector.baseline != NpmVersion.parse(declaration.baseline) ->
                        "npm selector baseline does not match the checked declaration; requires manual review"
                    else -> null
                }
            if (reason != null) return NpmPreparedUpdate(declaration.id, setOf(declaration.id), replacement,
                listOf(reason), null, emptyList())
            checkNotNull(value) { "npm declaration no longer exists" }
            val entry = value.parent as? JsonProperty
            val section = entry?.parent?.parent as? JsonProperty
            check(file.path == declaration.id.file && value.containingFile.virtualFile == file &&
                "${section?.name}/${entry?.name}" == declaration.id.location) {
                "npm declaration no longer exists"
            }
            check(value.value == declaration.selector) { "npm selector changed during the check" }
            val edit = NpmVersionEdit(value, replacement,
                "${declaration.id.file}: ${declaration.id.location} (${declaration.artifact.name})")
            return NpmPreparedUpdate(declaration.id, setOf(declaration.id), replacement, emptyList(), edit,
                listOf(NpmUpdateGuidance.message))
        }
    }
}
