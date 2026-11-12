package io.github.khopland.versionchecker.npm

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.Conflicts
import io.github.khopland.versionchecker.core.Lookup
import io.github.khopland.versionchecker.core.Needs
import io.github.khopland.versionchecker.core.OutdatedVersionInspection
import io.github.khopland.versionchecker.core.Releases
import io.github.khopland.versionchecker.core.UpdateKind
import io.github.khopland.versionchecker.core.VersionService
import kotlinx.coroutines.runBlocking

/** Updates that break a peer dependency or need another Node.js. */
class NpmConflictsTest : BasePlatformTestCase() {
    private lateinit var previousFetch: suspend (Lookup) -> Releases

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(OutdatedVersionInspection())
        previousFetch = VersionService.getInstance().fetch
        VersionService.getInstance().fetch = { lookup -> RELEASES.getValue((lookup as NpmLookup).packageName) }
        VersionService.getInstance().clear()
    }

    override fun tearDown() {
        try {
            VersionService.getInstance().fetch = previousFetch
            VersionService.getInstance().clear()
        } finally {
            super.tearDown()
        }
    }

    fun testRanges() {
        fun check(range: String, inside: List<String>, outside: List<String>) {
            inside.forEach { assertEquals("$it in $range", true, NpmRange.satisfies(range, it)) }
            outside.forEach { assertEquals("$it in $range", false, NpmRange.satisfies(range, it)) }
        }
        check("^18.2.0", listOf("18.2.0", "18.3.1"), listOf("18.1.9", "19.0.0"))
        check("^0.2.3", listOf("0.2.3", "0.2.9"), listOf("0.3.0", "0.2.2"))
        check("^0.0.3", listOf("0.0.3"), listOf("0.0.4"))
        check("^0", listOf("0.9.9"), listOf("1.0.0"))
        check("~1.2.3", listOf("1.2.9"), listOf("1.3.0", "1.2.2"))
        check("~1", listOf("1.9.0"), listOf("2.0.0"))
        check("^16.8.0 || ^17.0.0 || ^18.0.0", listOf("16.14.0", "18.3.1"), listOf("16.7.0", "19.0.0"))
        check(">= 18.18 <21", listOf("18.18.0", "20.11.1"), listOf("18.17.9", "21.0.0"))
        check(">16", listOf("17.0.0"), listOf("16.20.2"))
        check(">16.0.0", listOf("16.0.1"), listOf("16.0.0"))
        check("<=1.2", listOf("1.2.9"), listOf("1.3.0"))
        check("1.x", listOf("1.0.0", "1.9.9"), listOf("2.0.0", "0.9.0"))
        check("18", listOf("18.3.1"), listOf("19.0.0"))
        check("1.2.3 - 2", listOf("1.2.3", "2.9.0"), listOf("1.2.2", "3.0.0"))
        check("*", listOf("0.0.1", "99.0.0"), emptyList())
        check("", listOf("1.0.0"), emptyList())
        // A stable version is after the prereleases of the same numbers.
        check("^18.0.0 || ^19.0.0-rc", listOf("19.0.0", "19.1.0"), listOf("20.0.0"))
        check(">=19.0.0-rc.1 <20.0.0-0", listOf("19.0.0"), listOf("20.0.0", "18.9.9"))
        // `.nvmrc` may name only a major version, which can be any release of it.
        check(">=22.12.0", listOf("22", "22.12"), listOf("20", "22.11"))
        listOf("workspace:*", "latest", "npm:other@1.0.0", "github:a/b").forEach { assertNull(it, NpmRange.satisfies(it, "1.0.0")) }
        assertNull(NpmRange.satisfies("^1.0.0", "1.0.0-beta.1"))
        assertEquals(true, NpmRange.satisfies("^1.0.0 || latest", "1.2.0"))
        assertNull(NpmRange.satisfies("^1.0.0 || latest", "2.0.0"))
    }

    fun testRegistryDocumentHasPeersAndEngines() = runBlocking {
        val releases = NpmLookup.parse(JsonParser.parseString("""
            {"versions": {
              "1.0.0": {},
              "1.1.0": {"peerDependencies": {"react": "^18.0.0"}, "engines": {"node": ">=18"}},
              "2.0.0": {"peerDependencies": {"react": "^19.0.0"}, "engines": ["node >=20"]},
              "3.0.0-rc.1": {"peerDependencies": {"react": "^20.0.0"}}}}
        """).asJsonObject)
        assertEquals(setOf("1.1.0", "2.0.0"), releases.needs.keys)
        assertEquals(mapOf("react" to "^18.0.0"), releases.needs.getValue("1.1.0").peers)
        assertEquals(">=18", releases.needs.getValue("1.1.0").node)
        assertNull(releases.needs.getValue("2.0.0").node)
        // The outcome keeps what the installed version and the offered versions need.
        assertEquals(setOf("1.1.0", "2.0.0"), VersionService.outcome("1.0.0", releases, null).needs.keys)
        assertEquals(setOf("1.1.0", "2.0.0"), VersionService.outcome("1.0.0", releases, null, checked = "1.1.0").needs.keys)
    }

    fun testUpdateThatBreaksAPeerDependency() {
        myFixture.addFileToProject(".nvmrc", "v18.19.0\n")
        myFixture.configureByText("package.json", """
            {
              "dependencies": {"react": "~18.2.0", "react-dom": "^18.2.0", "left-pad": "1.0.0"},
              "devDependencies": {"eslint": "^8.57.0", "eslint-plugin-x": "^3.0.0", "vite": "^5.0.0"},
              "overrides": {"react": "18.2.0"}
            }
        """.trimIndent())
        val messages = myFixture.doHighlighting().mapNotNull { it.description }.filter { " → " in it }
        assertEquals(listOf(
            "eslint 8.57.0 → 9.0.0 (major). 9.0.0 breaks eslint-plugin-x 3.0.0, which needs ^8.0.0 (eslint-plugin-x 4.0.0 accepts it)",
            "eslint-plugin-x 3.0.0 → 4.0.0 (major)",
            "left-pad 1.0.0 → 1.0.1 (patch)",
            "react (override) 18.2.0 → 18.3.1 (minor) · 19.0.0 (major)",
            "react 18.2.0 → 18.3.1 (minor) · 19.0.0 (major). 19.0.0 breaks react-dom 18.2.0, which needs ^18.2.0 (react-dom 19.0.0 accepts it)",
            "react-dom 18.2.0 → 18.3.1 (minor) · 19.0.0 (major). 18.3.1 needs react ^18.3.1, not 18.2.0. 19.0.0 needs react ^19.0.0, not 18.2.0",
            "vite 5.0.0 → 6.0.0 (major). 6.0.0 needs Node.js >=20, not 18.19.0",
        ), messages.sorted())

        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("^8.57.0") + 2)
        assertEquals(listOf("Update to 9.0.0 (major, conflicts with eslint-plugin-x)"), myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Update to") })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("^5.0.0") + 2)
        assertEquals(listOf("Update to 6.0.0 (major, conflicts with Node.js)"), myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Update to") })
    }

    fun testPackagesUpdatedTogetherDoNotConflict() = runBlocking {
        val file = myFixture.configureByText("package.json", """{"dependencies": {"react": "18.2.0", "react-dom": "^18.2.0", "eslint": "8.57.0", "eslint-plugin-x": "3.0.0"}}""")
        val declarations = NpmProvider().collect(file)
        val outcomes = VersionService.getInstance().check(declarations)
        val (react, dom, eslint) = declarations
        assertEquals(listOf("react-dom 19.0.0 needs react ^19.0.0, not 18.2.0"), Conflicts.of(dom, "19.0.0", outcomes).map { it.text })
        assertEquals(emptyList<String>(), Conflicts.of(dom, "19.0.0", outcomes) { if (it === react) "19.0.0" else it.checked }.map { it.text })
        assertEquals(emptyList<String>(), Conflicts.of(react, "19.0.0", outcomes) { if (it === dom) "19.0.0" else it.checked }.map { it.text })
        assertEquals(listOf("eslint-plugin-x 3.0.0 needs eslint ^8.0.0, not 9.0.0"), Conflicts.of(eslint, "9.0.0", outcomes).map { it.text })
    }

    fun testPeerMovesWithinItsDeclaredRange() {
        myFixture.configureByText("package.json", """{"dependencies": {"react": "^18.2.0", "react-dom": "^18.2.0"}}""")
        val messages = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("react-dom") }
        // `^18.2.0` installs react 18.3.1 when react-dom needs it, but never 19.
        assertEquals(listOf("react-dom 18.2.0 → 18.3.1 (minor) · 19.0.0 (major). 19.0.0 needs react ^19.0.0, not 18.2.0"), messages)
        assertEquals(listOf(null, UpdateKind.MINOR, UpdateKind.PATCH, UpdateKind.PATCH, null, UpdateKind.PATCH),
            listOf("1.2.3", "^1.2.3", "~1.2.3", "^0.2.3", "^0.0.3", "~0.0.3").map { NpmSpec.parse(it)!!.floats })
    }

    fun testConflictTheProjectAlreadyHasIsNotReported() {
        myFixture.addFileToProject(".nvmrc", "16\n")
        myFixture.configureByText("package.json", """{"dependencies": {"react": "17.0.0", "react-dom": "^18.2.0", "vite": "^5.0.0"}}""")
        val messages = myFixture.doHighlighting().mapNotNull { it.description }.filter { " → " in it }
        assertEquals(listOf(
            "react 17.0.0 → 19.0.0 (major)",
            "react-dom 18.2.0 → 18.3.1 (minor) · 19.0.0 (major)",
            "vite 5.0.0 → 6.0.0 (major)",
        ), messages.sorted())
    }

    private companion object {
        fun peers(vararg needs: Pair<String, String>) = Needs(mapOf(*needs))

        val RELEASES = mapOf(
            "react" to Releases(listOf("17.0.0", "18.2.0", "18.3.1", "19.0.0")),
            "react-dom" to Releases(listOf("18.2.0", "18.3.1", "19.0.0"), needs = mapOf(
                "18.2.0" to peers("react" to "^18.2.0"), "18.3.1" to peers("react" to "^18.3.1"), "19.0.0" to peers("react" to "^19.0.0"))),
            "left-pad" to Releases(listOf("1.0.0", "1.0.1")),
            "eslint" to Releases(listOf("8.57.0", "9.0.0")),
            "eslint-plugin-x" to Releases(listOf("3.0.0", "4.0.0"), needs = mapOf(
                "3.0.0" to peers("eslint" to "^8.0.0"), "4.0.0" to peers("eslint" to "^8.0.0 || ^9.0.0"))),
            "vite" to Releases(listOf("5.0.0", "6.0.0"), needs = mapOf("5.0.0" to Needs(node = ">=18"), "6.0.0" to Needs(node = ">=20"))))
    }
}
