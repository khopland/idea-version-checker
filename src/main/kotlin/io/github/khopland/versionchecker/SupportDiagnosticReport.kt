package io.github.khopland.versionchecker

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.SystemInfoRt
import io.github.khopland.versionchecker.core.DiagnosticSanitizer
import io.github.khopland.versionchecker.core.parseDeprecationPolicy
import java.time.Instant

/** Explicit allowlist: never serialize a settings component, policy snapshot or system properties. */
internal data class SupportReportSettings(
    val enabled: Boolean,
    val patch: VersionSeverity, val minor: VersionSeverity, val major: VersionSeverity,
    val other: VersionSeverity, val deprecated: VersionSeverity,
    val minimumReleaseAgeHours: Int
) {
    companion object {
        fun capture(options: VersionCheckerSettings.Options) = SupportReportSettings(
            options.enabled,
            options.patchSeverity, options.minorSeverity, options.majorSeverity, options.otherSeverity, options.deprecatedSeverity,
            options.mavenMinimumReleaseAgeHours.coerceAtLeast(0))
    }

    fun json() = JsonObject().apply {
        addProperty("source", "workspace-settings")
        addProperty("enabled", enabled)
        addProperty("patchSeverity", patch.name)
        addProperty("minorSeverity", minor.name)
        addProperty("majorSeverity", major.name)
        addProperty("otherSeverity", other.name)
        addProperty("deprecatedSeverity", deprecated.name)
        addProperty("mavenMinimumReleaseAgeHours", minimumReleaseAgeHours)
    }
}

internal data class SupportReportPolicy(
    val status: String, val sharedRules: Int?, val activeSharedIgnores: Int?,
    val sharedDeprecations: Int?, val sharedUpdateRestrictions: Int?, val localIgnores: Int?, val localDeprecations: Int?
) {
    companion object {
        fun capture(policy: ProjectPolicySnapshot?): SupportReportPolicy {
            if (policy == null) return SupportReportPolicy("unknown", null, null, null, null, null, null)
            val valid = policy.shared.diagnostic == null
            return SupportReportPolicy(if (!valid) "invalid" else if (policy.sharedText == null) "absent" else "valid",
                policy.shared.rules.size.takeIf { valid },
                policy.shared.rules.sumOf { rule -> rule.ignoredVersions.count { it.until == null || policy.observedAt.isBefore(it.until) } }.takeIf { valid },
                policy.shared.rules.count { it.deprecated != null }.takeIf { valid },
                policy.shared.rules.count { it.allowedUpdates != null }.takeIf { valid },
                policy.ignoredText.takeIf { it.length <= 1_048_576 }?.let { ignoredVersions(it).size },
                policy.deprecatedText.takeIf { it.length <= 1_048_576 }?.let { parseDeprecationPolicy(it).size })
        }
    }

    fun json() = JsonObject().apply {
        addProperty("sharedSource", "project-root/$PROJECT_POLICY_FILE")
        addProperty("workspaceSource", "workspace-settings")
        addProperty("sharedStatus", status.takeIf { it in setOf("absent", "valid", "invalid", "unknown") } ?: "unknown")
        addProperty("sharedRuleCount", sharedRules)
        addProperty("activeSharedIgnoreCount", activeSharedIgnores)
        addProperty("sharedDeprecationCount", sharedDeprecations)
        addProperty("sharedUpdateRestrictionCount", sharedUpdateRestrictions)
        addProperty("workspaceIgnoreCount", localIgnores)
        addProperty("workspaceDeprecationCount", localDeprecations)
        addProperty("precedence", "Workspace rules add restrictions; shared rules remain applicable.")
        addProperty("content", "Rule identities, reasons, policy text and parser diagnostics are omitted.")
    }
}

internal data class SupportReportEnvironment(
    val pluginVersion: String?, val ideVersion: String?, val ideBuild: String?,
    val jvmVersion: String?, val osFamily: String?, val osVersion: String?
) {
    companion object {
        private val release = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:-(?:SNAPSHOT|alpha[0-9]*|beta[0-9]*|rc[0-9]*))?")
        private val build = Regex("(?:[A-Z]{2,4}-)?[0-9]+(?:\\.[0-9]+){1,4}")
        private val numericPrefix = Regex("^[0-9]+(?:\\.[0-9]+){0,3}")
        internal fun bundledPluginVersion(): String? = runCatching {
            SupportReportEnvironment::class.java.getResourceAsStream("support-build.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("pluginVersion")
            }
        }.getOrNull()

        fun capture(): SupportReportEnvironment {
            val app = ApplicationInfo.getInstance()
            val runtime = Runtime.version()
            return SupportReportEnvironment(
                bundledPluginVersion(),
                "${app.majorVersion}.${app.minorVersion}", app.build.asString(),
                "${runtime.feature()}.${runtime.interim()}.${runtime.update()}.${runtime.patch()}",
                when { SystemInfoRt.isMac -> "macOS"; SystemInfoRt.isWindows -> "Windows"; SystemInfoRt.isLinux -> "Linux"; else -> null },
                System.getProperty("os.version"))
        }
    }

    fun json() = JsonObject().apply {
        fun version(name: String, value: String?, pattern: Regex) {
            addProperty(name, value?.takeIf { it.length <= 80 && pattern.matches(it) })
        }
        version("pluginVersion", pluginVersion, release)
        version("ideVersion", ideVersion, release)
        version("ideBuild", ideBuild, build)
        version("jvmVersion", jvmVersion, release)
        addProperty("osFamily", osFamily?.takeIf { it in setOf("macOS", "Windows", "Linux") })
        addProperty("osVersion", osVersion?.takeIf { it.length <= 256 }?.let { numericPrefix.find(it)?.value })
        addProperty("unknownValues", "null means unavailable or outside the safe version format; runtime vendors, hostnames and usernames are omitted.")
    }
}

/** One frozen UI snapshot. Rendering or saving it never reads services, files or repositories. */
internal data class SupportDiagnosticReport(
    val displayed: List<DependencyReviewRow>, val totalCachedRows: Int, val filtersActive: Boolean,
    val checksActive: Boolean, val basePath: String?, val settings: SupportReportSettings?,
    val policy: SupportReportPolicy, val environment: SupportReportEnvironment?,
    val capturedAt: Instant, val nowNanos: Long
) {
    companion object {
        const val MAX_BYTES = 262_144
        const val MAX_ROWS = 128
        const val MAX_FIELD_CHARS = 512
        private const val MAX_INPUT_CHARS = 16_384
        private val controls = Regex("[\\p{Cntrl}\\p{Cf}]")
        private val urls = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"']+")
        private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    }

    fun json(): String {
        var limitedFields = 0
        fun safe(value: String): String {
            if (value.length > MAX_INPUT_CHARS) { limitedFields++; return "<omitted: oversized field>" }
            val text = urls.replace(DiagnosticSanitizer.sanitize(controls.replace(value, "")), "<url omitted>")
            return if (text.length > MAX_FIELD_CHARS) { limitedFields++; text.take(MAX_FIELD_CHARS - "<truncated>".length) + "<truncated>" } else text
        }
        fun boundedPath(value: String) = if (value.length <= MAX_INPUT_CHARS) value else {
            limitedFields++; ""
        }
        val bounded = displayed.take(MAX_ROWS).map { row -> row.copy(
            adapter = row.adapter.takeIf { it in setOf("maven", "npm", "pnpm", "yarn", "bun", "gradle") } ?: "unknown", root = boundedPath(row.root), artifact = safe(row.artifact), current = safe(row.current), available = row.available?.let(::safe),
            // Raw source paths stay private and are only passed to the established relative-path boundary.
            source = row.source.copy(file = boundedPath(row.source.file), location = safe(row.source.location)),
            origin = row.origin?.let { it.copy(checkSourceFile = boundedPath(it.checkSourceFile), coverageDescription = it.coverageDescription?.let(::safe)) },
            detail = "<omitted: support reports exclude free-text diagnostics>") }
        val results = DependencyReviewExport.jsonResults(bounded, basePath, nowNanos)
        // Apply the same control/length boundary to relative source paths produced by the shared exporter.
        results.forEach { entry ->
            val row = entry.asJsonObject
            if (!row.has("coverageScope")) row.add("coverageScope", JsonNull.INSTANCE)
            for (field in listOf("buildRoot", "sourceFile", "checkSourceFile")) row[field]?.let { row.addProperty(field, safe(it.asString)) }
        }
        val limits = JsonObject().apply {
            addProperty("maxBytes", MAX_BYTES); addProperty("maxRows", MAX_ROWS); addProperty("maxFieldChars", MAX_FIELD_CHARS)
            addProperty("limitedFieldCount", limitedFields)
        }
        val document = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("reportKind", "version-checker-support")
            addProperty("capturedAt", capturedAt.toString())
            addProperty("capturedAtPurpose", "report-creation-not-check-time")
            addProperty("scope", "displayed-cached-observations-in-displayed-order")
            addProperty("requiresFreshReviewBeforeEditing", true)
            addProperty("observationTimingBasis", "original-observation-durations-at-report-capture")
            addProperty("totalCachedRowsAtCapture", totalCachedRows)
            addProperty("displayedRowsAtCapture", displayed.size)
            addProperty("filtersActiveAtCapture", filtersActive)
            addProperty("checksActiveAtCapture", checksActive)
            add("displayedOutcomeCounts", JsonObject().apply {
                val counts = displayed.groupingBy { it.outcome }.eachCount()
                DependencyReviewOutcome.entries.forEach { addProperty(it.name.lowercase(), counts[it] ?: 0) }
            })
            add("environment", environment?.json() ?: JsonNull.INSTANCE)
            add("settings", settings?.json() ?: JsonNull.INSTANCE)
            add("policy", policy.json())
            add("limits", limits)
            add("limitations", JsonArray().apply {
                add("This is a partial cached UI snapshot, not a complete diagnosis or dependency audit. Filters and payload limits can omit observations.")
                add("Settings and policy status describe capture time; cached results may have been checked with earlier settings or inputs. Policy presence reflects IDE-visible state, not a fresh disk audit.")
                add("No checks or cache renewal were requested. Original observation age/expiry are frozen at capture, not at Save As time.")
                add("Cached observations do not establish unchanged build inputs, successful resolution, vulnerability fixes or compatibility, and never authorize edits.")
                add("No logs, free-text diagnostics, environment variables, machine identities, credentials, repository configuration, policy contents or file contents are collected. Package names and project-relative source paths remain; review before sharing.")
                add("Unknown values are explicit. Only supplied cached coverage descriptions are reported; missing coverage stays unknown.")
            })
            add("results", results)
        }
        fun render(): String {
            limits.addProperty("includedRows", results.size())
            limits.addProperty("omittedDisplayedRows", displayed.size - results.size())
            return gson.toJson(document) + "\n"
        }
        var rendered = render()
        while (rendered.toByteArray(Charsets.UTF_8).size > MAX_BYTES && results.size() > 0) {
            results.remove(results.size() - 1)
            rendered = render()
        }
        return rendered
    }
}
