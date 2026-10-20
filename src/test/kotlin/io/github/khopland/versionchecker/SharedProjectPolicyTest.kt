package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SharedProjectPolicyTest {
    private val declaration = VersionDeclaration(DeclarationId("package.json", "dependencies/library"),
        ArtifactId("npm", "@scope/library"), "^1.0.0", "1.0.0")
    private fun candidate(version: String = "1.1.0", kind: VersionChangeKind = VersionChangeKind.MINOR) =
        UpdateCandidate(declaration, version, kind = kind)
    private fun policy(rule: String, local: String = "", at: Instant = Instant.parse("2026-10-20T00:00:00Z")) =
        ProjectPolicySnapshot("""{"schemaVersion":1,"rules":[$rule]}""", local, "", observedAt = at)

    @Test fun `shared and workspace ignores intersect without changing raw reports or notices`() {
        val policy = policy("""{"ecosystem":"npm","package":"@scope/library","ignoredVersions":[{"version":"1.1.0"}]}""",
            "npm @scope/library = 1.2.0")
        val report = UpdateReport(listOf(candidate(), candidate("1.2.0"), candidate("1.3.0")),
            listOf(UpdateNotice(declaration, NoticeKind.MANUAL_REVIEW, "Keep this notice")),
            coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.CHECKED)))
        val filtered = VersionPresentationPolicy("npm", policy.ignoredText, policy).filter(report)
        assertEquals(listOf("1.3.0"), filtered.candidates.map { it.version })
        assertEquals(report.notices, filtered.notices)
        assertEquals(3, report.candidates.size)
        val restoredLocal = policy.copy(ignoredText = "")
        assertFalse(VersionPresentationPolicy("npm", "", restoredLocal).includes(candidate()))
    }

    @Test fun `matching is exact by ecosystem package and published version including Maven storage identity`() {
        val policy = policy("""{"ecosystem":"maven","package":"g:a","ignoredVersions":[{"version":"1.1.0"}]}""")
        val maven = candidate().copy(declaration = declaration.copy(artifact = ArtifactId("maven", "g:a")))
        assertFalse(VersionPresentationPolicy("maven", "", policy).includes(maven))
        assertTrue(VersionPresentationPolicy("gradle", "", policy).includes(maven))
        assertTrue(VersionPresentationPolicy("maven", "", policy).includes(maven.copy(version = "1.1.1")))
        assertTrue(VersionPresentationPolicy("maven", "", policy).includes(maven.copy(
            declaration = declaration.copy(artifact = ArtifactId("g", "other")))))
    }

    @Test fun `snoozed exact release returns at expiry and unbounded ignores stay active`() {
        val rule = """{"ecosystem":"npm","package":"@scope/library","ignoredVersions":[{"version":"1.1.0","until":"2026-10-20T00:00:00Z"},{"version":"1.2.0"}]}"""
        val before = policy(rule, at = Instant.parse("2026-10-19T23:59:59Z"))
        val at = policy(rule)
        assertNotNull(before.restriction("npm", candidate()))
        assertNull(at.restriction("npm", candidate()))
        assertNotNull(at.restriction("npm", candidate("1.2.0")))
    }

    @Test fun `shared update levels reject other and unknown changes unless explicitly allowed`() {
        val policy = policy("""{"ecosystem":"npm","package":"@scope/library","allowedUpdates":["patch"]}""")
        assertNull(policy.restriction("npm", candidate(kind = VersionChangeKind.PATCH)))
        for (kind in listOf(VersionChangeKind.MINOR, VersionChangeKind.MAJOR, VersionChangeKind.OTHER))
            assertTrue(policy.restriction("npm", candidate(kind = kind))!!.contains("shared policy"))
        val allBlocked = policy("""{"ecosystem":"npm","package":"@scope/library","allowedUpdates":[]}""")
        assertNotNull(allBlocked.restriction("npm", candidate(kind = VersionChangeKind.PATCH)))
    }

    @Test fun `shared deprecation wins its reason and local policy can only add more deprecated packages`() {
        val policy = policy("""{"ecosystem":"npm","package":"@scope/library","deprecated":"Use replacement"}""")
            .copy(deprecatedText = "@scope/library = local reason\nother = local additional rule")
        assertEquals("Use replacement (shared policy: .version-checker.json)", policy.deprecations("npm")["@scope/library"])
        assertEquals("local additional rule", policy.deprecations("npm")["other"])
        assertEquals("local reason", policy.deprecations("gradle")["@scope/library"])
        assertTrue(policy.restriction("npm", candidate())!!.contains("Use replacement"))
    }

    @Test fun `malformed unsupported ambiguous and broad rules fail closed with a diagnostic`() {
        val invalid = listOf("", "{}", "[]", "null", "{schemaVersion:1,rules:[]}", """{"schemaVersion":2,"rules":[]}""",
            """{"schemaVersion":1,"schemaVersion":1,"rules":[]}""",
            """{"schemaVersion":1,"rules":[],"scheduledChecks":true}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"all","package":"*"}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"*"}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"maven","package":"g:*"}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","ignoredVersions":[{"version":"*"}]}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","ignoredVersions":[{"version":"1.0.0","until":"tomorrow"}]}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","allowedUpdates":["breaking"]}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library","deprecated":false}]}""",
            """{"schemaVersion":1,"rules":[{"ecosystem":"npm","package":"library"},{"ecosystem":"npm","package":"library"}]}""")
        for (text in invalid) {
            val policy = ProjectPolicySnapshot(text, "", "")
            assertNotNull(text, policy.shared.diagnostic)
            val filtered = VersionPresentationPolicy("npm", "", policy).filter(UpdateReport(listOf(candidate())))
            assertFalse(text, filtered.successful)
            assertTrue(text, filtered.candidates.isEmpty())
        }
        assertNull(parseSharedProjectPolicy(null).diagnostic)
        assertNull(parseSharedProjectPolicy("""{"schemaVersion":1,"rules":[]}""").diagnostic)
    }

    @Test fun `ignore reason and expiry remain visible in shared provenance`() {
        val policy = policy("""{"ecosystem":"npm","package":"@scope/library","ignoredVersions":[{"version":"1.1.0","reason":"Await compatibility testing","until":"2026-12-01T00:00:00Z"}]}""")
        val reason = policy.restriction("npm", candidate())!!
        assertTrue(reason.contains("Await compatibility testing"))
        assertTrue(reason.contains("2026-12-01T00:00:00Z"))
        assertTrue(reason.contains(".version-checker.json"))
    }

    @Test fun `hidden coverage reports provenance without hiding notices`() {
        val policy = policy("""{"ecosystem":"npm","package":"@scope/library","ignoredVersions":[{"version":"1.1.0"}]}""")
        val report = UpdateReport(listOf(candidate()), coverage = mapOf(declaration.id to DeclarationCoverage(DeclarationOutcome.CHECKED)))
        val coverage = VersionPresentationPolicy("npm", "", policy).filter(report).coverage!![declaration.id]!!
        assertEquals(DeclarationOutcome.IGNORED, coverage.outcome)
        assertTrue(coverage.reason!!.contains(".version-checker.json"))
    }
}
