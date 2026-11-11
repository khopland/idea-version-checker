package io.github.khopland.versionchecker.core

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.VersionCheckerSettings
import io.github.khopland.versionchecker.npm.NpmLookup
import io.github.khopland.versionchecker.repo.MavenLookup

/** Runs the real providers, annotator and fixes on build files, with fake releases and a fake vulnerability source. */
class VulnerableDependencyTest : BasePlatformTestCase() {
    private class FakeSource : VulnerabilitySource {
        val queried = mutableListOf<Pkg>()
        override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> {
            synchronized(queried) { queried += packages }
            return packages.associateWith { pkg -> ADVISORIES[pkg.name].orEmpty().filter { it.affects(pkg.version) } }
        }

        override fun actions(pkg: Pkg, advisories: List<Advisory>): List<IntentionAction> =
            if (pkg.name == "lodash") listOf(WaiveAction(advisories.single().displayId)) else emptyList()
    }

    private class WaiveAction(private val id: String) : IntentionAction {
        override fun getText() = "Waive $id"
        override fun getFamilyName() = text
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {}
        override fun startInWriteAction() = false
    }

    private val source = FakeSource()
    private lateinit var previousSource: VulnerabilitySource
    private lateinit var previousFetch: suspend (Lookup) -> Releases

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(OutdatedVersionInspection(), VulnerableDependencyInspection())
        // Kotlin does not highlight a script outside a configured Gradle project.
        ExtensionTestUtil.maskExtensions(ProblemHighlightFilter.EP_NAME, emptyList(), testRootDisposable)
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = true
        previousSource = VulnerabilityService.getInstance().source
        previousFetch = VersionService.getInstance().fetch
        VulnerabilityService.getInstance().source = source
        VersionService.getInstance().fetch = { lookup ->
            val name = when (lookup) {
                is MavenLookup -> "${lookup.groupId}:${lookup.artifactId}"
                is NpmLookup -> lookup.packageName
                else -> error("unexpected lookup ${lookup.key}")
            }
            Releases(VERSIONS[name].orEmpty())
        }
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

    private fun vulnerabilities() = myFixture.doHighlighting().filter { it.inspectionToolId == VulnerableDependencyInspection.SHORT_NAME ||
        it.description?.let { text -> text.startsWith("CVE-") || text.startsWith("GHSA-") } == true }

    private fun intentions(text: String): List<String> {
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(text) + 1)
        return myFixture.availableIntentions.map { it.text }
    }

    fun testPomPropertyIsUpdatedToTheNearestSafeVersion() {
        myFixture.configureByText("pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
              <properties><log4j.version>2.14.1</log4j.version></properties>
              <dependencies>
                <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>${'$'}{log4j.version}</version></dependency>
                <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
              </dependencies>
            </project>
        """.trimIndent())
        val highlight = vulnerabilities().single()
        assertEquals("CVE-2021-44228, CVE-2021-44832 (critical). Fixed in 2.17.1", highlight.description)
        assertEquals(HighlightSeverity.ERROR, highlight.severity)
        assertTrue(Pkg(Ecosystem.MAVEN, "junit:junit", "4.13.2") in source.queried)

        val texts = intentions("\${log4j.version}</version>")
        // The outdated patch update is offered, but marked.
        assertContainsElements(texts, "Update log4j.version to 2.17.1 (fixes 2 vulnerabilities)", "Update log4j.version to 2.14.2 (patch, still vulnerable)")
        myFixture.launchAction(myFixture.findSingleIntention("Update log4j.version to 2.17.1 (fixes 2 vulnerabilities)"))
        assertTrue(myFixture.editor.document.text.contains("<log4j.version>2.17.1</log4j.version>"))
    }

    fun testGradleScriptSkipsPluginsAndRespectsSuppression() {
        myFixture.configureByText("build.gradle.kts", """
            plugins {
                id("org.example.plugin") version "1.0.0"
            }
            dependencies {
                implementation("com.example:lib:1.0.0")
                //noinspection VulnerableDependency
                implementation("org.apache.logging.log4j:log4j-core:2.14.1")
            }
        """.trimIndent())
        val highlight = vulnerabilities().single()
        assertEquals("GHSA-lib (moderate): Lib leaks <tokens>. Fixed in 1.0.2", highlight.description)
        assertTrue(highlight.toolTip, highlight.toolTip!!.contains("&lt;tokens&gt;"))
        assertEquals(HighlightSeverity.WARNING, highlight.severity)
        assertTrue(source.queried.none { it.name.contains("plugin") })

        assertContainsElements(intentions("1.0.0\")"), "Update to 1.0.2 (fixes 1 vulnerability)", "Update to 1.0.2 (patch)")
        myFixture.launchAction(myFixture.findSingleIntention("Update to 1.0.2 (fixes 1 vulnerability)"))
        assertTrue(myFixture.editor.document.text.contains("\"com.example:lib:1.0.2\""))
    }

    fun testPackageJsonKeepsTheOperator() {
        myFixture.configureByText("package.json", """{"dependencies": {"lodash": "^4.17.20", "left-pad": "1.3.0"}}""")
        val highlight = vulnerabilities().single()
        assertEquals("CVE-2021-23337 (high): Command Injection in lodash. Fixed in 4.17.21", highlight.description)
        assertEquals(HighlightSeverity.ERROR, highlight.severity)
        assertTrue(Pkg(Ecosystem.NPM, "lodash", "4.17.20") in source.queried)

        // The error hides the outdated warning, so it carries the update actions, without a second update to the same version.
        val texts = intentions("^4.17.20")
        // The source's own actions, such as a Nexus IQ waiver, are offered too.
        assertContainsElements(texts, "Update all versions in this file…", "Waive CVE-2021-23337")
        assertDoesntContain(texts, "Update to 4.17.21 (patch)")
        myFixture.launchAction(myFixture.findSingleIntention("Update to 4.17.21 (fixes 1 vulnerability)"))
        assertTrue(myFixture.editor.document.text.contains("\"lodash\": \"^4.17.21\""))
    }

    fun testNothingIsSentWhenTheSettingIsOff() {
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
        myFixture.configureByText("package.json", """{"dependencies": {"lodash": "^4.17.20"}}""")
        assertEmpty(vulnerabilities())
        assertEmpty(source.queried)
    }

    fun testErrorWithoutNewerVersionsCarriesNoUpdates() {
        myFixture.configureByText("package.json", """{"dependencies": {"abandoned": "1.0.0"}}""")
        assertEquals(listOf("GHSA-abandoned (critical)"), vulnerabilities().map { it.description })
        val texts = intentions("1.0.0")
        assertContainsElements(texts, "Inspection 'Vulnerable dependency' options")
        assertDoesntContain(texts, "Update all versions in this file…")
    }

    fun testOwnSwitchIndependentOfOutdatedVersions() {
        myFixture.disableInspections(OutdatedVersionInspection())
        myFixture.configureByText("package.json", """{"dependencies": {"lodash": "^4.17.20"}}""")
        assertEquals(listOf("CVE-2021-23337 (high): Command Injection in lodash. Fixed in 4.17.21"), vulnerabilities().map { it.description })
        assertDoesntContain(intentions("^4.17.20"), "Update all versions in this file…")

        refresh()
        source.queried.clear()
        myFixture.enableInspections(OutdatedVersionInspection())
        myFixture.disableInspections(VulnerableDependencyInspection())
        assertEmpty(vulnerabilities())
        assertEmpty(source.queried)
    }

    fun testSharedPropertyGetsAVersionThatFixesEveryUser() {
        myFixture.configureByText("pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>demo</groupId><artifactId>app</artifactId><version>1.0</version>
              <properties><shared.version>1.0.0</shared.version></properties>
              <dependencies>
                <dependency><groupId>com.example</groupId><artifactId>alpha</artifactId><version>${'$'}{shared.version}</version></dependency>
                <dependency><groupId>com.example</groupId><artifactId>beta</artifactId><version>${'$'}{shared.version}</version></dependency>
              </dependencies>
            </project>
        """.trimIndent())
        // Alpha alone is fixed in 1.0.1, but beta needs 1.1.0.
        assertEquals(listOf("GHSA-alpha (moderate). Fixed in 1.1.0", "GHSA-beta (moderate). Fixed in 1.1.0"), vulnerabilities().map { it.description })
        val texts = intentions("\${shared.version}</version></dependency>")
        assertContainsElements(texts, "Update shared.version to 1.1.0 (fixes 1 vulnerability)", "Update shared.version to 1.0.1 (patch, still vulnerable)")
    }

    fun testPackageJsonIsCheckedAtTheLockedVersion() {
        // ^1.2.0 allows the safe 1.2.0, but the lockfile installs the vulnerable 1.2.5.
        myFixture.addFileToProject("package-lock.json", """{"lockfileVersion": 3, "packages": {"": {}, "node_modules/minimist": {"version": "1.2.5"}}}""")
        myFixture.configureByText("package.json", """{"dependencies": {"minimist": "^1.2.0"}}""")
        assertEquals(listOf("GHSA-minimist (moderate). Fixed in 1.2.6"), vulnerabilities().map { it.description })
        assertTrue(Pkg(Ecosystem.NPM, "minimist", "1.2.5") in source.queried)
        assertFalse(Pkg(Ecosystem.NPM, "minimist", "1.2.0") in source.queried)
        // The outdated warning still compares the declared version.
        assertTrue(myFixture.doHighlighting().any { it.description?.startsWith("minimist 1.2.0 → ") == true })
        assertContainsElements(intentions("^1.2.0"), "Update to 1.2.6 (fixes 1 vulnerability)")
        myFixture.launchAction(myFixture.findSingleIntention("Update to 1.2.6 (fixes 1 vulnerability)"))
        assertTrue(myFixture.editor.document.text.contains("\"minimist\": \"^1.2.6\""))
    }

    fun testYarnLockfileFindsTheLockedVersionByRange() {
        myFixture.addFileToProject("yarn.lock", "# yarn lockfile v1\n\nminimist@^1.2.0:\n  version \"1.2.5\"\n")
        myFixture.configureByText("package.json", """{"devDependencies": {"minimist": "^1.2.0"}}""")
        assertEquals(listOf("GHSA-minimist (moderate). Fixed in 1.2.6"), vulnerabilities().map { it.description })
    }

    fun testDeclaredVersionWithoutALockedOne() {
        // No lockfile.
        myFixture.configureByText("package.json", """{"dependencies": {"minimist": "^1.2.0"}}""")
        assertEmpty(vulnerabilities())
        assertTrue(Pkg(Ecosystem.NPM, "minimist", "1.2.0") in source.queried)
        // A lockfile without the package, or one older than package.json, as before an install.
        myFixture.addFileToProject("package-lock.json", """{"lockfileVersion": 3, "packages": {"": {}, "node_modules/other": {"version": "1.2.5"}}}""")
        myFixture.configureByText("package.json", """{"dependencies": {"minimist": "^1.2.4"}}""")
        assertEquals(listOf("GHSA-minimist (moderate). Fixed in 1.2.6"), vulnerabilities().map { it.description })
        assertTrue(Pkg(Ecosystem.NPM, "minimist", "1.2.4") in source.queried)
        WriteAction.runAndWait<Throwable> {
            myFixture.findFileInTempDir("package-lock.json").setBinaryContent("""{"lockfileVersion": 3, "packages": {"": {}, "node_modules/minimist": {"version": "1.2.0"}}}""".toByteArray())
        }
        source.queried.clear()
        myFixture.configureByText("package.json", """{"dependencies": {"minimist": "^1.2.5"}}""")
        assertEquals(listOf("GHSA-minimist (moderate). Fixed in 1.2.6"), vulnerabilities().map { it.description })
        assertEquals(listOf(Pkg(Ecosystem.NPM, "minimist", "1.2.5")), source.queried)
        // A catalog entry is no installed package, so it keeps its own version.
        myFixture.configureByText("package.json", """{"workspaces": {"catalog": {"minimist": "^1.2.0"}}}""")
        assertEmpty(vulnerabilities())
    }

    fun testFailureIsOneWeakWarning() {
        VulnerabilityService.getInstance().source = object : VulnerabilitySource {
            override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> = throw HttpFailure("api.osv.dev: unknown host")
        }
        myFixture.configureByText("package.json", """{"dependencies": {"lodash": "^4.17.20"}}""")
        val weak = myFixture.doHighlighting().filter { it.description?.startsWith("Could not check for vulnerabilities") == true }
        assertEquals(listOf("Could not check for vulnerabilities: api.osv.dev: unknown host"), weak.map { it.description })
    }

    companion object {
        private val VERSIONS = mapOf(
            "org.apache.logging.log4j:log4j-core" to listOf("2.14.1", "2.14.2", "2.15.0", "2.17.0", "2.17.1", "2.18.0"),
            "junit:junit" to listOf("4.13.2"),
            "com.example:lib" to listOf("1.0.0", "1.0.1", "1.0.2"),
            "com.example:alpha" to listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0"),
            "com.example:beta" to listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0"),
            "lodash" to listOf("4.17.20", "4.17.21"),
            "left-pad" to listOf("1.3.0"),
            "abandoned" to listOf("1.0.0"),
            "minimist" to listOf("1.2.0", "1.2.4", "1.2.5", "1.2.6", "1.2.8"))
        private val ADVISORIES = mapOf(
            "org.apache.logging.log4j:log4j-core" to listOf(
                Advisory("GHSA-jfh8-c2jp-5v3q", listOf("CVE-2021-44228"), "CRITICAL", "Remote code injection in Log4j", listOf("2.15.0"), listOf(AffectedRange("2.13.0", "2.15.0"))),
                Advisory("GHSA-8489-44mv-ggj8", listOf("CVE-2021-44832"), "MODERATE", "Improper input validation", listOf("2.17.1"), listOf(AffectedRange("2.13.0", "2.17.1")))),
            "com.example:lib" to listOf(Advisory("GHSA-lib", emptyList(), "MODERATE", "Lib leaks <tokens>.", listOf("1.0.2"), listOf(AffectedRange("0", "1.0.2")))),
            "com.example:alpha" to listOf(Advisory("GHSA-alpha", emptyList(), "MODERATE", null, listOf("1.0.1"), listOf(AffectedRange("0", "1.0.1")))),
            "com.example:beta" to listOf(Advisory("GHSA-beta", emptyList(), "MODERATE", null, listOf("1.1.0"), listOf(AffectedRange("0", "1.1.0")))),
            "minimist" to listOf(Advisory("GHSA-minimist", emptyList(), "MODERATE", null, listOf("1.2.6"), listOf(AffectedRange("1.2.3", "1.2.6")))),
            "abandoned" to listOf(Advisory("GHSA-abandoned", emptyList(), "CRITICAL", null, emptyList(), listOf(AffectedRange("0")))),
            "lodash" to listOf(Advisory("GHSA-35jh-r3h4-6jhm", listOf("CVE-2021-23337"), "HIGH", "Command Injection in lodash", listOf("4.17.21"), listOf(AffectedRange("0", "4.17.21")))))
    }
}
