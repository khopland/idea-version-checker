package io.github.khopland.versionchecker.npm

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.Targets
import io.github.khopland.versionchecker.core.VersionService
import io.github.khopland.versionchecker.repo.FakeRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class NpmTest : BasePlatformTestCase() {
    fun testSpecs() {
        assertEquals(NpmSpec("^", "1.2.3"), NpmSpec.parse("^1.2.3"))
        assertEquals(NpmSpec("~", "1.2.3"), NpmSpec.parse("~1.2.3"))
        assertEquals(NpmSpec("", "1.2.3"), NpmSpec.parse("1.2.3"))
        listOf("^1.2", "1.x", "*", ">=1.0.0", "^1.0.0 || ^2.0.0", "latest", "workspace:*", "npm:other@1.0.0", "file:../x", "1.0.0-beta.1", " 1.0.0")
            .forEach { assertNull(it, NpmSpec.parse(it)) }
    }

    fun testNpmrcScopesAuthAndPrecedence() {
        val home = Files.createTempDirectory("home")
        val project = Files.createTempDirectory("project")
        val pkg = Files.createDirectories(project.resolve("packages/app"))
        Files.writeString(home.resolve(".npmrc"), "registry=https://ignored.example/\n//npm.corp.example/repo/:_authToken=user-token\n")
        Files.writeString(project.resolve(".npmrc"), """
            ; comment
            registry=https://npm.corp.example/repo/npm-all/
            @corp:registry=https://npm.corp.example/repo/npm-private
            //npm.corp.example/repo/npm-private/:_authToken=${'$'}{CORP_TOKEN}
        """.trimIndent())
        val npmrc = Npmrc.forDirectory(pkg, env = mapOf("CORP_TOKEN" to "secret"), home = home.toString())
        assertEquals(NpmRegistry("https://npm.corp.example/repo/npm-private/", mapOf("Authorization" to "Bearer secret")), npmrc.registry("@corp/lib"))
        // The longest matching prefix wins; a shorter one still applies to other paths on the host.
        assertEquals(NpmRegistry("https://npm.corp.example/repo/npm-all/", mapOf("Authorization" to "Bearer user-token")), npmrc.registry("react"))
        val overridden = Npmrc.forDirectory(pkg, env = mapOf("npm_config_registry" to "https://env.example/"), home = home.toString())
        assertEquals("https://env.example/", overridden.registry("react").url)
        assertEquals(Npmrc.DEFAULT, Npmrc.forDirectory(Files.createTempDirectory("empty"), env = emptyMap(), home = Files.createTempDirectory("h").toString()).registry("react").url)
    }

    fun testRegistryDocumentIgnoresTheMinimumReleaseAge() = runBlocking {
        val document = JsonParser.parseString("""
            {"versions": {"1.0.0": {}, "1.0.1": {"deprecated": "Security issue"}, "1.1.0": {}, "2.0.0-rc.1": {}, "2.0.0": {}}}
        """).asJsonObject
        val releases = NpmLookup.parse(document)
        assertEquals(listOf("1.0.0", "1.0.1", "1.1.0", "2.0.0"), releases.versions)
        // The registry quarantine already removed held-back versions, so a cutoff does not hide anything.
        val cutoff = System.currentTimeMillis()
        assertEquals(Targets("1.0.1", "1.1.0", "2.0.0"), VersionService.outcome("1.0.0", releases, cutoff).targets)
        assertEquals("Security issue", VersionService.outcome("1.0.1", releases, null).deprecated)
    }

    fun testLookupUsesScopedUrlHeadersAndTreatsMissingPackagesAsEmpty() = runBlocking {
        FakeRepository().use { repository ->
            // The server decodes %2f, so the route uses a slash.
            repository.routes["/@corp/lib"] = FakeRepository.Response(200, """{"versions": {"1.0.0": {}}}""")
            val registry = NpmRegistry(repository.url + "/", mapOf("Authorization" to "Bearer t"))
            assertEquals(listOf("1.0.0"), NpmLookup("@corp/lib", registry, http = { repository.http }).fetch().versions)
            assertEquals(listOf("Bearer t"), repository.authorizations)
            assertEquals(emptyList<String>(), NpmLookup("local-only", registry, http = { repository.http }).fetch().versions)
        }
    }

    fun testProviderKeepsTheOperator() {
        val file = myFixture.configureByText("package.json", """
            {"name": "app", "dependencies": {"react": "^18.2.0", "left-pad": "1.3.0", "lodash": "4.x"},
             "devDependencies": {"typescript": "~5.4.5", "local": "workspace:*"}, "peerDependencies": {"react": "^18.0.0"}}
        """.trimIndent())
        val declarations = NpmProvider().collect(file)
        assertEquals(listOf("react", "left-pad", "typescript"), declarations.map { it.name })
        assertEquals("^18.2.0", declarations[0].range.substring(file.text))
        assertEquals("^18.3.1", declarations[0].edit!!.format("18.3.1"))
        assertEquals("~5.6.2", declarations[2].edit!!.format("5.6.2"))
        assertEquals("18.2.0", declarations[0].current)
    }

    fun testDuplicateDependencies() {
        myFixture.enableInspections(DuplicateDependencyInspection())
        myFixture.configureByText("package.json", """
            {
              "dependencies": {"react": "^18.2.0", "left-pad": "1.3.0", "fsevents": "^2.3.0"},
              "devDependencies": {
                "typescript": "~5.4.5",
                "react": "^18.0.0",
                "eslint": "^9.0.0"
              },
              "optionalDependencies": {"fsevents": "^2.3.3"},
              "peerDependencies": {"typescript": "^5.0.0"}
            }
        """.trimIndent())
        val highlights = myFixture.doHighlighting().filter { it.inspectionToolId == "DuplicateNpmDependency" }
        assertEquals(listOf("fsevents is also in optionalDependencies (^2.3.3), which overrides this entry", "react is also in dependencies (^18.2.0)"),
            highlights.map { it.description }.sorted())

        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove from devDependencies" })
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("""
              "devDependencies": {
                "typescript": "~5.4.5",
                "eslint": "^9.0.0"
              },
        """.trimIndent().prependIndent("  ").trimStart()))
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove from dependencies" })
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains(""""dependencies": {"react": "^18.2.0", "left-pad": "1.3.0"},"""))
    }
}
