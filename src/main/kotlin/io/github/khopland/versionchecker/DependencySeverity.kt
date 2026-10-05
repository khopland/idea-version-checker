package io.github.khopland.versionchecker

import com.intellij.codeInspection.ProblemHighlightType

enum class DependencySeverity(val label: String, val highlight: ProblemHighlightType?) {
    WARNING("Warning (yellow)", ProblemHighlightType.WARNING),
    ERROR("Error (red)", ProblemHighlightType.GENERIC_ERROR),
    INFORMATION("Information", ProblemHighlightType.INFORMATION),
    DISABLED("Disabled", null);

    override fun toString(): String = label
}

internal enum class DependencyChangeKind {
    PATCH, MINOR, MAJOR, OTHER, DEPRECATED;

    fun severity(options: VersionCheckerSettings.Options): DependencySeverity = when (this) {
        PATCH -> options.patchSeverity
        MINOR -> options.minorSeverity
        MAJOR -> options.majorSeverity
        OTHER -> options.otherSeverity
        DEPRECATED -> options.deprecatedSeverity
    }

    companion object {
        fun between(current: String, latest: String): DependencyChangeKind {
            fun numbers(version: String): List<Int>? =
                Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-.].*)?$""").matchEntire(version)
                    ?.groupValues?.drop(1)?.map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
            val old = numbers(current) ?: return OTHER
            val new = numbers(latest) ?: return OTHER
            return when {
                old[0] != new[0] -> MAJOR
                old[1] != new[1] -> MINOR
                else -> PATCH
            }
        }
    }
}

/** Explicit project policy: Maven repositories do not define a general deprecation flag. */
internal fun deprecatedDependencies(text: String): Map<String, String> = text.lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
    .mapNotNull { line ->
        val parts = line.split('=', limit = 2).map { it.trim() }
        val coordinate = parts[0]
        if (!Regex("""[^\s:]+:[^\s:]+""").matches(coordinate)) null
        else coordinate to (parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Deprecated by project policy")
    }.toMap()
