package io.github.khopland.versionchecker.vuln

import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Http
import io.github.khopland.versionchecker.core.Pkg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.http.HttpClient

/**
 * Talks to a real Nexus IQ server. Skipped unless these are set:
 *
 * ```
 * NEXUS_IQ_URL=https://iq.example.com NEXUS_IQ_APP=my-app NEXUS_IQ_USER=me NEXUS_IQ_PASSWORD=token \
 *   ./gradlew test -Plive=true --tests '*LiveNexusIqTest'
 * ```
 *
 * The application should have the usual security policies, which flag Log4Shell in log4j-core 2.14.1. To see a waiver
 * work, waive that violation in the application and run it again: log4j-core should then have no advisories.
 */
class LiveNexusIqTest {
    private val env = listOf("NEXUS_IQ_URL", "NEXUS_IQ_APP", "NEXUS_IQ_USER", "NEXUS_IQ_PASSWORD").associateWith { System.getenv(it) }

    @Test
    fun evaluatesSuggestsAFixAndLinksTheReport() = runBlocking {
        assumeTrue(System.getProperty("versionchecker.live") == "true" && env.values.all { !it.isNullOrBlank() })
        val http = Http(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build())
        val source = NexusIqSource(env.getValue("NEXUS_IQ_URL")!!, env.getValue("NEXUS_IQ_APP")!!,
            env.getValue("NEXUS_IQ_USER"), env.getValue("NEXUS_IQ_PASSWORD"), { http })
        val log4j = Pkg(Ecosystem.MAVEN, "org.apache.logging.log4j:log4j-core", "2.14.1")
        val lodash = Pkg(Ecosystem.NPM, "lodash", "4.17.20")
        val scoped = Pkg(Ecosystem.NPM, "@babel/traverse", "7.22.0")
        val safe = Pkg(Ecosystem.MAVEN, "junit:junit", "4.13.2")

        assertTrue(source.application().isNotEmpty())
        val found = source.check(listOf(log4j, lodash, scoped, safe))
        found.forEach { (pkg, advisories) ->
            println("${pkg.name} ${pkg.version}: " + advisories.joinToString { "${it.displayId} (${it.severity}, ${it.summary}, fixed ${it.fixed})" })
        }
        assertFalse("junit 4.13.2 has no known vulnerabilities", safe in found)
        val log4jAdvisories = found[log4j]
        if (log4jAdvisories == null) println("log4j-core 2.14.1 has no active violations: waived, or no security policy flags it")
        else {
            assertTrue(log4jAdvisories.map { it.displayId }.toString(), log4jAdvisories.any { it.displayId == "CVE-2021-44228" })
            // Nexus IQ suggests a version without violations, newer than 2.14.1.
            val fix = log4jAdvisories.first().fixed.singleOrNull()
            println("Suggested log4j-core: $fix")
            if (fix != null) assertFalse(log4jAdvisories.first().affects(fix))
        }
        println("Report: ${source.reportUrl() ?: "none yet, the application was never scanned"}")
    }
}
