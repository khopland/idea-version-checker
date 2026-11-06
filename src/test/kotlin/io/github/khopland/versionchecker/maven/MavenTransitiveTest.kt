package io.github.khopland.versionchecker.maven

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PsiTestUtil
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
import io.github.khopland.versionchecker.repo.MavenLookup
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenArtifactState
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

/** Vulnerable packages below declared dependencies, from a dependency tree registered as if IDEA had imported it. */
class MavenTransitiveTest : BasePlatformTestCase() {
    private class FakeSource : VulnerabilitySource {
        val queried = mutableListOf<Pkg>()
        override suspend fun check(packages: List<Pkg>): Map<Pkg, List<Advisory>> {
            synchronized(queried) { queried += packages }
            return packages.associateWith { pkg -> ADVISORIES[pkg.name].orEmpty().filter { it.affects(pkg.version) } }
        }
    }

    private val source = FakeSource()
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path
    private lateinit var root: VirtualFile
    private lateinit var previousSource: VulnerabilitySource
    private lateinit var previousFetch: suspend (Lookup) -> Releases

    override fun setUp() {
        super.setUp()
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        // MavenProject needs files on disk; the content root makes them project files.
        directory = Files.createTempDirectory("maven-transitive").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        PsiTestUtil.addContentRoot(module, root)
        myFixture.enableInspections(VulnerableTransitiveDependencyInspection())
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = true
        previousSource = VulnerabilityService.getInstance().source
        previousFetch = VersionService.getInstance().fetch
        VulnerabilityService.getInstance().source = source
        VersionService.getInstance().fetch = { lookup -> Releases(VERSIONS[(lookup as MavenLookup).let { "${it.groupId}:${it.artifactId}" }].orEmpty()) }
        refresh()
    }

    override fun tearDown() {
        try {
            VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
            VulnerabilityService.getInstance().source = previousSource
            VersionService.getInstance().fetch = previousFetch
            refresh()
            manager.projectsTree.setIgnoredState(manager.projects, true)
            PsiTestUtil.removeContentEntry(module, root)
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun refresh() {
        VersionService.getInstance().clear()
        VulnerabilityService.getInstance().clear()
    }

    /** A tree node: `"g:a:1.0"` with [children], in [state]. */
    private fun node(coordinates: String, vararg children: MavenArtifactNode, state: MavenArtifactState = MavenArtifactState.ADDED): MavenArtifactNode {
        val (group, artifact, version) = coordinates.split(':')
        val mavenArtifact = MavenArtifact(group, artifact, version, version, "jar", null, "compile", false, "jar", null, null, true, false)
        return MavenArtifactNode(null, mavenArtifact, state, null, "compile", null, null).apply { dependencies = children.toList() }
    }

    /** Writes the POM, registers it with [tree] as its resolved dependencies, and opens it in the editor. */
    private fun pom(body: String, tree: List<MavenArtifactNode>): XmlFile {
        val path = Files.createDirectories(directory.resolve("app")).resolve("pom.xml")
        val head = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId>
                <artifactId>app</artifactId>
                <version>1</version>
        """.trimIndent()
        Files.writeString(path, "$head\n${body.trimIndent().prependIndent("    ")}\n</project>\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val model = MavenModel().apply {
            mavenId = MavenId("demo", "app", "1")
            dependencyTree = tree
        }
        val imported = MavenProject(virtualFile).apply {
            updateState(model, emptyList(), "21", emptyList(), MavenExplicitProfiles(emptyList()), emptySet(), emptyMap(),
                Files.createTempDirectory("repository"), false)
        }
        manager.projectsTree.putVirtualFileToProjectMapping(imported, imported.mavenId)
        manager.projectsTree.setIgnoredState(listOf(imported), false)
        myFixture.configureFromExistingVirtualFile(virtualFile)
        return myFixture.file as XmlFile
    }

    private fun warnings() = myFixture.doHighlighting().filter { it.description?.startsWith("Brings in") == true }
        .map { myFixture.file.text.substring(it.startOffset, it.endOffset) to it.description }

    private fun intentions(text: String): List<String> {
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(text) + 1)
        return myFixture.availableIntentions.map { it.text }
    }

    private fun dependency(artifact: String, extra: String = "<version>1.0</version>") =
        "<dependency><groupId>org.example</groupId><artifactId>$artifact</artifactId>$extra</dependency>"

    fun testTreeBelowEachDeclaredDependency() {
        val file = pom("<dependencies>${dependency("web")}${dependency("client")}</dependencies>", listOf(
            node("org.example:web:1.0", node("org.example:json:1.0", node("$DATABIND:2.9.0")),
                node("org.example:old:0.9", state = MavenArtifactState.CONFLICT)),
            // Maven omits what web already brings in; it still comes in through client.
            node("org.example:client:1.0", node("org.example:json:1.0", state = MavenArtifactState.DUPLICATE)),
            // Inherited from a parent: not declared in this file.
            node("org.example:inherited:1.0", node("$DATABIND:2.9.0"))))
        val found = MavenProvider().transitive(file).map { "${it.via} > ${it.path.joinToString("/")} > ${it.pkg.name}:${it.pkg.version}" }
        assertEquals(listOf(
            "org.example:web >  > org.example:json:1.0",
            "org.example:web > org.example:json > $DATABIND:2.9.0",
            "org.example:client >  > org.example:json:1.0",
            "org.example:client > org.example:json > $DATABIND:2.9.0"), found)
    }

    fun testPackagesThePomDeclaresAreNotRepeated() {
        val file = pom("<dependencies>${dependency("web")}${dependency("json")}</dependencies>", listOf(
            node("org.example:web:1.0", node("org.example:json:1.0", state = MavenArtifactState.DUPLICATE)),
            node("org.example:json:1.0", node("$DATABIND:2.9.0"))))
        assertEquals(listOf("org.example:web > org.example:json > $DATABIND", "org.example:json >  > $DATABIND"),
            MavenProvider().transitive(file).map { "${it.via} > ${it.path.joinToString("/")} > ${it.pkg.name}" })
    }

    fun testWarningOnTheDeclaredArtifactAndPinFix() {
        pom("""
            <dependencies>
                ${dependency("web")}
                ${dependency("safe")}
            </dependencies>
        """, listOf(
            node("org.example:web:1.0", node("org.example:json:1.0", node("$DATABIND:2.9.0"))),
            node("org.example:safe:1.0", node("$DATABIND:2.9.10"))))
        assertEquals(listOf("web" to "Brings in $DATABIND 2.9.0 via org.example:json: CVE-2019-12384 (high): Polymorphic typing. Fixed in 2.9.9.1"), warnings())
        assertTrue(Pkg(Ecosystem.MAVEN, "org.example:json", "1.0") in source.queried)

        intentions(">web<")
        myFixture.launchAction(myFixture.findSingleIntention("Pin $DATABIND to 2.9.9.1 in dependency management"))
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("""
            |    <dependencyManagement>
            |        <dependencies>
            |            <dependency>
            |                <groupId>com.fasterxml.jackson.core</groupId>
            |                <artifactId>jackson-databind</artifactId>
            |                <version>2.9.9.1</version>
            |            </dependency>
            |        </dependencies>
            |    </dependencyManagement>
            |    <dependencies>
        """.trimMargin()))
    }

    fun testPinGoesIntoExistingDependencyManagement() {
        pom("""
            <dependencyManagement>
                <dependencies>
                    ${dependency("bom", "<version>2.0</version><type>pom</type><scope>import</scope>")}
                </dependencies>
            </dependencyManagement>
            <dependencies>${dependency("web")}</dependencies>
        """, listOf(node("org.example:web:1.0", node("$DATABIND:2.9.0"))))
        intentions(">web<")
        myFixture.launchAction(myFixture.findSingleIntention("Pin $DATABIND to 2.9.9.1 in dependency management"))
        val management = (myFixture.file as XmlFile).rootTag!!.findFirstSubTag("dependencyManagement")!!.findFirstSubTag("dependencies")!!
        assertEquals(listOf("bom", "jackson-databind"), management.findSubTags("dependency").map { it.findFirstSubTag("artifactId")!!.value.text })
        assertEquals(1, Regex("<dependencyManagement>").findAll(myFixture.editor.document.text).count())
    }

    fun testNoPinWhenThePomAlreadyManagesThePackage() {
        pom("""
            <dependencyManagement><dependencies>
                <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.9.0</version></dependency>
            </dependencies></dependencyManagement>
            <dependencies>${dependency("web")}</dependencies>
        """, listOf(node("org.example:web:1.0", node("$DATABIND:2.9.0"))))
        assertEquals(1, warnings().size)
        assertTrue(intentions(">web<").none { it.startsWith("Pin ") })
    }

    fun testSeveralPackagesAreListedWorstFirst() {
        pom("<dependencies>${dependency("web")}</dependencies>",
            listOf(node("org.example:web:1.0", node("org.example:util:1.0"), node("$DATABIND:2.9.0"))))
        assertEquals(listOf("web" to "Brings in 2 vulnerable packages: $DATABIND 2.9.0 (1, high), org.example:util 1.0 (1, low)"), warnings())
        assertContainsElements(intentions(">web<"), "Pin $DATABIND to 2.9.9.1 in dependency management", "Pin org.example:util to 1.1 in dependency management")
    }

    fun testSuppressForTag() {
        pom("<dependencies>${dependency("web")}</dependencies>", listOf(node("org.example:web:1.0", node("$DATABIND:2.9.0"))))
        assertEquals(1, warnings().size)
        intentions(">web<")
        myFixture.launchAction(myFixture.availableIntentions.first { it.text == "Suppress for tag" })
        assertTrue(myFixture.editor.document.text.contains("<!--suppress VulnerableTransitiveDependency -->"))
        assertEmpty(warnings())
    }

    fun testNothingIsSentWhenTheSettingIsOff() {
        VersionCheckerSettings.getInstance().state.checkVulnerabilities = false
        pom("<dependencies>${dependency("web")}</dependencies>", listOf(node("org.example:web:1.0", node("$DATABIND:2.9.0"))))
        assertEmpty(warnings())
        assertEmpty(source.queried)
    }

    companion object {
        private const val DATABIND = "com.fasterxml.jackson.core:jackson-databind"
        private val VERSIONS = mapOf(
            DATABIND to listOf("2.9.0", "2.9.9", "2.9.9.1", "2.9.10"),
            "org.example:util" to listOf("1.0", "1.1"))
        private val ADVISORIES = mapOf(
            DATABIND to listOf(Advisory("GHSA-mx7p-6679-8g3q", listOf("CVE-2019-12384"), "HIGH", "Polymorphic typing", listOf("2.9.9.1"),
                listOf(AffectedRange("2.0.0", fixed = "2.9.9.1")))),
            "org.example:util" to listOf(Advisory("GHSA-util", emptyList(), "LOW", "Util leaks", listOf("1.1"), listOf(AffectedRange("0", fixed = "1.1")))))
    }
}
