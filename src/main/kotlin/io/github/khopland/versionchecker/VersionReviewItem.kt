package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.DiagnosticSanitizer

import com.intellij.psi.PsiElement
import io.github.khopland.versionchecker.core.VersionDeclaration

/** Captured presentation identity: navigation never attempts to parse a provider's display text. */
internal data class VersionReviewSource(val filePath: String, val offset: Int = 0) {
    fun findFile() = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
        ?.takeIf { it.isValid && !it.isDirectory }

    fun navigationOffset(textLength: Int) = offset.coerceIn(0, textLength.coerceAtLeast(0))

    companion object {
        fun capture(element: PsiElement?, offset: Int? = null): VersionReviewSource? = element?.let {
            it.containingFile?.virtualFile?.let { file -> VersionReviewSource(file.path, offset ?: it.textOffset) }
        }
    }
}

/** Safe provider-owned facts only; no native output, repository settings or environment values. */
internal data class VersionReviewItem(
    val reason: String,
    val source: VersionReviewSource? = null,
    val declaration: String? = null,
    val current: String? = null,
    val target: String? = null,
    val consumers: List<String> = emptyList(),
    val buildContext: String? = null,
    val checkedVersion: String? = null
) {
    fun description(): String = DiagnosticSanitizer.sanitize(listOfNotNull(
        declaration, reason,
        current?.let { "Declared: $it" }, checkedVersion?.let { "Checked version: $it" }, target?.let { "Target: $it" },
        consumers.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Affected consumers: "),
        buildContext?.let { "Build: $it" }, source?.let { "Source: ${it.filePath}" }
    ).joinToString("\n"))
}

internal fun VersionEdit.withReview(declarations: List<VersionDeclaration>, buildContext: String): VersionEdit {
    val captured = VersionReviewItem(
        "The check selected this newer version. This is an available update, not a compatibility guarantee.",
        VersionReviewSource.capture(element, documentChange?.range?.startOffset), location, expected, latest,
        declarations.map { listOf(it.artifact.namespace, it.artifact.name).filter(String::isNotEmpty).joinToString(":") }.distinct(),
        buildContext, declarations.map { it.resolvedVersion ?: it.baseline }.filter(String::isNotEmpty).distinct().joinToString().takeIf(String::isNotEmpty)
    )
    return object : VersionEdit by this { override val review = captured }
}
