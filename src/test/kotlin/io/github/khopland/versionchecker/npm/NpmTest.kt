package io.github.khopland.versionchecker.npm

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

    fun testRegistryDocument() = runBlocking {
        val releases = NpmLookup.parse("""
            {"name": "x", "dist-tags": {"latest": "2.0.0"}, "versions": {"1.0.0": {"dist": {"tarball": "t", "fileCount": 3}, "dependencies": {"a": "1"}},
             "1.0.1": {"deprecated": "Security issue"}, "1.1.0": {"deprecated": ""}, "2.0.0-rc.1": {}, "2.0.0": {}}, "modified": "2024"}
        """.reader())
        assertEquals(listOf("1.0.0", "1.0.1", "1.1.0", "2.0.0"), releases.versions)
        assertEquals(Targets("1.0.1", "1.1.0", "2.0.0"), VersionService.outcome("1.0.0", releases, null).targets)
        assertEquals("Security issue", VersionService.outcome("1.0.1", releases, null).deprecated)
    }

    fun testMinimumReleaseAgeReadsTheFullDocumentOnceAndOnlyForAnUpdate() = runBlocking {
        FakeRepository().use { repository ->
            val day = java.util.concurrent.TimeUnit.DAYS.toMillis(1)
            val now = System.currentTimeMillis()
            fun date(age: Long) = java.time.Instant.ofEpochMilli(now - age).toString()
            // One route answers both requests: the lookup reads `versions`, and the release times read `time`.
            repository.routes["/lib"] = FakeRepository.Response(200, """
                {"name": "lib", "versions": {"1.0.0": {"dist": {}}, "1.0.1": {}, "1.1.0": {}, "2.0.0": {}},
                 "time": {"created": "${date(90 * day)}", "modified": "${date(0)}", "1.0.0": "${date(90 * day)}", "1.0.1": "${date(30 * day)}",
                          "1.1.0": "${date(3 * day)}", "2.0.0": "${date(day / 2)}", "unpublished": {"time": "x"}},
                 "readme": "…"}""")
            repository.routes["/plain"] = FakeRepository.Response(200, """{"versions": {"1.0.0": {}, "2.0.0": {}}}""")
            val registry = NpmRegistry(repository.url + "/", emptyMap())
            val releases = NpmLookup("lib", registry, http = { repository.http }).fetch()
            assertEquals(Targets("1.0.1", "1.1.0", "2.0.0"), VersionService.outcome("1.0.0", releases, null).targets)
            assertEquals(Targets(null, null, null), VersionService.outcome("2.0.0", releases, now - day).targets)
            assertEquals("No update and no cutoff need no release times", listOf("GET /lib"), repository.requests)
            assertEquals(Targets("1.0.1", "1.1.0", "1.1.0"), VersionService.outcome("1.0.0", releases, now - day).targets)
            assertEquals(Targets("1.0.1", "1.0.1", "1.0.1"), VersionService.outcome("1.0.0", releases, now - 7 * day).targets)
            assertEquals(listOf("GET /lib", "GET /lib"), repository.requests)

            // A registry without release times cannot hold a version back, and one that fails hides the update.
            val plain = NpmLookup("plain", registry, http = { repository.http }).fetch()
            assertEquals(Targets(null, null, "2.0.0"), VersionService.outcome("1.0.0", plain, now - day).targets)
            val failing = NpmLookup("lib", registry, http = { repository.http }).fetch()
            repository.routes["/lib"] = FakeRepository.Response(500)
            assertEquals(Targets(null, null, null), VersionService.outcome("1.0.0", failing, now - day).targets)
        }
    }

    fun testKeepListKeepsAVersion() {
        myFixture.enableInspections(io.github.khopland.versionchecker.core.OutdatedVersionInspection())
        val service = VersionService.getInstance()
        val previous = service.fetch
        service.fetch = { io.github.khopland.versionchecker.core.Releases(listOf("1.0.0", "2.0.0", "9.0.0", "10.0.0")) }
        service.clear()
        try {
            myFixture.addFileToProject("package.json", """{"name": "root", "versionChecker": {"keep": ["lodash"]}}""")
            myFixture.addFileToProject("packages/app/package.json", """
                {
                  "name": "app",
                  "packageManager": "pnpm@9.0.0",
                  "dependencies": {"react": "^1.0.0", "lodash": "1.0.0", "left-pad": "1.0.0"},
                  "overrides": {"parent>qs": "1.0.0"},
                  "versionChecker": {"keep": {"react": "waiting for the new types", "qs": "", "pnpm": ""}}
                }
            """.trimIndent())
            myFixture.configureFromTempProjectFile("packages/app/package.json")
            fun outdated() = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING }.mapNotNull { it.description }
            assertEquals(listOf("left-pad 1.0.0 → 10.0.0 (major)"), outdated())

            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("\"left-pad\": \"1.0.0") + 13)
            myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Keep this version" })
            assertTrue(myFixture.editor.document.text, Regex("\"pnpm\": \"\",\\s*\"left-pad\": \"\"").containsMatchIn(myFixture.editor.document.text))
            assertEquals(emptyList<String>(), outdated())
        } finally {
            service.fetch = previous
            service.clear()
        }
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

    fun testNexusIqApplicationIsNamedAfterThePackageOrTheWorkspace() {
        myFixture.addFileToProject("package.json", """{"name": "shop", "workspaces": ["packages/*"]}""")
        val web = myFixture.addFileToProject("packages/web/package.json", """{"name": "@shop/web"}""")
        assertEquals(listOf("@shop/web", "web", "shop"), NpmProvider().application(web)?.names)
        assertNull(NpmProvider().application(web)?.declared)
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

    fun testPackageManagerOverridesAndCatalogs() {
        val file = myFixture.configureByText("package.json", """
            {
              "packageManager": "yarn@4.5.0",
              "overrides": {"semver": "7.5.2", "foo": {".": "^1.0.0", "bar": "~2.0.0"}, "baz": "${'$'}baz"},
              "resolutions": {"**/minimist": "1.2.8", "@corp/a/@corp/b": "1.0.0", "lodash@^4.0.0": "4.17.21"},
              "pnpm": {"overrides": {"parent>qs": "6.11.0"}},
              "workspaces": {"packages": ["apps/*"], "catalog": {"react": "^18.2.0"}, "catalogs": {"legacy": {"react": "^17.0.2"}}}
            }
        """.trimIndent())
        val declarations = NpmProvider().collect(file)
        assertEquals(listOf("packageManager yarn", "semver (override)", "foo (override)", "bar (override)", "minimist (override)",
            "@corp/b (override)", "lodash (override)", "qs (override)", "react", "react"), declarations.map { it.name })
        assertEquals("4.5.0", declarations[0].range.substring(file.text))
        assertEquals("@yarnpkg/cli-dist", (declarations[0].lookup as NpmLookup).packageName)
        assertEquals("^1.0.0", declarations[2].range.substring(file.text))
        assertEquals("~2.1.0", declarations[3].edit!!.format("2.1.0"))
        assertEquals(listOf("18.2.0", "17.0.2"), declarations.takeLast(2).map { it.current })
    }

    fun testPackageManagerWithHashHasNoFix() {
        val file = myFixture.configureByText("package.json", """{"packageManager": "pnpm@9.12.0+sha512.abc", "name": "x"}""")
        val declaration = NpmProvider().collect(file).single()
        assertEquals("pnpm", (declaration.lookup as NpmLookup).packageName)
        assertEquals("9.12.0", declaration.current)
        assertNull(declaration.edit)
        assertEquals("The hash must match the version: run corepack use pnpm@<version>", declaration.note)
    }

    fun testOverrideTargets() {
        assertEquals("foo", NpmPackages.target("foo@^1.0.0"))
        assertEquals("@scope/foo", NpmPackages.target("@scope/foo@1"))
        assertEquals("foo", NpmPackages.target("a>b>foo"))
        assertEquals("@s/b", NpmPackages.target("**/@s/b"))
        assertNull(NpmPackages.target("**"))
    }

    fun testPnpmWorkspace() {
        val text = """
            packages:
              - 'apps/*'
            catalog:
              react: ^18.2.0
              '@types/node': "20.11.0"   # pinned
              local: workspace:*
            catalogs:
              legacy:
                react: ~17.0.2
            overrides:
              "parent>qs": 6.11.0
            onlyBuiltDependencies:
              esbuild: 1.0.0
        """.trimIndent()
        val entries = PnpmWorkspace.entries(text)
        assertEquals(listOf("catalog react", "catalog @types/node", "catalog local", "legacy react", "overrides parent>qs"),
            entries.map { "${it.section} ${it.key}" })
        assertEquals(listOf("^18.2.0", "20.11.0", "workspace:*", "~17.0.2", "6.11.0"), entries.map { it.range.substring(text) })
        val file = myFixture.configureByText("pnpm-workspace.yaml", text)
        assertEquals(listOf("react", "@types/node", "react", "qs (override)"), PnpmWorkspaceProvider().collect(file).map { it.name })
    }

    fun testUnusedPnpmCatalogEntries() {
        myFixture.enableInspections(UnusedPnpmCatalogEntryInspection())
        myFixture.addFileToProject("package.json", """{"name": "root", "devDependencies": {"typescript": "catalog:"}, "pnpm": {"overrides": {"parent>qs": "catalog:"}}}""")
        myFixture.addFileToProject("packages/app/package.json", """{"dependencies": {"react": "catalog:react18", "lodash": "catalog:default", "left-pad": "^1.0.0"}}""")
        myFixture.addFileToProject("node_modules/x/package.json", """{"dependencies": {"left-pad": "catalog:"}}""")
        val file = myFixture.addFileToProject("pnpm-workspace.yaml", """
            packages:
              - packages/*
            catalog:
              typescript: ~5.4.5
              lodash: 4.17.21
              qs: 6.11.0
              left-pad: 1.3.0   # nobody
              react: ^19.0.0
              "@scope/tool": 1.0.0
            catalogs:
              react18:
                react: ^18.2.0
                react-dom: ^18.2.0
            overrides:
              "@scope/tool": "catalog:"
              semver: 7.5.2
        """.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val unused = myFixture.doHighlighting().filter { it.description?.startsWith("No package uses") == true }
        assertEquals(listOf("left-pad" to "No package uses left-pad", "react" to "No package uses react", "react-dom" to "No package uses react-dom from catalog react18"),
            unused.map { myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description })

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("left-pad") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused entry"))
        assertTrue(myFixture.editor.document.text.contains("  qs: 6.11.0\n  react: ^19.0.0\n"))
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
