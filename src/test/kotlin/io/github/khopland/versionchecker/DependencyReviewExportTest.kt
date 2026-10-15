package io.github.khopland.versionchecker

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.DeclarationId
import io.github.khopland.versionchecker.core.VersionChangeKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DependencyReviewExportTest {
    private val row = DependencyReviewRow("npm", "/project", DeclarationId("/project/packages/app/package.json", "dependencies/example"),
        "example", "1.0.0", "1.0.1", VersionChangeKind.PATCH, "Update", "Newer stable version", UpdateMode.PATCH, false)

    @Test fun `export preserves useful observations and marks them as presentation only`() {
        val document = JsonParser.parseString(DependencyReviewExport.json(listOf(row), "/project", Instant.EPOCH)).asJsonObject
        assertEquals(1, document.get("schemaVersion").asInt)
        assertEquals("1970-01-01T00:00:00Z", document.get("generatedAt").asString)
        assertTrue(document.get("requiresFreshReviewBeforeEditing").asBoolean)
        val result = document.getAsJsonArray("results").single().asJsonObject
        assertEquals(".", result.get("buildRoot").asString)
        assertEquals("packages/app/package.json", result.get("sourceFile").asString)
        assertEquals("1.0.1", result.get("availableVersion").asString)
        assertEquals("patch", result.get("changeKind").asString)
        assertFalse(result.has("fingerprint"))
        assertFalse(result.has("lease"))
    }

    @Test fun `export redacts diagnostics and paths outside project without losing failure status`() {
        val failed = row.copy(root = "/private/user/work", source = DeclarationId("/project-other/private/pom.xml", "version"),
            detail = "https://user:password@registry.test/pkg?token=private-query in /Users/person/private-project/.npmrc\nAuthorization: Bearer private-header",
            available = null, outcome = "Failed", checking = true, checkFailed = true)
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
