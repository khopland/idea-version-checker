package io.github.khopland.versionchecker.npm

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.core.AffectedRange
import io.github.khopland.versionchecker.core.Advisory
import io.github.khopland.versionchecker.core.Ecosystem
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Pkg
import io.github.khopland.versionchecker.core.Releases
import io.github.khopland.versionchecker.core.VersionService
import io.github.khopland.versionchecker.core.VulnerabilityService
import io.github.khopland.versionchecker.core.VulnerabilitySource
import io.github.khopland.versionchecker.core.VulnerableTransitiveDependencyInspection

/** Vulnerable packages that `package-lock.json` installs below the dependencies in `package.json`. */
class NpmTransitiveTest : BasePlatformTestCase() {
    private class FakeSource : VulnerabilitySource {
        val queried = mutableListOf<Pkg>()
        override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> {
            synchronized(queried) { queried += packages }
            return packages.associateWith { pkg -> ADVISORIES[pkg.name].orEmpty().filter { it.affects(pkg.version) } }
        }
    }

    private val source = FakeSource()
    private lateinit var previousSource: VulnerabilitySource
    private lateinit var previousFetch: suspend (Lookup) -> Releases

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(VulnerableTransitiveDependencyInspection())
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = true
        previousSource = VulnerabilityService.getInstance().source
        previousFetch = VersionService.getInstance().fetch
        VulnerabilityService.getInstance().source = source
        VersionService.getInstance().fetch = { lookup -> Releases(VERSIONS[(lookup as NpmLookup).packageName].orEmpty()) }
        refresh()
    }

    override fun tearDown() {
        try {
            VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
            VulnerabilityService.getInstance().source = previousSource
            VersionService.getInstance().fetch = previousFetch
            refresh()
        } finally {
            super.tearDown()
        }
    }

    private fun refresh() {
        VersionService.getInstance().clear()
        VulnerabilityService.getInstance().clear()
    }

    private fun warnings() = myFixture.doHighlighting().filter { it.description?.startsWith("Brings in") == true }
        .map { myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description }

    private fun intentions(text: String): List<String> {
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(text) + 1)
        return myFixture.availableIntentions.map { it.text }
    }

    /** A lockfile with the root, and `location: version` entries with the dependencies after a `>`. */
    private fun lock(path: String, vararg entries: String, root: String = "") {
        val packages = entries.joinToString(",\n") { line ->
            val (location, rest) = line.split(": ", limit = 2)
            val version = rest.substringBefore(" >").trim()
            val dependencies = rest.substringAfter(" >", "").trim().split(' ').filter { it.isNotEmpty() }
            val fields = listOfNotNull(
                if (version == "link") "\"link\": true" else "\"version\": \"$version\"",
                dependencies.takeIf { it.isNotEmpty() }?.joinToString(", ", "\"dependencies\": {", "}") { "\"$it\": \"*\"" })
            "\"$location\": {${fields.joinToString(", ")}}"
        }
        myFixture.addFileToProject(path, """{"lockfileVersion": 3, "packages": {"": {$root}${if (packages.isEmpty()) "" else ",\n$packages"}}}""")
    }

    private fun transitive(path: String) = NpmProvider().transitive(myFixture.configureByFile(path))
        .map { "${it.via} > ${it.path.joinToString("/")} > ${it.pkg.name}@${it.pkg.version}" }

    fun testLockfileTree() {
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0", "app": "1.0.0", "other": "^2.0.0"}}""")
        lock("package-lock.json",
            "node_modules/web: 1.0.0 > json qs",
            "node_modules/json: 1.0.0 > qs",
            // web gets its own copy; json uses the hoisted one.
            "node_modules/web/node_modules/qs: 6.0.0",
            "node_modules/qs: 6.5.0",
            "node_modules/app: link",
            "packages/app: 1.0.0 > qs")
        assertEquals(listOf(
            "web >  > json@1.0.0",
            "web >  > qs@6.0.0",
            "web > json > qs@6.5.0"), transitive("package.json"))
    }

    fun testTheDeclaredCopyIsNotRepeated() {
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0", "qs": "^6.0.0"}}""")
        lock("package-lock.json",
            "node_modules/web: 1.0.0 > qs json",
            "node_modules/json: 1.0.0 > qs",
            "node_modules/json/node_modules/qs: 5.0.0",
            "node_modules/qs: 6.0.0")
        assertEquals(listOf("web >  > json@1.0.0", "web > json > qs@5.0.0"), transitive("package.json"))
    }

    fun testWorkspaceUsesTheRootLockfile() {
        myFixture.addFileToProject("packages/app/package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        myFixture.addFileToProject("package.json", """{"workspaces": ["packages/*"]}""")
        lock("package-lock.json",
            "packages/app: 1.0.0 > web",
            "packages/app/node_modules/web: 1.0.0 > qs",
            "node_modules/web: 2.0.0 > json",
            "node_modules/json: 1.0.0",
            "node_modules/qs: 6.0.0")
        assertEquals(listOf("web >  > qs@6.0.0"), transitive("packages/app/package.json"))
        // A folder the lockfile does not list as a workspace is not installed by it.
        myFixture.addFileToProject("tools/package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEmpty(transitive("tools/package.json"))
    }

    fun testLockfileVersion1IsIgnored() {
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        myFixture.addFileToProject("package-lock.json", """{"lockfileVersion": 1, "dependencies": {"web": {"version": "1.0.0"}}}""")
        assertEmpty(transitive("package.json"))
    }

    fun testWarningOnTheDependencyNameAndOverrideFix() {
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """
            {
              "name": "demo",
              "dependencies": {
                "web": "^1.0.0"
              }
            }
        """.trimIndent())
        assertEquals(listOf("web" to "Brings in qs 6.0.0: CVE-2022-24999 (high): Prototype pollution. Fixed in 6.2.4"), warnings())
        assertTrue(Pkg(Ecosystem.NPM, "qs", "6.0.0") in source.queried)

        intentions("\"web\"")
        assertTrue(myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in package.json"))!!.contains("\"qs@^6\": \"^6.2.4\""))
        myFixture.launchAction(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in package.json"))
        assertEquals("""
            {
              "name": "demo",
              "dependencies": {
                "web": "^1.0.0"
              },
              "overrides": {
                "qs@^6": "^6.2.4"
              }
            }
        """.trimIndent(), myFixture.editor.document.text)
    }

    fun testOverrideGoesIntoExistingOverrides() {
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": {"left-pad": "1.3.0"}}""")
        intentions("\"web\"")
        myFixture.launchAction(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in package.json"))
        val text = myFixture.editor.document.text
        assertTrue(text, Regex(""""overrides": \{"left-pad": "1.3.0",\s*"qs@\^6": "\^6.2.4"\s*}""").containsMatchIn(text))
    }

    fun testNoOverrideWhereNpmWouldRefuseIt() {
        // Already overridden for this major. A bare name overrides every copy, which counts too.
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": {"qs@^6": "6.0.0"}}""")
        assertEquals(1, warnings().size)
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": {"qs": "6.2.4"}}""")
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
        // npm refuses an override of a package the root depends on, peers included.
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "peerDependencies": {"qs": "^6.0.0"}}""")
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
    }

    fun testPnpmLockfile() {
        myFixture.addFileToProject("pnpm-lock.yaml", """
            lockfileVersion: '9.0'

            settings:
              autoInstallPeers: true

            importers:

              .:
                dependencies:
                  web:
                    specifier: ^1.0.0
                    version: 1.0.0(react@18.2.0)
                  shared:
                    specifier: workspace:*
                    version: link:packages/shared
                devDependencies:
                  '@scope/tool':
                    specifier: 1.0.0
                    version: 1.0.0

              packages/app:
                dependencies:
                  qs:
                    specifier: ^6.0.0
                    version: 6.11.0

            packages:

              qs@6.0.0:
                resolution: {integrity: sha512-x}

              web@1.0.0:
                resolution: {integrity: sha512-y}
                peerDependencies:
                  react: ^18.0.0

            snapshots:

              '@scope/tool@1.0.0':
                dependencies:
                  strip: strip-ansi@6.0.1

              qs@6.0.0: {}

              react@18.2.0: {}

              strip-ansi@6.0.1: {}

              web@1.0.0(react@18.2.0):
                dependencies:
                  qs: 6.0.0
                  react: 18.2.0
                transitivePeerDependencies:
                  - supports-color
        """.trimIndent())
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0", "shared": "workspace:*"}, "devDependencies": {"@scope/tool": "1.0.0"}}""")
        assertEquals(listOf("web >  > qs@6.0.0", "web >  > react@18.2.0", "@scope/tool >  > strip-ansi@6.0.1"), transitive("package.json"))
        myFixture.addFileToProject("packages/app/package.json", """{"dependencies": {"qs": "^6.0.0"}}""")
        assertEmpty(transitive("packages/app/package.json"))
    }

    fun testPnpmVersion6OfOnePackage() {
        myFixture.addFileToProject("pnpm-lock.yaml", """
            lockfileVersion: '6.0'

            dependencies:
              web:
                specifier: ^1.0.0
                version: 1.0.0

            packages:

              /qs@6.0.0:
                resolution: {integrity: sha512-x}
                dev: false

              /web@1.0.0:
                resolution: {integrity: sha512-y}
                dependencies:
                  qs: 6.0.0
                dev: false
        """.trimIndent())
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEquals(listOf("web >  > qs@6.0.0"), transitive("package.json"))
    }

    fun testTheNewerLockfileWins() {
        myFixture.addFileToProject("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        val pnpm = myFixture.addFileToProject("pnpm-lock.yaml", "lockfileVersion: '9.0'\nimporters:\n  .:\n    dependencies:\n      web:\n        version: 1.0.0\n" +
            "snapshots:\n  web@1.0.0:\n    dependencies:\n      qs: 6.2.4\n").virtualFile
        val npm = myFixture.findFileInTempDir("package-lock.json")
        WriteAction.runAndWait<Throwable> { pnpm.setBinaryContent(pnpm.contentsToByteArray(), -1, npm.timeStamp + 1000) }
        assertEquals(listOf("web >  > qs@6.2.4"), transitive("package.json"))
    }

    fun testNoOverrideAcrossMajors() {
        // The only safe semver is 7.5.2, and an override of semver@^5 to it would break the packages that need 5.
        lock("package-lock.json", "node_modules/web: 1.0.0 > semver", "node_modules/semver: 5.7.1")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEquals(1, warnings().size)
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
    }

    fun testNoOverrideWhereTheKeyIsNotAnObject() {
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": null}""")
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
    }

    private fun pnpmLock() = myFixture.addFileToProject("pnpm-lock.yaml", """
        lockfileVersion: '9.0'
        importers:
          .:
            dependencies:
              web:
                specifier: ^1.0.0
                version: 1.0.0
        snapshots:
          qs@6.0.0: {}
          web@1.0.0:
            dependencies:
              qs: 6.0.0
    """.trimIndent())

    fun testPnpmOverrideGoesToTheWorkspaceFile() {
        pnpmLock()
        myFixture.configureByText("package.json", """{"packageManager": "pnpm@11.0.0", "dependencies": {"web": "^1.0.0"}}""")
        intentions("\"web\"")
        myFixture.launchAction(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in pnpm-workspace.yaml"))
        val yaml = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("pnpm-workspace.yaml"))!!
        assertEquals("overrides:\n  'qs@^6': '^6.2.4'\n", yaml.text)
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
    }

    fun testPnpm9OverrideGoesToPackageJson() {
        pnpmLock()
        myFixture.configureByText("package.json", """{"packageManager": "pnpm@9.12.0", "dependencies": {"web": "^1.0.0"}}""")
        intentions("\"web\"")
        myFixture.launchAction(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in package.json"))
        val text = myFixture.editor.document.text
        assertTrue(text, Regex(""""pnpm": \{\s*"overrides": \{\s*"qs@\^6": "\^6.2.4"\s*}\s*}""").containsMatchIn(text))
    }

    fun testWhereThePnpmOverrideGoes() {
        fun target(packageJson: String, lockfileVersion: String = "9.0"): String? {
            val text = "lockfileVersion: '$lockfileVersion'\nimporters:\n  .:\n    dependencies:\n      web:\n        version: 1.0.0\n" +
                "snapshots:\n  qs@6.0.0: {}\n  web@1.0.0:\n    dependencies:\n      qs: 6.0.0\n"
            val existing = myFixture.tempDirFixture.getFile("pnpm-lock.yaml")
            if (existing == null) myFixture.addFileToProject("pnpm-lock.yaml", text)
            else WriteAction.runAndWait<Throwable> { existing.setBinaryContent(text.toByteArray()) }
            myFixture.configureByText("package.json", packageJson)
            return intentions("\"web\"").firstOrNull { it.startsWith("Override ") }?.substringAfterLast(" in ")
        }
        val overrides = """"pnpm": {"overrides": {"left-pad": "1.3.0"}}"""
        assertEquals("package.json", target("""{"packageManager": "pnpm@9.12.0", "dependencies": {"web": "^1.0.0"}}"""))
        // Every pnpm 10 reads package.json, and a workspace file with only overrides breaks 10.0 to 10.4.
        assertEquals("package.json", target("""{"packageManager": "pnpm@10.4.1", "dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("package.json", target("""{"packageManager": "pnpm@10.12.1", "dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("pnpm-workspace.yaml", target("""{"packageManager": "pnpm@11.0.0", "dependencies": {"web": "^1.0.0"}, $overrides}"""))
        // Unknown version: package.json, which works up to pnpm 10 and only warns on 11, rather than a file that breaks pnpm 9.
        assertEquals("package.json", target("""{"dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("package.json", target("""{"dependencies": {"web": "^1.0.0"}}""", "6.0"))
        // The last install says which pnpm it was: YAML before pnpm 11, JSON since.
        myFixture.addFileToProject("node_modules/.modules.yaml", "{\n  \"packageManager\": \"pnpm@11.28.9\"\n}\n")
        assertEquals("pnpm-workspace.yaml", target("""{"dependencies": {"web": "^1.0.0"}}"""))
        WriteAction.runAndWait<Throwable> { myFixture.findFileInTempDir("node_modules/.modules.yaml").setBinaryContent("packageManager: pnpm@9.15.9\n".toByteArray()) }
        assertEquals("package.json", target("""{"dependencies": {"web": "^1.0.0"}}"""))
        WriteAction.runAndWait<Throwable> { myFixture.findFileInTempDir("node_modules/.modules.yaml").delete(this) }
        // A workspace file that already has overrides is where the project keeps them: pnpm 10 would ignore them all
        // once package.json has some. Overrides in package.json win, since pnpm 10 reads those.
        myFixture.addFileToProject("pnpm-workspace.yaml", "packages:\n  - app\noverrides:\n  left-pad: 1.3.0\n")
        assertEquals("pnpm-workspace.yaml", target("""{"dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("pnpm-workspace.yaml", target("""{"packageManager": "pnpm@10.20.0", "dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("package.json", target("""{"packageManager": "pnpm@10.20.0", "dependencies": {"web": "^1.0.0"}, $overrides}"""))
        // pnpm before 10.5.2 ignore the workspace file's overrides, even beside packages.
        assertEquals("package.json", target("""{"packageManager": "pnpm@9.15.0", "dependencies": {"web": "^1.0.0"}}"""))
        assertEquals("package.json", target("""{"packageManager": "pnpm@10.5.1", "dependencies": {"web": "^1.0.0"}}"""))
        // pnpm 11 ignores package.json, so a stale override there does not block the fix.
        assertEquals("pnpm-workspace.yaml", target("""{"packageManager": "pnpm@11.0.0", "dependencies": {"web": "^1.0.0"}, "pnpm": {"overrides": {"qs": "6.2.4"}}}"""))
        // An override in either file counts.
        WriteAction.runAndWait<Throwable> {
            myFixture.findFileInTempDir("pnpm-workspace.yaml").setBinaryContent("packages:\n  - app\noverrides:\n  qs: 6.2.4\n".toByteArray())
        }
        assertNull(target("""{"packageManager": "pnpm@10.20.0", "dependencies": {"web": "^1.0.0"}, $overrides}"""))
    }

    fun testCopiesInOneRangeGetTheHighestOverride() {
        // qs 6.0.0 is safe at 6.2.4 and the nested qs 6.3.0 at 6.3.3; ^6.2.4 would leave 6.3.0 vulnerable.
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs json", "node_modules/web/node_modules/qs: 6.3.0", "node_modules/json: 1.0.0 > qs",
            "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0", "json": "^1.0.0"}}""")
        val overrides = { at: String -> intentions(at).filter { it.startsWith("Override ") } }
        assertEquals(listOf("Override qs@^6 to ^6.3.3 in package.json"), overrides("\"web\""))
        assertEquals(listOf("Override qs@^6 to ^6.3.3 in package.json"), overrides("\"json\""))
    }

    fun testWorkspaceFilePreviewShowsTheExistingFile() {
        pnpmLock()
        myFixture.addFileToProject("pnpm-workspace.yaml", "packages:\n  - app\n")
        myFixture.configureByText("package.json", """{"packageManager": "pnpm@11.0.0", "dependencies": {"web": "^1.0.0"}}""")
        intentions("\"web\"")
        val preview = myFixture.getIntentionPreviewText(myFixture.findSingleIntention("Override qs@^6 to ^6.2.4 in pnpm-workspace.yaml"))!!
        assertTrue(preview, "packages:" in preview && "'qs@^6': '^6.2.4'" in preview)
    }

    fun testOverrideKeys() {
        assertEquals("qs@^6", NpmOverrides.key("qs", "6.0.0", "6.2.4"))
        assertEquals("x@^0.3", NpmOverrides.key("x", "0.3.1", "0.3.4"))
        assertNull(NpmOverrides.key("x", "0.3.1", "0.4.0"))
        // Each 0.0.x is its own range, so a range value would move newer copies back.
        assertNull(NpmOverrides.key("x", "0.0.3", "0.0.4"))
    }

    fun testPnpmWorkspaceOverrides() {
        assertEquals("overrides:\n  'a@^1': '^1.0.1'\n", PnpmWorkspace.withOverride("", "a@^1", "^1.0.1"))
        assertEquals("packages:\n  - app\n\noverrides:\n  'a@^1': '^1.0.1'\n", PnpmWorkspace.withOverride("packages:\n  - app\n", "a@^1", "^1.0.1"))
        assertEquals("overrides:\n    b: 2.0.0\n    'a@^1': '^1.0.1'\n\ncatalog:\n  c: 1.0.0\n",
            PnpmWorkspace.withOverride("overrides:\n    b: 2.0.0\n\ncatalog:\n  c: 1.0.0\n", "a@^1", "^1.0.1"))
        assertEquals("overrides:\r\n  b: 2.0.0\r\n  'a@^1': '^1.0.1'\r\n", PnpmWorkspace.withOverride("overrides:\r\n  b: 2.0.0\r\n", "a@^1", "^1.0.1"))
        assertFalse(PnpmWorkspace.canAddOverride("overrides:\n  a: 1.0.1\n", "a", "a@^1"))
        assertFalse(PnpmWorkspace.canAddOverride("overrides:\n  'a@^1': 1.0.1\n", "a", "a@^1"))
        assertFalse(PnpmWorkspace.canAddOverride("overrides: {}\n", "a", "a@^1"))
        assertTrue(PnpmWorkspace.canAddOverride("overrides:\n  b: 1.0.1\n", "a", "a@^1"))
    }

    fun testPnpmReferencesWithoutARegistryVersion() {
        val lock = PnpmLock.parse("""
            lockfileVersion: '6.0'
            dependencies:
              web:
                specifier: ^1.0.0
                version: 1.0.0
            packages:
              /web@1.0.0:
                dependencies:
                  string-width-cjs: /string-width@4.2.3
                  '@s/alias': /@s/real@2.0.0
                  local: file:../local
                  git: git+ssh://git@github.com/a/b.git
                  tar: '@x/y@https://example.com/y.tgz'
              /local@file:../local:
                dependencies:
                  phantom: 1.0.0
              /string-width@4.2.3:
                dependencies:
                  ansi: 5.0.0
        """.trimIndent())!!
        val web = lock.direct("", "web")!!
        // A key with a colon is read whole, so its dependencies do not end up under the package before it.
        assertEquals(listOf("string-width@4.2.3", "@s/real@2.0.0"), lock.dependencies(web).map { "${it.name}@${it.version}" })
        assertEquals(listOf("ansi@5.0.0"), lock.dependencies(lock.dependencies(web).first()).map { "${it.name}@${it.version}" })
    }

    fun testPnpmWorkspacePackage() {
        myFixture.addFileToProject("pnpm-lock.yaml", """
            lockfileVersion: '9.0'
            importers:
              .: {}
              packages/app:
                dependencies:
                  web:
                    specifier: ^1.0.0
                    version: 1.0.0(react@18.2.0)
            snapshots:
              qs@6.0.0: {}
              web@1.0.0(react@18.2.0):
                dependencies:
                  qs: 6.0.0
        """.trimIndent())
        myFixture.addFileToProject("packages/app/package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEquals(listOf("web >  > qs@6.0.0"), transitive("packages/app/package.json"))
    }

    fun testPackageManagerPicksTheLockfile() {
        myFixture.addFileToProject("package.json", """{"packageManager": "npm@10.0.0", "dependencies": {"web": "^1.0.0"}}""")
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        val pnpm = myFixture.addFileToProject("pnpm-lock.yaml", "lockfileVersion: '9.0'\nimporters:\n  .:\n    dependencies:\n      web:\n        version: 1.0.0\n" +
            "snapshots:\n  web@1.0.0:\n    dependencies:\n      qs: 6.2.4\n").virtualFile
        val npm = myFixture.findFileInTempDir("package-lock.json")
        WriteAction.runAndWait<Throwable> { pnpm.setBinaryContent(pnpm.contentsToByteArray(), -1, npm.timeStamp + 1000) }
        assertEquals(listOf("web >  > qs@6.0.0"), transitive("package.json"))
    }

    fun testNothingIsSentWhenTheSettingIsOff() {
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEmpty(warnings())
        assertEmpty(source.queried)
    }

    companion object {
        private val VERSIONS = mapOf("qs" to listOf("6.0.0", "6.2.3", "6.2.4", "6.3.0", "6.3.3", "6.11.0"), "semver" to listOf("5.7.1", "7.5.2"))
        private val ADVISORIES = mapOf(
            "qs" to listOf(Advisory("GHSA-hrpp-h998-j3pp", listOf("CVE-2022-24999"), "HIGH", "Prototype pollution", listOf("6.2.4"),
                listOf(AffectedRange("6.0.0", fixed = "6.2.4"))),
                Advisory("GHSA-qs-6-3", emptyList(), "HIGH", "Another qs bug", listOf("6.3.3"), listOf(AffectedRange("6.3.0", fixed = "6.3.3")))),
            "semver" to listOf(Advisory("GHSA-c2qf-rxjj-qqgw", listOf("CVE-2022-25883"), "MODERATE", "ReDoS", listOf("7.5.2"),
                listOf(AffectedRange("0", fixed = "7.5.2")))))
    }
}
