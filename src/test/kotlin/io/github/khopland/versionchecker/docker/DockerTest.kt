package io.github.khopland.versionchecker.docker

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.DeclarationProvider
import io.github.khopland.versionchecker.core.Targets
import io.github.khopland.versionchecker.core.VersionService
import io.github.khopland.versionchecker.repo.FakeRepository
import kotlinx.coroutines.runBlocking

class DockerTest : BasePlatformTestCase() {
    fun testDockerfile() {
        val text = """
            # FROM node:18
            FROM --platform=${'$'}BUILDPLATFORM node:20.11.0-alpine AS build
            from ghcr.io/owner/tool:v1.2
            FROM localhost:5000/team/app:3
            FROM eclipse-temurin:21-jre@sha256:0123abcd
            FROM build
            FROM scratch
            FROM nginx
            FROM nginx:latest
            FROM nginx:${'$'}{NGINX_VERSION}-alpine
            COPY --from=busybox:1.36 /bin/sh /bin/sh
        """.trimIndent()
        val file = myFixture.configureByText("Dockerfile", text)
        val declarations = DockerfileProvider().collect(file)
        assertEquals(listOf("node" to "20.11.0-alpine", "ghcr.io/owner/tool" to "v1.2", "localhost:5000/team/app" to "3", "eclipse-temurin" to "21-jre"),
            declarations.map { it.name to it.range.substring(text) })
        assertEquals(listOf("docker:registry-1.docker.io/library/node:3", "docker:ghcr.io/owner/tool:v2", "docker:localhost:5000/team/app:1",
            "docker:registry-1.docker.io/library/eclipse-temurin:1"), declarations.map { it.lookup.key })
        assertNull("A digest names one build", declarations.last().edit)
        assertNotNull(declarations.last().note)
        assertEquals(DockerfileProvider::class.java, DeclarationProvider.forFile(file)?.javaClass)

        // The Docker plugin gives a Dockerfile its own language, which needs its own annotator and suppress comment.
        assertEquals("Dockerfile", file.language.id)
        assertTrue(com.intellij.lang.ExternalLanguageAnnotators.INSTANCE.allForLanguage(file.language).any { it is io.github.khopland.versionchecker.core.VersionAnnotator })
        assertTrue(com.intellij.codeInspection.LanguageInspectionSuppressors.INSTANCE.allForLanguage(file.language).any { it is io.github.khopland.versionchecker.core.LineCommentSuppressor })
        val provider = DockerfileProvider()
        listOf("Dockerfile.dev", "api.dockerfile", "Containerfile").forEach { assertTrue(it, provider.accepts(myFixture.configureByText(it, text))) }
        assertFalse(provider.accepts(myFixture.configureByText("Dockerfile-notes.txt", text)))
    }

    fun testComposeFile() {
        val text = """
            services:
              db:
                image: postgres:16.2-alpine   # the database
              cache:
                image: "docker.io/bitnami/redis:7.2.4"
              web:
                build: .
                image: registry.example.com/web:${'$'}{TAG:-1.0}
              proxy:
                image: nginx
        """.trimIndent()
        val file = myFixture.configureByText("compose.yaml", text)
        val provider = ComposeProvider()
        assertEquals(listOf("postgres" to "16.2-alpine", "bitnami/redis" to "7.2.4"), provider.collect(file).map { it.name to it.range.substring(text) })
        assertEquals(ComposeProvider::class.java, DeclarationProvider.forFile(file)?.javaClass)
        listOf("docker-compose.yml", "compose.prod.yaml", "docker-compose-dev.yml").forEach { assertTrue(it, provider.accepts(myFixture.configureByText(it, text))) }
        assertFalse(provider.accepts(myFixture.configureByText("deployment.yaml", text)))
    }

    fun testTagsKeepTheirShape() = runBlocking {
        val tags = listOf("3", "3.19", "3.19.1", "3.20", "3.20.3", "4", "20240923", "edge", "latest", "v3", "22-alpine", "22.11.0-alpine", "20-alpine", "20-slim", "24-rc-alpine")
        assertEquals(listOf("3", "4", "22-alpine", "20-alpine", "20-slim", "24-rc-alpine"), RegistryTags.shaped(tags, "3"))
        assertEquals(listOf("3.19", "3.20"), RegistryTags.shaped(tags, "3.18"))
        assertEquals(listOf("20240923"), RegistryTags.shaped(tags, "20240101"))
        assertEquals(listOf("v3"), RegistryTags.shaped(tags, "v2"))
        // The variant stays, and a release candidate is not offered.
        val releases = io.github.khopland.versionchecker.core.Releases(RegistryTags.shaped(tags, "20-alpine"), ignoresMinimumAge = true)
        assertEquals(Targets(null, null, "22-alpine"), VersionService.outcome("20-alpine", releases, null).targets)
    }

    fun testRegistryPagesAndPrivateImages() = runBlocking {
        FakeRepository().use { repository ->
            repository.routes["/v2/team/app/tags/list"] = FakeRepository.Response(200, """{"name":"team/app","tags":["1","2","2.1"]}""",
                mapOf("Link" to """</v2/team/app/tags/more?last=2.1&n=1000>; rel="next""""))
            repository.routes["/v2/team/app/tags/more"] = FakeRepository.Response(200, """{"name":"team/app","tags":["3","latest"]}""")
            val image = Image.parse("registry.example.com/team/app:1")!!
            val releases = RegistryTags(image, repository.url) { repository.http }.fetch()
            assertEquals(listOf("1", "2", "3"), releases.versions)
            assertTrue("The registry has no release times", releases.ignoresMinimumAge)

            // No token to get, and a token that the registry refuses.
            repository.routes["/v2/team/secret/tags/list"] = FakeRepository.Response(401, headers = mapOf("WWW-Authenticate" to "Basic realm=\"registry\""))
            assertEquals(emptyList<String>(), RegistryTags(Image.parse("registry.example.com/team/secret:1")!!, repository.url) { repository.http }.fetch().versions)
            assertEquals(emptyList<String>(), RegistryTags(Image.parse("registry.example.com/team/missing:1")!!, repository.url) { repository.http }.fetch().versions)
        }
    }

    fun testTokenAddress() {
        assertEquals("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Fnode%3Apull",
            RegistryTags.tokenUrl("""Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/node:pull"""", "library/node"))
        assertEquals("https://ghcr.io/token?scope=repository%3Ao%2Fr%3Apull", RegistryTags.tokenUrl("""Bearer realm="https://ghcr.io/token"""", "o/r"))
        assertNull(RegistryTags.tokenUrl("""Bearer realm="http://plain.example.com/token"""", "o/r"))
        assertNull(RegistryTags.tokenUrl("""Basic realm="registry"""", "o/r"))
        assertNull(RegistryTags.tokenUrl(null, "o/r"))
    }
}
