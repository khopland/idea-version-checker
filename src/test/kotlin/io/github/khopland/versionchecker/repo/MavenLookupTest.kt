package io.github.khopland.versionchecker.repo

import io.github.khopland.versionchecker.core.HttpFailure
import io.github.khopland.versionchecker.core.Moved
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
            assertEquals(listOf("GET /org/example/lib/maven-metadata.xml", "GET /org/example/lib/1.2/lib-1.2.pom",
                "HEAD /org/example/lib/1.2/lib-1.2.pom", "HEAD /org/example/lib/1.1/lib-1.1.pom"),
                repository.requests)

            repository.requests.clear()
            val settled = java.time.Instant.parse("2026-11-30T00:00:00Z").toEpochMilli()
            assertEquals("1.2", VersionService.outcome("1.0", releases, settled).targets.major)
            assertEquals("Metadata older than the cutoff needs no POM requests", emptyList<String>(), repository.requests)
        }
    }

    @Test
    fun relocationInTheNewestPomIsAMove() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/org/example/lib/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("1.0", "1.1", "1.2-SNAPSHOT"))
            repository.routes["/org/example/lib/1.1/lib-1.1.pom"] = FakeRepository.Response(200, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><distributionManagement><relocation>
                  <groupId>com.example</groupId><message>New group</message>
                </relocation></distributionManagement></project>""".trimIndent())
            assertEquals(Moved("com.example:lib", message = "New group"), lookup(repository, MavenRepo("r", repository.url)).fetch().moved)

            repository.routes["/org/example/lib/1.1/lib-1.1.pom"] = FakeRepository.Response(200, "<project><version>1.1</version></project>")
            assertNull(lookup(repository, MavenRepo("r", repository.url)).fetch().moved)
        }
    }

    @Test
    fun jacksonMovesWhenTheArtifactHasAReleaseInTheNewGroup() = runBlocking {
        FakeRepository().use { repository ->
            fun jackson(group: String, artifact: String) = MavenLookup(group, artifact, listOf(MavenRepo("r", repository.url)), http = { repository.http }, times = { null })
            repository.routes["/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("2.20.0"))
            repository.routes["/tools/jackson/core/jackson-databind/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("3.0.0-rc1", "3.0.0", "3.1.0"))
            repository.routes["/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("2.20.0"))
            repository.routes["/tools/jackson/datatype/jackson-datatype-jsr310/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("3.0.0-rc2"))
            repository.routes["/com/fasterxml/jackson/core/jackson-annotations/maven-metadata.xml"] = FakeRepository.Response(200, FakeRepository.metadata("2.20"))

            assertEquals(Moved("tools.jackson.core:jackson-databind", "3.1.0"), jackson("com.fasterxml.jackson.core", "jackson-databind").fetch().moved)
            assertNull("Merged into jackson-databind in 3.0", jackson("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310").fetch().moved)
            assertNull("Jackson 3 still uses the old group", jackson("com.fasterxml.jackson.core", "jackson-annotations").fetch().moved)
        }
    }

    @Test
    fun knownMovesMapTablesAndPatterns() {
        assertEquals("jakarta.servlet:jakarta.servlet-api", MavenLookup.knownMove("javax.servlet", "javax.servlet-api"))
        assertEquals("tools.jackson.module:jackson-module-kotlin", MavenLookup.knownMove("com.fasterxml.jackson.module", "jackson-module-kotlin"))
        assertEquals("org.testcontainers:testcontainers-postgresql", MavenLookup.knownMove("org.testcontainers", "postgresql"))
        assertNull(MavenLookup.knownMove("org.testcontainers", "testcontainers"))
        assertNull(MavenLookup.knownMove("org.testcontainers", "testcontainers-postgresql"))
        assertEquals("org.bouncycastle:bcpkix-jdk18on", MavenLookup.knownMove("org.bouncycastle", "bcpkix-jdk15on"))
        assertNull(MavenLookup.knownMove("org.bouncycastle", "bcprov-jdk15to18"))
        assertEquals("software.amazon.awssdk:s3", MavenLookup.knownMove("com.amazonaws", "aws-java-sdk-s3"))
        assertNull(MavenLookup.knownMove("com.amazonaws", "aws-java-sdk-core"))
        assertEquals("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin",
            MavenLookup.knownMove("com.github.johnrengelman.shadow", "com.github.johnrengelman.shadow.gradle.plugin"))
        assertNull(MavenLookup.knownMove("org.example", "lib"))
    }

    @Test
    fun movedPluginIsNamedByItsId() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/com/github/johnrengelman/shadow/com.github.johnrengelman.shadow.gradle.plugin/maven-metadata.xml"] =
                FakeRepository.Response(200, FakeRepository.metadata("8.1.1"))
            repository.routes["/com/gradleup/shadow/com.gradleup.shadow.gradle.plugin/maven-metadata.xml"] =
                FakeRepository.Response(200, FakeRepository.metadata("9.0.0", "9.6.1"))
            val lookup = MavenLookup("com.github.johnrengelman.shadow", "com.github.johnrengelman.shadow.gradle.plugin",
                listOf(MavenRepo("r", repository.url)), http = { repository.http }, times = { null })
            assertEquals(Moved("com.gradleup.shadow", "9.6.1"), lookup.fetch().moved)
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
