package io.github.khopland.versionchecker.vuln

import com.google.gson.JsonParser
import io.github.khopland.versionchecker.core.AffectedRange
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.HttpCache
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.repo.FakeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.http.HttpClient
import java.nio.file.Files

/** Parses responses captured from api.osv.dev, and serves them from a local server. */
class OsvSourceTest {
    private fun resource(name: String) = javaClass.getResource("/osv/$name")!!.readText()
    private fun document(id: String) = JsonParser.parseString(resource("$id.json")).asJsonObject

    private val log4j = Pkg(Ecosystem.MAVEN, "org.apache.logging.log4j:log4j-core", "2.14.1")
    private val lodash = Pkg(Ecosystem.NPM, "lodash", "4.17.20")
    private val junit = Pkg(Ecosystem.MAVEN, "junit:junit", "4.13.2")

    @Test
    fun batchResultsAreInQueryOrder() {
        val results = OsvSource.parseBatch(resource("querybatch.json"))
        assertEquals(3, results.size)
        assertEquals(8, results[0].size)
        assertEquals(OsvSource.Ref("GHSA-35jh-r3h4-6jhm", "2026-10-17T02:55:38.085813Z"), results[1][1])
        assertEquals(emptyList<OsvSource.Ref>(), results[2])
        assertEquals(listOf(emptyList<OsvSource.Ref>(), listOf(OsvSource.Ref("A", null))),
            OsvSource.parseBatch("""{"results": [null, {"vulns": [{"modified": "x"}, {"id": "A"}]}]}"""))
    }

    @Test
    fun mavenAdvisoryHasTheRangesOfThePackageOnly() {
        val advisory = OsvSource.parse(document("GHSA-jfh8-c2jp-5v3q"), log4j)
        assertEquals("GHSA-jfh8-c2jp-5v3q", advisory.id)
        assertEquals("CVE-2021-44228", advisory.displayId)
        assertEquals("CRITICAL", advisory.severity)
        assertEquals("Remote code injection in Log4j", advisory.summary)
        // Three branches were fixed; ranges of other packages in the advisory, such as pax-logging, are left out.
        assertEquals(setOf("2.15.0", "2.3.1", "2.12.2"), advisory.fixed.toSet())
        assertTrue(AffectedRange("2.13.0", fixed = "2.15.0") in advisory.ranges)
        assertTrue(advisory.affects("2.14.1"))
        assertFalse(advisory.affects("2.15.0"))
        assertFalse(advisory.affects("2.12.2"))
        assertTrue(advisory.affects("2.12.1"))
        assertEquals("2.15.0", advisory.fixedFor("2.14.1"))
        assertEquals("2.12.2", advisory.fixedFor("2.11.0"))
    }

    @Test
    fun npmAdvisory() {
        val advisory = OsvSource.parse(document("GHSA-35jh-r3h4-6jhm"), lodash)
        assertEquals("CVE-2021-23337", advisory.displayId)
        assertEquals("HIGH", advisory.severity)
        assertEquals(listOf("4.17.21"), advisory.fixed)
        assertTrue(advisory.affects("4.17.20"))
        assertFalse(advisory.affects("4.17.21"))
        // lodash-es is another package in the same advisory.
        assertEquals(listOf("4.17.21"), OsvSource.parse(document("GHSA-35jh-r3h4-6jhm"), Pkg(Ecosystem.NPM, "lodash-es", "4.0.0")).fixed)
    }

    @Test
    fun severityFallsBackToCvssAndRangesCanStayOpen() {
        val document = JsonParser.parseString("""
            {"id": "OSV-1", "aliases": [], "severity": [{"type": "CVSS_V3", "score": "CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H"}],
             "affected": [{"package": {"ecosystem": "npm", "name": "x"}, "ranges": [{"type": "SEMVER",
               "events": [{"introduced": "0"}, {"fixed": "1.0.1"}, {"introduced": "2.0.0"}, {"last_affected": "2.0.3"}, {"introduced": "3.0.0"}]}]}]}
        """).asJsonObject
        val advisory = OsvSource.parse(document, Pkg(Ecosystem.NPM, "x", "1.0.0"))
        assertEquals("HIGH", advisory.severity)
        assertEquals("OSV-1", advisory.displayId)
        assertNull(advisory.summary)
        assertEquals(listOf(AffectedRange("0", fixed = "1.0.1"), AffectedRange("2.0.0", lastAffected = "2.0.3"), AffectedRange("3.0.0")), advisory.ranges)
        assertEquals(listOf(true, false, true, false, true), listOf("1.0.0", "1.0.1", "2.0.3", "2.0.4", "3.5.0").map(advisory::affects))
    }

    @Test
    fun cvss() {
        assertEquals(10.0, Cvss.score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H")!!, 0.0)
        assertEquals(7.2, Cvss.score("CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H")!!, 0.0)
        assertEquals(5.3, Cvss.score("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N")!!, 0.0)
        assertEquals("MODERATE", Cvss.rating("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"))
        assertNull(Cvss.rating("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
        assertNull(Cvss.score("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
    }

    @Test
    fun anUnreadableAdvisoryStillShowsAndAShortBatchFails() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/querybatch"] = FakeRepository.Response(200, """
                {"results": [{"vulns": [{"id": "GHSA-jfh8-c2jp-5v3q", "modified": "1"}, {"id": "GHSA-gone", "modified": "1"}]}]}""")
            repository.routes["/vulns/GHSA-jfh8-c2jp-5v3q"] = FakeRepository.Response(200, resource("GHSA-jfh8-c2jp-5v3q.json"))
            repository.routes["/vulns/GHSA-gone"] = FakeRepository.Response(500)
            val source = OsvSource({ repository.http }, repository.url)
            val found = source.check(listOf(log4j)).getValue(log4j)
            assertEquals(listOf("CVE-2021-44228", "GHSA-gone"), found.map { it.displayId })
            assertEquals(listOf(false, true), found.map { it.unread })
            // A document that does not parse counts as unread too.
            repository.routes["/vulns/GHSA-gone"] = FakeRepository.Response(200, """{"aliases": [{}]}""")
            assertEquals(listOf(false, true), source.check(listOf(log4j)).getValue(log4j).map { it.unread })
            // Fewer results than queries would leave packages unchecked, so the check fails instead.
            val error = runCatching { source.check(listOf(log4j, lodash)) }.exceptionOrNull()
            assertEquals("osv.dev returned 1 results for 2 packages", error?.message)
        }
    }

    @Test
    fun checkQueriesOnceAndKeepsAdvisoriesUntilTheyChange() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/querybatch"] = FakeRepository.Response(200, """
                {"results": [{"vulns": [{"id": "GHSA-jfh8-c2jp-5v3q", "modified": "2025-10-22T19:37:02Z"}]},
                             {"vulns": [{"id": "GHSA-35jh-r3h4-6jhm", "modified": "2026-10-17T02:55:38Z"}]}, {}]}""")
            repository.routes["/vulns/GHSA-jfh8-c2jp-5v3q"] = FakeRepository.Response(200, resource("GHSA-jfh8-c2jp-5v3q.json"))
            repository.routes["/vulns/GHSA-35jh-r3h4-6jhm"] = FakeRepository.Response(200, resource("GHSA-35jh-r3h4-6jhm.json"))
            val http = Http(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), HttpCache(Files.createTempDirectory("osv")))
            val source = OsvSource({ http }, repository.url)

            val found = source.check(listOf(log4j, lodash, junit))
            assertEquals(setOf(log4j, lodash), found.keys)
            assertEquals("CVE-2021-44228", found.getValue(log4j).single().displayId)
            assertEquals("CVE-2021-23337", found.getValue(lodash).single().displayId)
            assertEquals(3, repository.requests.size)

            repository.requests.clear()
            source.check(listOf(log4j, lodash, junit))
            assertEquals(listOf("POST /querybatch"), repository.requests)
        }
    }
}
