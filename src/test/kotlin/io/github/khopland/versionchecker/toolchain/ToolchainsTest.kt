package io.github.khopland.versionchecker.toolchain

import com.google.gson.JsonParser
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.actions.GitHubActionsProvider
import io.github.khopland.versionchecker.actions.GitHubTags
import io.github.khopland.versionchecker.core.ReleaseNotes
import io.github.khopland.versionchecker.core.Targets
import io.github.khopland.versionchecker.core.VersionService
import io.github.khopland.versionchecker.npm.NpmLookup
import io.github.khopland.versionchecker.repo.FakeRepository
import io.github.khopland.versionchecker.repo.MavenLookup
import io.github.khopland.versionchecker.repo.MavenRepo
import kotlinx.coroutines.runBlocking

class ToolchainsTest : BasePlatformTestCase() {
    fun testNodeVersionFiles() {
        val file = myFixture.configureByText(".nvmrc", "v20.11.0\n")
        assertEquals(PlainTextFileType.INSTANCE, file.fileType)
        val declaration = NodeVersionProvider().collect(file).single()
        assertEquals("20.11.0", declaration.range.substring(file.text))
        assertEquals("nodejs:20", declaration.lookup.key)
        assertEquals(emptyList<Any>(), NodeVersionProvider().collect(myFixture.configureByText(".node-version", "lts/iron\n")))
    }

    fun testNodeReleasesOfferLtsMajorsAndTheCurrentLine() = runBlocking {
        val index = """[
            {"version":"v25.1.0","date":"2026-10-01","lts":false},
            {"version":"v24.2.0","date":"2026-09-01","lts":"Krypton"},
            {"version":"v22.10.0","date":"2026-08-01","lts":"Jod"},
            {"version":"v21.7.3","date":"2024-04-10","lts":false},
            {"version":"v21.0.0","date":"2023-10-17","lts":false}]"""
        val releases = NodeReleases.parse(JsonParser.parseString(index).asJsonArray, 21)
        assertEquals(listOf("24.2.0", "22.10.0", "21.7.3", "21.0.0"), releases.versions)
        assertEquals(Targets(null, "21.7.3", "24.2.0"), VersionService.outcome("21.0.0", releases, null).targets)
        assertEquals(listOf("24.2.0", "22.10.0"), NodeReleases.parse(JsonParser.parseString(index).asJsonArray, 22).versions)
    }

    fun testSdkmanAndToolVersions() {
        val sdkman = myFixture.configureByText(".sdkmanrc", "# tools\njava=21.0.2-tem\nmaven=3.9.6\ngradle=latest\n")
        assertEquals(listOf("SDKMAN! java" to "21.0.2-tem", "SDKMAN! maven" to "3.9.6"),
            SdkmanProvider().collect(sdkman).map { it.name to it.range.substring(sdkman.text) })
        val tools = myFixture.configureByText(".tool-versions", "nodejs 20.11.0\njava temurin-21.0.2+13.0.LTS\nmaven 3.9.6 # comment\ngradle 8.5\n")
        assertEquals(listOf("Node.js", "Maven", "Gradle"), ToolVersionsProvider().collect(tools).map { it.name })
        assertEquals("darwinarm64", SdkmanLookup.platform("Mac OS X", "aarch64"))
        assertEquals("linuxx64", SdkmanLookup.platform("Linux", "amd64"))
    }

    fun testSdkmanJavaKeepsTheVendor() = runBlocking {
        val releases = io.github.khopland.versionchecker.core.Releases(listOf("21.0.5-tem", "23.0.1-tem", "21.0.6-zulu", "21.0.2-tem"))
        assertEquals(Targets("21.0.5-tem", "21.0.5-tem", "23.0.1-tem"), VersionService.outcome("21.0.2-tem", releases, null).targets)
    }

    fun testGitHubActions() {
        val text = """
            jobs:
              build:
                steps:
                  - uses: actions/checkout@v4
                  - uses: "actions/setup-java@v4.1.0"   # pinned
                  - uses: github/codeql-action/init@v3
                  - uses: actions/cache@0c45773b623bea8c8e75f6c82b208c3cf94ea4f9
                  - uses: some/action@main
                  - uses: ./local-action
                  - uses: docker://alpine:3.19
        """.trimIndent()
        myFixture.addFileToProject(".github/workflows/ci.yml", text)
        val file = myFixture.configureFromTempProjectFile(".github/workflows/ci.yml")
        val provider = GitHubActionsProvider()
        assertTrue(provider.accepts(file))
        assertEquals(listOf("actions/checkout" to "v4", "actions/setup-java" to "v4.1.0", "github/codeql-action" to "v3"),
            provider.collect(file).map { it.name to it.range.substring(text) })
        assertFalse(provider.accepts(myFixture.addFileToProject("config/ci.yml", text)))
    }

    fun testGitHubTagsKeepTheirShape() = runBlocking {
        val advertisement = "001e# service=git-upload-pack\n0000" + listOf("v3", "v4", "v4.0.0", "v4.1.2", "v5", "v5^{}", "5.0.0", "v6-beta", "nightly")
            .joinToString("") { "003fabc refs/tags/$it\n" }
        assertEquals(listOf("v3", "v4", "v4.0.0", "v4.1.2", "v5", "5.0.0", "v6-beta", "nightly"), GitHubTags.tags(advertisement))
        assertEquals("github-tags:actions/checkout:v1", GitHubTags("actions", "checkout", "v4").key)
        assertEquals("github-tags:actions/checkout:v3", GitHubTags("actions", "checkout", "v4.1.2").key)
        assertEquals(listOf("v3", "v4", "v5"), GitHubTags.shaped(GitHubTags.tags(advertisement), "v4"))
        assertEquals(listOf("v4.0.0", "v4.1.2"), GitHubTags.shaped(GitHubTags.tags(advertisement), "v4.1.0"))
    }

    fun testReleaseNotes() = runBlocking {
        assertEquals("https://github.com/o/r/releases", ReleaseNotes.github("scm:git:git@github.com:o/r.git"))
        assertEquals("https://github.com/o/r/releases", ReleaseNotes.github("https://github.com/o/r/tree/main/core"))
        assertEquals("https://example.com/project", ReleaseNotes.page("https://example.com/project"))
        assertNull(ReleaseNotes.page("scm:svn:svn://example.com/x"))
        assertEquals("https://github.com/o/r/releases", NpmLookup.releaseNotes(JsonParser.parseString("""{"repository": "github:o/r"}""").asJsonObject))
        assertEquals("https://github.com/o/r/releases",
            NpmLookup.releaseNotes(JsonParser.parseString("""{"repository": {"type": "git", "url": "git+https://github.com/o/r.git"}}""").asJsonObject))
        assertEquals("https://lodash.com/", NpmLookup.releaseNotes(JsonParser.parseString("""{"homepage": "https://lodash.com/"}""").asJsonObject))

        FakeRepository().use { repository ->
            val repo = MavenRepo("test", repository.url)
            repository.routes["/g/lib/2.0.0/lib-2.0.0.pom"] = FakeRepository.Response(200,
                "<project><parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent></project>")
            repository.routes["/g/parent/1/parent-1.pom"] = FakeRepository.Response(200,
                "<project><scm><url>https://github.com/o/lib</url></scm></project>")
            assertEquals("https://github.com/o/lib/releases", MavenLookup("g", "lib", listOf(repo), http = { repository.http }).releaseNotes("2.0.0"))
            assertEquals("https://plugins.gradle.org/plugin/com.example",
                MavenLookup("com.example", "com.example.gradle.plugin", listOf(repo), http = { repository.http }).releaseNotes("1.0"))
            assertNull(MavenLookup("g", "missing", listOf(repo), http = { repository.http }).releaseNotes("1.0"))
        }
    }
}
