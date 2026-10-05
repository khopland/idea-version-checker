package io.github.khopland.versionchecker

import com.intellij.codeInspection.ProblemHighlightType

enum class DependencySeverity(val label: String, val highlight: ProblemHighlightType?) {
    WARNING("Warning (yellow)", ProblemHighlightType.WARNING),
    ERROR("Error (red)", ProblemHighlightType.GENERIC_ERROR),
    INFORMATION("Information", ProblemHighlightType.INFORMATION),
    DISABLED("Disabled", null);

    override fun toString(): String = label
}

internal typealias DependencyChangeKind = io.github.khopland.versionchecker.core.VersionChangeKind

internal fun DependencyChangeKind.severity(options: VersionCheckerSettings.Options): DependencySeverity = when (this) {
    DependencyChangeKind.PATCH -> options.patchSeverity
    DependencyChangeKind.MINOR -> options.minorSeverity
    DependencyChangeKind.MAJOR -> options.majorSeverity
    DependencyChangeKind.OTHER -> options.otherSeverity
    DependencyChangeKind.DEPRECATED -> options.deprecatedSeverity
}
