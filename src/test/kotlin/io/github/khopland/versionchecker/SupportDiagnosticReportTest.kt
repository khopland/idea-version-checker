package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class SupportDiagnosticReportTest {
    private val clock = TimeUnit.SECONDS.toNanos(100)
    private val row = DependencyReviewRow("npm", "/project", DeclarationId("/project/packages/app/package.json", "dependencies/example"),
        "example", "1.0.0", "1.0.1", VersionChangeKind.PATCH, DependencyReviewOutcome.UPDATE, "private diagnostic", UpdateMode.PATCH, false,
        checkedAtNanos = clock - TimeUnit.SECONDS.toNanos(20), expiresAtNanos = clock + TimeUnit.SECONDS.toNanos(40))
    private fun report(rows: List<DependencyReviewRow> = listOf(row)) = SupportDiagnosticReport(
        rows, rows.size, false, false, "/project", SupportReportSettings.capture(VersionCheckerSettings.Options()),
        SupportReportPolicy.capture(null), null, Instant.EPOCH, clock)

    @Test fun reportedPluginVersionMatchesThePackagedDescriptor() {
        val descriptor = ZipFile(System.getProperty("versionchecker.pluginArchive")).use { archive ->
            archive.entries().asSequence().filter { it.name.endsWith(".jar") }.mapNotNull { jar ->
                ZipInputStream(archive.getInputStream(jar)).use { contents ->
                    var descriptor: String? = null
                    while (true) {
                        val entry = contents.nextEntry ?: break
                        if (entry.name == "META-INF/plugin.xml") {
                            val xml = contents.readBytes().toString(Charsets.UTF_8)
                            if (xml.contains("<id>io.github.khopland.version-checker</id>")) descriptor = xml
                            break
                        }
                    }
                    descriptor
                }
            }.single()
        }
        val version = Regex("<version>([^<]+)</version>").find(descriptor)?.groupValues?.get(1)
        assertNotNull(version)
        assertEquals(version, SupportReportEnvironment.bundledPluginVersion())
    }

    @Test fun settingsAndPolicyOnlyContainAllowlistedScalarsAndCountsWithProvenance() {
        val settings = VersionCheckerSettings.Options(enabled = false, scheduledChecks = true, checkIntervalMinutes = -20,
            mavenMetadataThreads = 500, mavenMinimumReleaseAgeHours = -10,
            deprecatedDependencies = "private:library = password=workspace-deprecation-secret",
            ignoredVersions = "npm private-package = private-version-secret")
        val policy = ProjectPolicySnapshot("""{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"private-shared-name",
            "deprecated":"private-shared-reason","allowedUpdates":["patch"],"ignoredVersions":[
                {"version":"private-active-version","reason":"private-ignore-reason","until":"2100-01-01T00:00:00Z"},
                {"version":"private-expired-version","until":"2000-01-01T00:00:00Z"}]}]}""",
            settings.ignoredVersions, settings.deprecatedDependencies, observedAt = Instant.parse("2026-10-22T00:00:00Z"))
        val json = report().copy(settings = SupportReportSettings.capture(settings), policy = SupportReportPolicy.capture(policy)).json()
        for (secret in listOf("private:library", "workspace-deprecation-secret", "private-package", "private-version-secret",
            "private-shared-name", "private-shared-reason", "private-ignore-reason", "private-active-version", "private-expired-version"))
            assertFalse(secret, json.contains(secret))
        val document = JsonParser.parseString(json).asJsonObject
        val scalar = document.getAsJsonObject("settings")
        assertEquals("workspace-settings", scalar["source"].asString)
        assertFalse(scalar["scheduledChecksEffective"].asBoolean)
        assertEquals(1, scalar["checkIntervalMinutes"].asInt)
        assertEquals(4, scalar["mavenMetadataThreads"].asInt)
        assertEquals(0, scalar["mavenMinimumReleaseAgeHours"].asInt)
        assertFalse(scalar.has("ignoredVersions"))
        val provenance = document.getAsJsonObject("policy")
        assertEquals("project-root/.version-checker.json", provenance["sharedSource"].asString)
        assertEquals("valid", provenance["sharedStatus"].asString)
        for (field in listOf("sharedRuleCount", "activeSharedIgnoreCount", "sharedDeprecationCount", "sharedUpdateRestrictionCount",
            "workspaceIgnoreCount", "workspaceDeprecationCount")) assertEquals(field, 1, provenance[field].asInt)
        val invalid = SupportReportPolicy.capture(policy.copy(shared = SharedProjectPolicy(diagnostic = "token=private-parse-secret"))).json()
        assertEquals("invalid", invalid["sharedStatus"].asString)
        assertTrue(invalid["sharedRuleCount"].isJsonNull)
        assertFalse(invalid.toString().contains("private-parse-secret"))
        assertEquals("absent", SupportReportPolicy.capture(ProjectPolicySnapshot(null, "", "")).status)
        assertEquals("unknown", SupportReportPolicy.capture(null).status)
    }

    @Test fun environmentVersionsExcludeMachineIdentitiesAndUnrecognizedText() {
        val environment = SupportReportEnvironment("1.3.0-user-secret", "2026.1.4", "IU-261.26222.65", "21.0.9.0", "Linux", "6.8.0-user-secret@host-secret")
        val json = report().copy(environment = environment).json()
        assertFalse(json.contains("user-secret"))
        assertFalse(json.contains("host-secret"))
        val result = JsonParser.parseString(json).asJsonObject.getAsJsonObject("environment")
        assertTrue(result["pluginVersion"].isJsonNull)
        assertEquals("2026.1.4", result["ideVersion"].asString)
        assertEquals("IU-261.26222.65", result["ideBuild"].asString)
        assertEquals("21.0.9.0", result["jvmVersion"].asString)
        assertEquals("6.8.0", result["osVersion"].asString)
    }

    @Test fun diagnosticTextUrlsCredentialsControlsAndExternalPathsAreExcludedWithoutLosingOutcomes() {
        val failed = row.copy(root = "/Users/private-person/work", source = DeclarationId("/project-other/private-name/pom.xml", "version\u0000\u202E"),
            artifact = "safe-package token\u0000=private-token", current = "https://user:password@private-host.example/pkg?token=private-query",
            detail = "arbitrary native log private-free-text-secret", available = null,
            outcome = DependencyReviewOutcome.FAILED, checking = true, checkFailed = true,
            origin = DependencyReviewOrigin(BuildContextId("npm", "/project", "context"), row.source,
                "/outside/private-user/check/package.json", "Partial scope Authorization: Bearer private-header"))
        val json = report(listOf(failed)).json()
        for (secret in listOf("private-person", "private-name", "private-token", "private-host", "private-query", "private-free-text-secret",
            "private-user", "private-header", "\u0000", "\u202E")) assertFalse(secret, json.contains(secret))
        val result = JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("<outside-project>", result["sourceFile"].asString)
        assertEquals("<outside-project>", result["checkSourceFile"].asString)
        assertEquals("Failed", result["outcome"].asString)
        assertTrue(result["checking"].asBoolean)
        assertTrue(result["checkFailed"].asBoolean)
        assertEquals(20_000L, result["observationAgeMillis"].asLong)
    }

    @Test fun payloadLimitsStayValidJsonWithExplicitOmissionsAndOriginalCounts() {
        val dense = row.copy(artifact = "界".repeat(800), current = "界".repeat(800), available = "界".repeat(800),
            source = row.source.copy(location = "界".repeat(800)))
        val json = report(List(1000) { dense }).json()
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= SupportDiagnosticReport.MAX_BYTES)
        val document = JsonParser.parseString(json).asJsonObject
        val included = document.getAsJsonArray("results").size()
        assertTrue(included > 0 && included < SupportDiagnosticReport.MAX_ROWS)
        assertEquals(included, document.getAsJsonObject("limits")["includedRows"].asInt)
        assertEquals(1000 - included, document.getAsJsonObject("limits")["omittedDisplayedRows"].asInt)
        assertEquals(1000, document.getAsJsonObject("displayedOutcomeCounts")["update"].asInt)
        assertTrue(document.getAsJsonObject("limits")["limitedFieldCount"].asInt > 0)
        document.getAsJsonArray("results").forEach { entry ->
            for (field in listOf("artifact", "currentVersion", "availableVersion", "sourceLocation"))
                assertTrue(entry.asJsonObject[field].asString.length <= SupportDiagnosticReport.MAX_FIELD_CHARS)
        }
        val oversized = report(listOf(row.copy(artifact = "x".repeat(20_000), source = row.source.copy(file = "/" + "x".repeat(20_000))))).json()
        assertTrue(oversized.contains("oversized field"))
        assertFalse(oversized.contains("x".repeat(600)))
    }

    @Test fun reportIsDeterministicAndRetainsExpiredAndUnknownOriginalTimingWithoutEditAuthority() {
        val input = report(listOf(row.copy(expiresAtNanos = clock), row.copy(checkedAtNanos = null, expiresAtNanos = null)))
        assertEquals(input.json(), input.json())
        val document = JsonParser.parseString(input.json()).asJsonObject
        assertTrue(document["requiresFreshReviewBeforeEditing"].asBoolean)
        assertEquals("report-creation-not-check-time", document["capturedAtPurpose"].asString)
        val results = document.getAsJsonArray("results")
        assertEquals(20_000L, results[0].asJsonObject["observationAgeMillis"].asLong)
        assertEquals("expired", results[0].asJsonObject["cacheExpiryStatus"].asString)
        assertEquals(0L, results[0].asJsonObject["cacheExpiresInMillis"].asLong)
        assertTrue(results[1].asJsonObject["observationAgeMillis"].isJsonNull)
        assertEquals("unknown", results[1].asJsonObject["cacheExpiryStatus"].asString)
        assertTrue(results[1].asJsonObject["coverageScope"].isJsonNull)
        assertFalse(input.json().contains(clock.toString()))
        val empty = JsonParser.parseString(report(emptyList()).json()).asJsonObject
        assertEquals(0, empty.getAsJsonArray("results").size())
        assertTrue(empty["environment"].isJsonNull)
        assertEquals("unknown", empty.getAsJsonObject("policy")["sharedStatus"].asString)
    }
}
