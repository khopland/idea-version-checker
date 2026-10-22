package io.github.khopland.versionchecker

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import java.nio.file.Path
import java.time.Instant

/** Explicit presentation export. It contains no cached inputs, native diagnostics or edit authority. */
internal object DependencyReviewExport {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    fun json(rows: List<DependencyReviewRow>, basePath: String?, generatedAt: Instant = Instant.now(),
             nowNanos: Long = System.nanoTime()): String {
        val document = JsonObject().apply {
            addProperty("schemaVersion", 2)
            addProperty("generatedAt", generatedAt.toString())
            addProperty("generatedAtPurpose", "export-creation")
            addProperty("observationTimingBasis", "monotonic-durations-at-export")
            addProperty("scope", "cached-observations")
            addProperty("requiresFreshReviewBeforeEditing", true)
            add("results", jsonResults(rows, basePath, nowNanos))
        }
        return gson.toJson(document) + "\n"
    }

    /** Each caller owns its array, so support-report bounds cannot alter another export. */
    fun jsonResults(rows: List<DependencyReviewRow>, basePath: String?, nowNanos: Long) = JsonArray().apply {
        rows.forEach { row -> add(JsonObject().apply {
            addProperty("buildSystem", safe(row.adapter))
            addProperty("buildRoot", sourcePath(row.root, basePath))
            addProperty("sourceFile", sourcePath(row.source.file, basePath))
            addProperty("sourceLocation", safe(row.source.location))
            row.origin?.let { origin ->
                addProperty("checkSourceFile", sourcePath(origin.checkSourceFile, basePath))
                origin.coverageDescription?.let { addProperty("coverageScope", safe(it)) }
            }
            addProperty("artifact", safe(row.artifact))
            addProperty("currentVersion", safe(row.current))
            row.available?.let { addProperty("availableVersion", safe(it)) }
            row.kind?.let { addProperty("changeKind", it.name.lowercase()) }
            addProperty("outcome", row.outcome.label)
            addProperty("detail", safe(row.detail))
            addProperty("mode", row.mode.name.lowercase())
            addProperty("checking", row.checking)
            addProperty("checkFailed", row.checkFailed)
            addProperty("observationAgeMillis", DependencyReviewFreshness.elapsedMillis(row, nowNanos))
            addProperty("cacheExpiresInMillis", DependencyReviewFreshness.remainingMillis(row, nowNanos))
            addProperty("cacheExpiryStatus", DependencyReviewFreshness.expiryStatus(row, nowNanos))
        }) }
    }

    /** A shareable summary of precisely the supplied displayed rows; it cannot authorize edits. */
    fun markdown(rows: List<DependencyReviewRow>, basePath: String?, generatedAt: Instant = Instant.now(),
                 nowNanos: Long = System.nanoTime()): String = buildString {
        append("# Dependency review\n\n")
        append("Exported: $generatedAt (report creation time, not check time).\n\n")
        append("${rows.size} displayed cached observation${if (rows.size == 1) "" else "s"}, in displayed order. ")
        append("Filters can omit dependencies; this is not a complete dependency audit.\n\n")
        append("Refresh and review before editing. Cached observations do not establish unchanged build inputs, ")
        append("successful resolution, or compatibility. Observation age and expiry below retain their original check timing.\n")
        if (rows.isEmpty()) append("\nNo displayed cached observations.\n")
        rows.forEachIndexed { index, row ->
            append("\n## ${index + 1}. ${markdownText(row.artifact.ifBlank { "Unidentified declaration" })}\n\n")
            append("- Build system: ${markdownText(row.adapter)}; root: ${markdownText(sourcePath(row.root, basePath))}\n")
            append("- Source: ${markdownText(sourcePath(row.source.file, basePath))}")
            if (row.source.location.isNotBlank()) append("; declaration: ${markdownText(row.source.location)}")
            append("\n- Check source: ${markdownText(row.origin?.checkSourceFile?.let { sourcePath(it, basePath) } ?: "Unknown")}")
            append("; coverage: ${markdownText(row.origin?.coverageDescription ?: "Unknown")}\n")
            append("- Version: ${markdownText(row.current.ifBlank { "Unknown" })}")
            row.available?.let { append(" → ${markdownText(it)}") }
            append("; mode: ${row.mode.name.lowercase()}")
            row.kind?.let { append("; change: ${it.name.lowercase()}") }
            append("\n- Outcome: ${row.outcome.label}; checking: ${if (row.checking) "yes" else "no"}")
            append("; check failed: ${if (row.checkFailed) "yes" else "no"}\n")
            append("- Observation: ${DependencyReviewFreshness.formatAge(row, nowNanos)}; ")
            append("cache: ${DependencyReviewFreshness.formatExpiry(row, nowNanos)}\n")
            if (row.detail.isNotBlank()) append("- Details: ${markdownText(row.detail)}\n")
        }
    }

    /** Escape presentation text so diagnostics and declaration names cannot add Markdown structure or HTML. */
    private fun markdownText(value: String): String = buildString {
        safe(value).replace(Regex("[\\p{Cntrl}]+"), " ").forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\\', '`', '*', '_', '[', ']', '(', ')', '#', '|', '~' -> append('\\').append(character)
                else -> append(character)
            }
        }
    }

    private fun safe(value: String) = DiagnosticSanitizer.sanitize(value)

    private fun sourcePath(value: String, basePath: String?): String = runCatching {
        val source = Path.of(value).normalize()
        val base = basePath?.let { Path.of(it).normalize() }
        if (!source.isAbsolute || base == null || !base.isAbsolute || !source.startsWith(base)) "<outside-project>"
        else base.relativize(source).toString().ifEmpty { "." }
    }.getOrDefault("<unavailable>")
}
