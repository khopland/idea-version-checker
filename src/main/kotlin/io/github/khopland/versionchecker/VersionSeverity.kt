package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.VersionChangeKind

import com.intellij.codeInspection.ProblemHighlightType

enum class VersionSeverity(val label: String, val highlight: ProblemHighlightType?) {
    WARNING("Warning (yellow)", ProblemHighlightType.WARNING),
    ERROR("Error (red)", ProblemHighlightType.GENERIC_ERROR),
    INFORMATION("Information", ProblemHighlightType.INFORMATION),
    DISABLED("Disabled", null);

    override fun toString(): String = label
}

internal fun VersionChangeKind.severity(options: VersionCheckerSettings.Options): VersionSeverity = when (this) {
    VersionChangeKind.PATCH -> options.patchSeverity
    VersionChangeKind.MINOR -> options.minorSeverity
    VersionChangeKind.MAJOR -> options.majorSeverity
    VersionChangeKind.OTHER -> options.otherSeverity
    VersionChangeKind.DEPRECATED -> options.deprecatedSeverity
}
