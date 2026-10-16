package io.github.khopland.versionchecker

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import java.nio.file.Path
import java.time.Instant

/** Explicit presentation export. It contains no cached inputs, native diagnostics or edit authority. */
internal object DependencyReviewExport {
    fun json(rows: List<DependencyReviewRow>, basePath: String?, generatedAt: Instant = Instant.now(),
             nowNanos: Long = System.nanoTime()): String {
        val document = JsonObject().apply {
            addProperty("schemaVersion", 2)
            addProperty("generatedAt", generatedAt.toString())
            addProperty("generatedAtPurpose", "export-creation")
            addProperty("observationTimingBasis", "monotonic-durations-at-export")
            addProperty("scope", "cached-observations")
            addProperty("requiresFreshReviewBeforeEditing", true)
            add("results", JsonArray().apply {
                rows.forEach { row -> add(JsonObject().apply {
                    addProperty("buildSystem", safe(row.adapter))
                    addProperty("buildRoot", sourcePath(row.root, basePath))
                    addProperty("sourceFile", sourcePath(row.source.file, basePath))
                    addProperty("sourceLocation", safe(row.source.location))
                    addProperty("artifact", safe(row.artifact))
                    addProperty("currentVersion", safe(row.current))
                    row.available?.let { addProperty("availableVersion", safe(it)) }
                    row.kind?.let { addProperty("changeKind", it.name.lowercase()) }
                    addProperty("outcome", safe(row.outcome))
                    addProperty("detail", safe(row.detail))
                    addProperty("mode", row.mode.name.lowercase())
                    addProperty("checking", row.checking)
                    addProperty("checkFailed", row.checkFailed)
                    add("observationAgeMillis", DependencyReviewFreshness.elapsedMillis(row, nowNanos)
                        ?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                    add("cacheExpiresInMillis", DependencyReviewFreshness.remainingMillis(row, nowNanos)
                        ?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                    addProperty("cacheExpiryStatus", DependencyReviewFreshness.expiryStatus(row, nowNanos))
                }) }
            })
        }
        return GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document) + "\n"
    }

    private fun safe(value: String) = DiagnosticSanitizer.sanitize(value)

    private fun sourcePath(value: String, basePath: String?): String = runCatching {
        val source = Path.of(value).normalize()
        val base = basePath?.let { Path.of(it).normalize() }
        if (!source.isAbsolute || base == null || !base.isAbsolute || !source.startsWith(base)) "<outside-project>"
        else base.relativize(source).toString().ifEmpty { "." }
    }.getOrDefault("<unavailable>")
}
