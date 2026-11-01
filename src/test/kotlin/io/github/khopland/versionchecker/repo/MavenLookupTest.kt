package io.github.khopland.versionchecker.repo

import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Targets
import io.github.khopland.versionchecker.core.VersionService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MavenLookupTest {
    private fun lookup(repository: FakeRepository, vararg repos: MavenRepo) =
        MavenLookup("org.example", "lib", repos.toList(), http = { repository.http }, times = { null })

    @Test
    fun mergesRepositoriesAndIgnoresMissingArtifacts() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/one/org/example/lib/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("1.0", "1.1", lastUpdated = "20240101000000"))
            repository.routes["/two/org/example/lib/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("1.1", "2.0", lastUpdated = "20250101000000"))
            val releases = lookup(repository, MavenRepo("one", "${repository.url}/one"), MavenRepo("two", "${repository.url}/two/"),
                MavenRepo("three", "${repository.url}/three")).fetch()
            assertEquals(listOf("1.0", "1.1", "2.0"), releases.versions)
            assertEquals(MavenLookup.parseLastUpdated("20250101000000"), releases.allPublishedBefore)
        }
    }

    @Test
    fun quarantineReadsPomDatesOnlyWhenNeeded() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/org/example/lib/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("1.0", "1.1", "1.2", lastUpdated = "20261031000000"))
            repository.routes["/org/example/lib/1.2/lib-1.2.pom"] = FakeRepository.Response(200, headers = mapOf("Last-Modified" to "Sat, 31 Oct 2026 00:00:00 GMT"))
            repository.routes["/org/example/lib/1.1/lib-1.1.pom"] = FakeRepository.Response(200, headers = mapOf("Last-Modified" to "Mon, 01 Jun 2026 00:00:00 GMT"))
            val releases = lookup(repository, MavenRepo("r", repository.url)).fetch()
            val cutoff = java.time.Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
            assertEquals(Targets(null, "1.1", "1.1"), VersionService.outcome("1.0", releases, cutoff).targets)
            assertEquals(listOf("GET /org/example/lib/maven-metadata.xml", "HEAD /org/example/lib/1.2/lib-1.2.pom", "HEAD /org/example/lib/1.1/lib-1.1.pom"),
                repository.requests)

            repository.requests.clear()
            val settled = java.time.Instant.parse("2026-11-30T00:00:00Z").toEpochMilli()
            assertEquals("1.2", VersionService.outcome("1.0", releases, settled).targets.major)
            assertEquals("Metadata older than the cutoff needs no POM requests", emptyList<String>(), repository.requests)
        }
    }

    @Test
    fun sendsCredentialsAndExplainsAuthFailures() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/org/example/lib/maven-metadata.xml"] = FakeRepository.Response(401)
            val repo = MavenRepo("corp", repository.url, mapOf("Authorization" to "Basic abc"), hint = "Add a <server> with id 'corp'")
            val error = runCatching { lookup(repository, repo).fetch() }.exceptionOrNull()
            assertTrue(error is HttpFailure)
            assertTrue(error!!.message, error.message!!.contains("needs credentials. Add a <server> with id 'corp'"))
            assertEquals(listOf("Basic abc"), repository.authorizations)
        }
    }
}
