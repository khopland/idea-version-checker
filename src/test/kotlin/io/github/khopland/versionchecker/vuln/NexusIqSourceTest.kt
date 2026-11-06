package io.github.khopland.versionchecker.vuln

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.repo.FakeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Evaluates against a local server that answers like Nexus IQ. */
class NexusIqSourceTest {
    private val log4j = Pkg(Ecosystem.MAVEN, "org.apache.logging.log4j:log4j-core", "2.14.1")
    private val babel = Pkg(Ecosystem.NPM, "@babel/traverse", "7.22.0")
    private val junit = Pkg(Ecosystem.MAVEN, "junit:junit", "4.13.2")

    private fun issue(ref: String, severity: Double, status: String = "Open") =
        """{"source": "cve", "reference": "$ref", "severity": $severity, "status": "$status", "threatCategory": "critical"}"""

    private fun violation(id: String, policy: String, refs: List<String>, extra: String = "", reason: String? = null) = """
        {"policyId": "p-$policy", "policyName": "$policy", "policyViolationId": "$id", "threatLevel": 9 $extra,
         "constraintViolations": [{"constraintId": "c", "constraintName": "CVSS >= 7", "reasons": [
           ${(refs.map { """{"reason": "Found security vulnerability $it", "reference": {"type": "SECURITY_VULNERABILITY_REFID", "value": "$it"}}""" } +
             listOfNotNull(reason?.let { """{"reason": "$it"}""" })).joinToString(",")}]}]}"""

    private val log4jResult = """
        {"component": {"packageUrl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar"},
         "securityData": {"securityIssues": [${issue("CVE-2021-44228", 10.0)}, ${issue("CVE-2021-45046", 9.0)},
            ${issue("CVE-2021-44832", 6.6)}, ${issue("sonatype-2021-4682", 5.0)}, ${issue("CVE-2021-45105", 5.9, "Not Applicable")}]},
         "policyData": {"policyViolations": [
            ${violation("v-critical", "Security-Critical", listOf("CVE-2021-44228"))},
            ${violation("v-waived", "Security-Critical", listOf("CVE-2021-45046"), extra = """, "waiveTime": "2026-10-01T10:00:00.000+0000"""")},
            ${violation("v-medium", "Security-Medium", listOf(), reason = "Found security vulnerability CVE-2021-44832 with severity >= 4")},
            ${violation("v-legacy", "Security-Medium", listOf("CVE-2021-45105"), extra = """, "legacyViolationTime": "2026-01-01T00:00:00.000+0000"""")},
            ${violation("v-fixed", "Security-Critical", listOf("CVE-2021-45046"), extra = """, "fixTime": "2026-10-02T10:00:00.000+0000"""")},
            ${violation("v-license", "License-Banned", listOf())}]}}"""

    @Test
    fun onlyIssuesThatAnActivePolicyViolationNamesAreReported() {
        val found = NexusIqSource.advisories(JsonParser.parseString(log4jResult).asJsonObject)
        // The waived and the not applicable issues and the one that no policy flags are left out.
        assertEquals(listOf("CVE-2021-44228", "CVE-2021-44832"), found.map { it.displayId })
        assertEquals(listOf("CRITICAL", "MODERATE"), found.map { it.severity })
        assertEquals(listOf("Security-Critical policy", "Security-Medium policy"), found.map { it.summary })
        assertTrue(found.all { it.affects("2.14.1") })
    }

    @Test
    fun packageUrls() {
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar", NexusIqSource.purl(log4j))
        assertEquals("pkg:npm/%40babel/traverse@7.22.0", NexusIqSource.purl(babel))
        assertEquals("pkg:npm/@babel/traverse@7.22.0", NexusIqSource.normalize("pkg:npm/%40babel/traverse@7.22.0"))
        assertEquals("pkg:maven/g/a@1.0+build", NexusIqSource.normalize(NexusIqSource.purl(Pkg(Ecosystem.MAVEN, "g:a", "1.0+build"))))
    }

    @Test
    fun evaluationMatchesResultsByPackageUrlAndFailsOnAnError() {
        // A rewritten package URL still matches by the one sent.
        val text = """{"results": [{"component": {"packageUrl": "pkg:npm/%40babel/traverse@7.22.0?x=y", "originalPurl": "pkg:npm/%40babel/traverse@7.22.0"}},
            {"component": {"packageUrl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?classifier=&type=jar"}}, $log4jResult]}"""
        val found = NexusIqSource.parseEvaluation(text, listOf(log4j, babel, junit))
        assertEquals(setOf(log4j, babel), found.keys)
        assertEquals(emptyList<Any>(), found.getValue(babel))
        val error = runCatching { NexusIqSource.parseEvaluation("""{"isError": true, "errorMessage": "boom", "results": []}""", listOf(log4j)) }.exceptionOrNull()
        assertEquals("Nexus IQ could not evaluate the dependencies: boom", error?.message)
    }

    @Test
    fun checkEvaluatesInTheApplicationAndAddsTheSuggestedFix() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-1", "publicId": "my-app"}]}""")
            repository.routes["/api/v2/evaluation/applications/app-1"] = FakeRepository.Response(200, """{"resultId": "r-1"}""")
            repository.routes["/api/v2/evaluation/applications/app-1/results/r-1"] = FakeRepository.Response(200, """{"results": [$log4jResult]}""")
            repository.routes["/api/v2/components/remediation/application/app-1"] = FakeRepository.Response(200, """
                {"remediation": {"versionChanges": [
                  {"type": "next-non-failing", "data": {"component": {"componentIdentifier": {"coordinates": {"version": "2.15.0"}}}}},
                  {"type": "next-no-violations", "data": {"component": {"packageUrl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.17.1?type=jar"}}}]}}""")
            val source = NexusIqSource(repository.url + "/", "my-app", "me", "token", { repository.http })

            val found = source.check(listOf(log4j, junit))
            assertEquals(setOf(log4j), found.keys)
            assertEquals(listOf(listOf("2.17.1")), found.getValue(log4j).map { it.fixed }.distinct())
            assertFalse(found.getValue(log4j).first().affects("2.17.1"))
            assertEquals(listOf("GET /api/v2/applications", "POST /api/v2/evaluation/applications/app-1",
                "GET /api/v2/evaluation/applications/app-1/results/r-1", "POST /api/v2/components/remediation/application/app-1"), repository.requests)
            assertTrue(repository.authorizations.all { it == Http.basicAuth("me", "token") })
            assertTrue(repository.bodies[1], repository.bodies[1].contains("pkg:maven/junit/junit@4.13.2?type=jar"))

            assertEquals("Open my-app in Nexus IQ to waive", source.actions(log4j, found.getValue(log4j)).single().text)

            // The newest report, where the user waives the violation.
            repository.routes["/api/v2/reports/applications/app-1"] = FakeRepository.Response(200, """[
                {"stage": "build", "evaluationDate": "2026-11-01T10:00:00.000+01:00", "reportHtmlUrl": "ui/links/application/my-app/report/old"},
                {"stage": "release", "evaluationDate": "2026-11-05T09:00:00.000Z", "reportHtmlUrl": "ui/links/application/my-app/report/new",
                 "latestReportHtmlUrl": "ui/links/application/my-app/latestReport/release"}]""")
            assertEquals("${repository.url}/ui/links/application/my-app/latestReport/release", source.reportUrl())
            repository.routes["/api/v2/reports/applications/app-1"] = FakeRepository.Response(200, "[]")
            assertNull(source.reportUrl())
        }
    }

    @Test
    fun failuresSayWhatToFix() = runBlocking {
        FakeRepository().use { repository ->
            fun error(source: NexusIqSource) = runBlocking { runCatching { source.check(listOf(log4j)) }.exceptionOrNull()?.message }
            repository.routes["/api/v2/applications"] = FakeRepository.Response(401)
            assertEquals("Nexus IQ did not accept the user name and password (HTTP 401)", error(NexusIqSource(repository.url, "my-app", "me", "wrong", { repository.http })))
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": []}""")
            assertEquals("Nexus IQ has no application my-app, or this user cannot see it", error(NexusIqSource(repository.url, "my-app", null, null, { repository.http })))
            // A wrong server URL: no API there, or a login page in its place.
            repository.routes["/api/v2/applications"] = FakeRepository.Response(404)
            assertEquals("No Nexus IQ API at ${repository.url}. Check the server URL", error(NexusIqSource(repository.url, "my-app", null, null, { repository.http })))
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, "<html><body>Sign in</body></html>")
            assertEquals("Nexus IQ did not answer with JSON. Check the server URL", error(NexusIqSource(repository.url, "my-app", null, null, { repository.http })))
            // A result that never gets ready times out.
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-1", "publicId": "my-app"}]}""")
            repository.routes["/api/v2/evaluation/applications/app-1"] = FakeRepository.Response(200, """{"resultId": "r-1"}""")
            val slow = NexusIqSource(repository.url, "my-app", null, null, { repository.http }, pollMillis = 10, timeoutMillis = 50)
            assertTrue(error(slow)!!.startsWith("Nexus IQ did not finish the evaluation"))
            assertNull(repository.authorizations.last())
        }
    }

    @Test
    fun anApplicationCreatedAgainIsLookedUpAgain() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-1", "publicId": "my-app"}]}""")
            val source = NexusIqSource(repository.url, "my-app", null, null, { repository.http })
            assertEquals("Nexus IQ returned HTTP 404 while evaluating dependencies", runCatching { source.check(listOf(log4j)) }.exceptionOrNull()?.message)
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-2", "publicId": "my-app"}]}""")
            repository.routes["/api/v2/evaluation/applications/app-2"] = FakeRepository.Response(200, """{"resultId": "r-1"}""")
            repository.routes["/api/v2/evaluation/applications/app-2/results/r-1"] = FakeRepository.Response(200, """{"results": []}""")
            assertEquals(emptyMap<Pkg, Any>(), source.check(listOf(log4j)))
            assertEquals(2, repository.requests.count { it == "GET /api/v2/applications" })
        }
    }

    @Test
    fun aVulnerabilityStaysWhileAnyViolationThatNamesItIsActive() {
        val result = JsonParser.parseString("""
            {"securityData": {"securityIssues": [${issue("CVE-2021-44228", 10.0)}]},
             "policyData": {"policyViolations": [
                ${violation("v-waived", "Security-Critical", listOf("CVE-2021-44228"), extra = """, "waiveTime": "2026-10-01T10:00:00.000+0000"""")},
                ${violation("v-active", "Security-High", listOf("CVE-2021-44228"))}]}}""").asJsonObject
        assertEquals(listOf("Security-High policy"), NexusIqSource.advisories(result).map { it.summary })
    }

    @Test
    fun anAbsoluteReportUrlIsKept() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/api/v2/applications"] = FakeRepository.Response(200, """{"applications": [{"id": "app-1", "publicId": "my-app"}]}""")
            repository.routes["/api/v2/reports/applications/app-1"] = FakeRepository.Response(200, """[{"reportHtmlUrl": "https://iq.example.com/ui/links/application/my-app/report/1"}]""")
            assertEquals("https://iq.example.com/ui/links/application/my-app/report/1", NexusIqSource(repository.url, "my-app", null, null, { repository.http }).reportUrl())
        }
    }
}
