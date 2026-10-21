package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class DependencyReviewMarkdownExportTest {
    private val row = DependencyReviewRow("maven", "/project", DeclarationId("/project/parent/pom.xml", "dependency:g:a"),
        "g:a", "1.0", "1.1", VersionChangeKind.MINOR, DependencyReviewOutcome.UPDATE, "Newer release", UpdateMode.MAJOR, false,
        origin = DependencyReviewOrigin(BuildContextId("maven", "/private/context", "opaque-context"),
            DeclarationId("/private/check-input", "opaque-identity"), "/project/child/pom.xml", "Partial editor coverage"))

    @Test fun `summary preserves displayed order sources outcomes and coverage limitations`() {
        val markdown = DependencyReviewExport.markdown(listOf(row.copy(artifact = "g:z"), row), "/project", Instant.EPOCH, 0)
        assertTrue(markdown.startsWith("# Dependency review\n"))
        assertTrue(markdown.contains("Exported: 1970-01-01T00:00:00Z (report creation time, not check time)"))
        assertTrue(markdown.indexOf("## 1. g:z") < markdown.indexOf("## 2. g:a"))
        for (expected in listOf("2 displayed cached observations", "not a complete dependency audit", "Refresh and review before editing",
            "Source: parent/pom.xml; declaration: dependency:g:a", "Check source: child/pom.xml; coverage: Partial editor coverage",
            "Version: 1.0 → 1.1; mode: major; change: minor", "Outcome: Update; checking: no; check failed: no", "Details: Newer release"))
            assertTrue(expected, markdown.contains(expected))
        for (private in listOf("/private/context", "opaque-context", "opaque-identity", "check-input"))
            assertFalse(private, markdown.contains(private))
    }

    @Test fun `summary retains original monotonic ages expiry and explicit unknown timing`() {
        val seconds = TimeUnit.SECONDS
        val timed = row.copy(checkedAtNanos = seconds.toNanos(1), expiresAtNanos = seconds.toNanos(11))
        fun summary(at: Instant, now: Long) = DependencyReviewExport.markdown(listOf(timed, row), "/project", at, now)
        val markdown = summary(Instant.EPOCH, seconds.toNanos(4))
        assertTrue(markdown.contains("Observation: 3s ago; cache: Expires in 7s"))
        assertTrue(markdown.contains("Observation: Unknown; cache: Unknown"))
        assertEquals(markdown.substringAfter("## 1."),
            summary(Instant.parse("2100-01-01T00:00:00Z"), seconds.toNanos(4)).substringAfter("## 1."))
        assertTrue(summary(Instant.EPOCH, seconds.toNanos(11)).contains("Observation: 10s ago; cache: Expired"))
        assertFalse(markdown.contains(timed.checkedAtNanos.toString()))
        assertFalse(markdown.contains(timed.expiresAtNanos.toString()))
        val inconsistent = row.copy(checkedAtNanos = 10, expiresAtNanos = 100)
        assertTrue(DependencyReviewExport.markdown(listOf(inconsistent), "/project", Instant.EPOCH, 5)
            .contains("Observation: Unknown; cache: Unknown"))
    }

    @Test fun `summary sanitizes diagnostics and paths before escaping Markdown and HTML`() {
        val unsafe = row.copy(artifact = "**g:a** [open](https://user:private-password@registry.test/path?token=private-query)",
            root = "/private/external-root", source = DeclarationId("/project-other/private/pom.xml", "version\n## injected"),
            detail = "Authorization: Bearer private-header\n<script>alert(1)</script>\n# injected [link](https://registry.test/path?token=secret-query)",
            origin = row.origin!!.copy(checkSourceFile = "/Users/private/check/pom.xml"),
            outcome = DependencyReviewOutcome.FAILED, checking = true, checkFailed = true)
        val markdown = DependencyReviewExport.markdown(listOf(unsafe), "/project", Instant.EPOCH, 0)
        for (private in listOf("private-password", "private-query", "private-header", "secret-query", "/private/external-root", "/project-other", "/Users/private"))
            assertFalse(private, markdown.contains(private))
        assertTrue(markdown.contains("&lt;outside-project&gt;"))
        assertTrue(markdown.contains("Outcome: Failed; checking: yes; check failed: yes"))
        assertTrue(markdown.contains("\\*\\*g:a\\*\\* \\[open\\]\\("))
        assertTrue(markdown.contains("&lt;script&gt;alert\\(1\\)&lt;"))
        assertFalse(markdown.contains("<script>"))
        assertFalse(markdown.contains("\n## injected"))
        assertFalse(markdown.contains("[link]("))
    }

    @Test fun `empty summary and unknown coverage stay explicit without project discovery`() {
        val empty = DependencyReviewExport.markdown(emptyList(), null, Instant.EPOCH, 0)
        assertTrue(empty.contains("0 displayed cached observations"))
        assertTrue(empty.contains("No displayed cached observations"))
        val unknown = DependencyReviewExport.markdown(listOf(row.copy(origin = null, current = "", available = null,
            artifact = "", detail = "")), null, Instant.EPOCH, 0)
        assertTrue(unknown.contains("Unidentified declaration"))
        assertTrue(unknown.contains("Check source: Unknown; coverage: Unknown"))
        assertTrue(unknown.contains("Version: Unknown; mode: major"))
        assertFalse(unknown.contains("Details:"))
    }

    @Test fun `repeated summary creation neither queries providers nor extends original cache lease`() {
        var time = TimeUnit.SECONDS.toNanos(1)
        val cache = VersionResultCache(now = { time })
        val declaration = VersionDeclaration(row.source, ArtifactId("g", "a"), "1.0", "1.0")
        val snapshot = BuildSnapshot(BuildContextId("maven", "/project", "root"), "/project/pom.xml",
            BuildFingerprint(emptyMap(), ""), listOf(declaration))
        val deadline = TimeUnit.SECONDS.toNanos(11)
        assertTrue(cache.put(snapshot, cache.begin(snapshot), UpdateReport(listOf(UpdateCandidate(declaration, "1.1")),
            checkedAtNanos = time, validUntilNanos = deadline)))
        val originalLease = cache.getResult(snapshot, UpdateMode.MAJOR)!!.lease
        val displayed = DependencyReviewRows.from(cache.observations())
        repeat(3) { offset ->
            time = TimeUnit.SECONDS.toNanos(3L + offset)
            val markdown = DependencyReviewExport.markdown(displayed, "/project", Instant.EPOCH, time)
            assertTrue(markdown.contains("Observation: ${2 + offset}s ago; cache: Expires in ${8 - offset}s"))
            assertEquals(deadline, originalLease.expiresAtNanos)
            assertTrue(originalLease.isCurrent())
        }
        time = deadline
        assertTrue(DependencyReviewExport.markdown(displayed, "/project", Instant.EPOCH, time).contains("cache: Expired"))
        assertFalse(originalLease.isCurrent())
        assertTrue(cache.observations().isEmpty())
    }
}
