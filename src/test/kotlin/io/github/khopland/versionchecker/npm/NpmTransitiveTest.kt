package io.github.khopland.versionchecker.npm

import com.intellij.openapi.application.WriteAction
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
        myFixture.launchAction(myFixture.findSingleIntention("Override qs to 6.2.4 in package.json"))
        assertEquals("""
            {
              "name": "demo",
              "dependencies": {
                "web": "^1.0.0"
              },
              "overrides": {
                "qs": "6.2.4"
              }
            }
        """.trimIndent(), myFixture.editor.document.text)
    }

    fun testOverrideGoesIntoExistingOverrides() {
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": {"left-pad": "1.3.0"}}""")
        intentions("\"web\"")
        myFixture.launchAction(myFixture.findSingleIntention("Override qs to 6.2.4 in package.json"))
        val text = myFixture.editor.document.text
        assertTrue(text, Regex(""""overrides": \{"left-pad": "1.3.0",\s*"qs": "6.2.4"\s*}""").containsMatchIn(text))
    }

    fun testNoOverrideWhereNpmWouldRefuseIt() {
        // Already overridden: the override is checked as a declaration.
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}, "overrides": {"qs@^6": "6.0.0"}}""")
        assertEquals(1, warnings().size)
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

    fun testPnpmOverrideFix() {
        myFixture.addFileToProject("pnpm-lock.yaml", """
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
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        intentions("\"web\"")
        myFixture.launchAction(myFixture.findSingleIntention("Override qs to 6.2.4 in package.json"))
        val text = myFixture.editor.document.text
        assertTrue(text, Regex(""""pnpm": \{\s*"overrides": \{\s*"qs": "6.2.4"\s*}\s*}""").containsMatchIn(text))

        // pnpm 10 reads overrides from pnpm-workspace.yaml when it has them.
        myFixture.addFileToProject("pnpm-workspace.yaml", "overrides:\n  left-pad: 1.3.0\n")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertTrue(intentions("\"web\"").none { it.startsWith("Override ") })
    }

    fun testNothingIsSentWhenTheSettingIsOff() {
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
        lock("package-lock.json", "node_modules/web: 1.0.0 > qs", "node_modules/qs: 6.0.0")
        myFixture.configureByText("package.json", """{"dependencies": {"web": "^1.0.0"}}""")
        assertEmpty(warnings())
        assertEmpty(source.queried)
    }

    companion object {
        private val VERSIONS = mapOf("qs" to listOf("6.0.0", "6.2.3", "6.2.4", "6.11.0"))
        private val ADVISORIES = mapOf(
            "qs" to listOf(Advisory("GHSA-hrpp-h998-j3pp", listOf("CVE-2022-24999"), "HIGH", "Prototype pollution", listOf("6.2.4"),
                listOf(AffectedRange("6.0.0", fixed = "6.2.4")))))
    }
}
