package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.VersionChangeKind

import com.intellij.codeInspection.ProblemHighlightType

/**
 * Severity comes from the inspection profile (Settings → Editor → Inspections), so users have one place to
 * change or disable it. Deprecated and relocated artifacts are struck through at that same severity.
 */
internal fun VersionChangeKind.highlightType(): ProblemHighlightType =
    if (this == VersionChangeKind.DEPRECATED) ProblemHighlightType.LIKE_DEPRECATED else ProblemHighlightType.GENERIC_ERROR_OR_WARNING
