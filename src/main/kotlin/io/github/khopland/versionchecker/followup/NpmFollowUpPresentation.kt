package io.github.khopland.versionchecker.followup

import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import java.nio.file.Path

/** A chosen workspace may differ from the declarations in the originating update session. */
internal object NpmFollowUpPresentation {
    fun withWorkspace(message: String, workspaceRoot: Path?, projectRoot: String?): String {
        val label = if (workspaceRoot == null) "not selected" else runCatching {
            val root = workspaceRoot.normalize()
            val base = projectRoot?.let { Path.of(it).normalize() }
            if (base == null || !base.isAbsolute || !root.isAbsolute || !root.startsWith(base)) "<outside-project>"
            else base.relativize(root).toString().ifEmpty { "." }
        }.getOrDefault("<unavailable>")
        val safe = DiagnosticSanitizer.sanitize(label.map { if (it.isISOControl()) '?' else it }.joinToString("")).take(1024)
        return "Selected npm workspace root: $safe (project-relative).\n$message"
    }
}
