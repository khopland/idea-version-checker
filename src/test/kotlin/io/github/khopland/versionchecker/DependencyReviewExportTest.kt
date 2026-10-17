package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.VersionChangeKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DependencyReviewExportTest {
    private val row = DependencyReviewRow("npm", "/project", DeclarationId("/project/packages/app/package.json", "dependencies/example"),
        "example", "1.0.0", "1.0.1", VersionChangeKind.PATCH, DependencyReviewOutcome.UPDATE, "Newer stable version", UpdateMode.PATCH, false)

    @Test fun `export preserves useful observations and marks them as presentation only`() {
        val document = JsonParser.parseString(DependencyReviewExport.json(listOf(row), "/project", Instant.EPOCH)).asJsonObject
        assertEquals(2, document.get("schemaVersion").asInt)
        assertEquals("1970-01-01T00:00:00Z", document.get("generatedAt").asString)
        assertTrue(document.get("requiresFreshReviewBeforeEditing").asBoolean)
        assertEquals("export-creation", document.get("generatedAtPurpose").asString)
        assertEquals("monotonic-durations-at-export", document.get("observationTimingBasis").asString)
        val result = document.getAsJsonArray("results").single().asJsonObject
        assertEquals(".", result.get("buildRoot").asString)
        assertEquals("packages/app/package.json", result.get("sourceFile").asString)
        assertEquals("1.0.1", result.get("availableVersion").asString)
        assertEquals("patch", result.get("changeKind").asString)
        assertFalse(result.has("fingerprint"))
        assertFalse(result.has("lease"))
    }

    @Test fun `export timing uses one monotonic sample and distinguishes unknown from expired`() {
        val nanos = java.util.concurrent.TimeUnit.SECONDS
        val checked = row.copy(checkedAtNanos = nanos.toNanos(1), expiresAtNanos = nanos.toNanos(11))
        val document = JsonParser.parseString(DependencyReviewExport.json(listOf(checked, row), "/project",
            Instant.EPOCH, nowNanos = nanos.toNanos(4))).asJsonObject
        val results = document.getAsJsonArray("results")
        val known = results[0].asJsonObject
        assertEquals(3_000L, known.get("observationAgeMillis").asLong)
        assertEquals(7_000L, known.get("cacheExpiresInMillis").asLong)
        assertEquals("unexpired", known.get("cacheExpiryStatus").asString)
        val unknown = results[1].asJsonObject
        assertTrue(unknown.get("observationAgeMillis").isJsonNull)
        assertTrue(unknown.get("cacheExpiresInMillis").isJsonNull)
        assertEquals("unknown", unknown.get("cacheExpiryStatus").asString)
        val expired = JsonParser.parseString(DependencyReviewExport.json(listOf(checked), "/project",
            Instant.EPOCH, nowNanos = nanos.toNanos(11))).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals(10_000L, expired.get("observationAgeMillis").asLong)
        assertEquals(0L, expired.get("cacheExpiresInMillis").asLong)
        assertEquals("expired", expired.get("cacheExpiryStatus").asString)
    }

    @Test fun `export generation wall clock cannot renew observation age or disclose absolute monotonic times`() {
        val checkedAt = 123_456_789_012_345_678L
        val checked = row.copy(checkedAtNanos = checkedAt, expiresAtNanos = checkedAt + 60_000_000_000)
        fun export(at: Instant) = DependencyReviewExport.json(listOf(checked), "/project", at, checkedAt + 1_000_000_000)
        val before = JsonParser.parseString(export(Instant.EPOCH)).asJsonObject
        val after = JsonParser.parseString(export(Instant.parse("2100-01-01T00:00:00Z"))).asJsonObject
        assertNotEquals(before.get("generatedAt"), after.get("generatedAt"))
        assertEquals(before.get("results"), after.get("results"))
        val exported = export(Instant.EPOCH)
        for (private in listOf(checkedAt.toString(), checked.expiresAtNanos.toString(), "checkedAtNanos", "expiresAtNanos",
            "snapshot", "fingerprint", "lease", "revision", "checkedAtEstimate", "publishedAt"))
            assertFalse(private, exported.contains(private))
    }

    @Test fun `export overflow timing stays unknown rather than fresh`() {
        val overflow = row.copy(checkedAtNanos = Long.MAX_VALUE, expiresAtNanos = Long.MIN_VALUE + 1)
        val result = JsonParser.parseString(DependencyReviewExport.json(listOf(overflow), "/project", Instant.EPOCH, Long.MIN_VALUE))
            .asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertTrue(result.get("observationAgeMillis").isJsonNull)
        assertTrue(result.get("cacheExpiresInMillis").isJsonNull)
        assertEquals("unknown", result.get("cacheExpiryStatus").asString)
    }

    @Test fun `export future timestamp is unknown rather than just observed`() {
        val future = row.copy(checkedAtNanos = 10, expiresAtNanos = 100)
        val result = JsonParser.parseString(DependencyReviewExport.json(listOf(future), "/project", Instant.EPOCH, 5))
            .asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertTrue(result.get("observationAgeMillis").isJsonNull)
        assertTrue(result.get("cacheExpiresInMillis").isJsonNull)
        assertEquals("unknown", result.get("cacheExpiryStatus").asString)
    }

    @Test fun `export redacts diagnostics and paths outside project without losing failure status`() {
        val failed = row.copy(root = "/private/user/work", source = DeclarationId("/project-other/private/pom.xml", "version"),
            detail = "https://user:password@registry.test/pkg?token=private-query in /Users/person/private-project/.npmrc\nAuthorization: Bearer private-header",
            available = null, outcome = DependencyReviewOutcome.FAILED, checking = true, checkFailed = true)
        val exported = DependencyReviewExport.json(listOf(failed), "/project", Instant.EPOCH)
        for (private in listOf("password", "private-query", "private-header", "/private/user", "/Users/person", "/project-other"))
            assertFalse(private, exported.contains(private))
        val result = JsonParser.parseString(exported).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("Failed", result.get("outcome").asString)
        assertTrue(result.get("checking").asBoolean)
        assertTrue(result.get("checkFailed").asBoolean)
        assertEquals("<outside-project>", result.get("sourceFile").asString)
        assertFalse(result.has("availableVersion"))
    }

    @Test fun `empty and missing project exports remain valid without filesystem discovery`() {
        assertEquals(0, JsonParser.parseString(DependencyReviewExport.json(emptyList(), null)).asJsonObject.getAsJsonArray("results").size())
        val result = JsonParser.parseString(DependencyReviewExport.json(listOf(row), null)).asJsonObject.getAsJsonArray("results").single().asJsonObject
        assertEquals("<outside-project>", result.get("buildRoot").asString)
    }
}
