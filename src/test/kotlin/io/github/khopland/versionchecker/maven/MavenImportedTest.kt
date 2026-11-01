package io.github.khopland.versionchecker.maven

import com.intellij.codeInspection.InspectionManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.model.MavenArtifactInfo
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenParent
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files

/** Light tests with Maven projects registered as if IDEA had imported them. */
class MavenImportedTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: java.nio.file.Path
    private lateinit var root: com.intellij.openapi.vfs.VirtualFile

    override fun setUp() {
        super.setUp()
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        // MavenProject needs files on disk; the content root makes them project files.
        directory = Files.createTempDirectory("maven-imported").toRealPath()
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
    }

    override fun tearDown() {
        try {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            com.intellij.testFramework.PsiTestUtil.removeContentEntry(module, root)
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    /**
     * Adds `<name>/pom.xml` and registers it. [parentXml] is the `<parent>` element as written, and
     * [parent] is the parent IDEA resolved, which can differ with Maven 4 inference or `${revision}`.
     */
    private fun pom(name: String, body: String, parent: String? = null, parentXml: String? = parent?.let {
        "<parent><groupId>demo</groupId><artifactId>$it</artifactId><version>1</version><relativePath>../$it/pom.xml</relativePath></parent>"
    }, managed: Map<String, String> = emptyMap()): XmlFile {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            ${parentXml.orEmpty()}
            <groupId>demo</groupId><artifactId>$name</artifactId><version>1</version>
            $body</project>
        """.trimIndent())
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile) as XmlFile
        val parentProject = parent?.let { manager.findProject(MavenId("demo", it, "1")) }
        val properties = java.util.Properties().apply {
            parentProject?.properties?.let(::putAll)
            file.rootTag!!.findFirstSubTag("properties")?.subTags?.forEach { setProperty(it.localName, it.value.trimmedText) }
        }
        val model = MavenModel().apply {
            mavenId = MavenId("demo", name, "1")
            setProperties(properties)
            parent?.let { setParent(MavenParent(MavenId("demo", it, "1"), "../$it/pom.xml")) }
        }
        val managedInfo = managed.map { (key, version) -> MavenArtifactInfo(key.substringBefore(':'), key.substringAfter(':'), version, "jar", null) }
        val imported = MavenProject(file.virtualFile).apply {
            updateState(model, managedInfo, "21", emptyList(), MavenExplicitProfiles(emptyList()), emptySet(), emptyMap(),
                Files.createTempDirectory("repository"), false)
        }
        manager.projectsTree.putVirtualFileToProjectMapping(imported, imported.mavenId)
        manager.projectsTree.setIgnoredState(listOf(imported), false)
        return file
    }

    private fun problems(file: XmlFile) = RedundantOverrideInspection()
        .checkFile(file, InspectionManager.getInstance(project), false).map { it.descriptionTemplate }

    fun testRedundantOverrides() {
        pom("parent", "<properties><lib.version>2.0</lib.version><same.version>1.0</same.version></properties>")
        val child = pom("child", """
            <properties><same.version>1.0</same.version><lib.version>1.5</lib.version><unused.version>3.0</unused.version></properties>
            <dependencies>
              <dependency><groupId>org.example</groupId><artifactId>equal</artifactId><version>4.0</version></dependency>
              <dependency><groupId>org.example</groupId><artifactId>older</artifactId><version>${'$'}{lib.version}</version></dependency>
              <dependency><groupId>org.example</groupId><artifactId>newer</artifactId><version>9.0</version></dependency>
            </dependencies>
        """, parent = "parent", managed = mapOf("org.example:equal" to "4.0", "org.example:older" to "2.0", "org.example:newer" to "8.0"))
        val found = problems(child)
        assertContainsElements(found,
            "Version 4.0 is already managed",
            "Dependency management gives a newer version 2.0 (local override: 1.5)",
            "Property same.version has the same value as in the parent",
            "The parent sets a newer lib.version 2.0 (local override: 1.5)",
            "Property unused.version is not used")
        assertDoesntContain(found, "Property lib.version is not used", "Property same.version is not used")
        assertEquals("A newer local version is a deliberate override", 5, found.size)
    }

    fun testRedundantPluginVersions() {
        pom("parent", """
            <properties><compiler.version>3.13.0</compiler.version></properties>
            <build><pluginManagement><plugins>
              <plugin><artifactId>maven-compiler-plugin</artifactId><version>${'$'}{compiler.version}</version></plugin>
              <plugin><groupId>org.example</groupId><artifactId>newer-plugin</artifactId><version>2.0</version></plugin>
              <plugin><groupId>org.example</groupId><artifactId>profiled-plugin</artifactId><version>1.0</version></plugin>
            </plugins></pluginManagement></build>
            <profiles><profile><id>x</id><build><pluginManagement><plugins>
              <plugin><groupId>org.example</groupId><artifactId>profiled-plugin</artifactId><version>1.1</version></plugin>
            </plugins></pluginManagement></build></profile></profiles>
        """)
        val child = pom("child", """
            <build>
              <pluginManagement><plugins>
                <plugin><groupId>org.example</groupId><artifactId>local-plugin</artifactId><version>1.0</version></plugin>
              </plugins></pluginManagement>
              <plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><groupId>org.example</groupId><artifactId>newer-plugin</artifactId><version>1.5</version></plugin>
                <plugin><groupId>org.example</groupId><artifactId>local-plugin</artifactId><version>1.0</version></plugin>
                <plugin><groupId>org.example</groupId><artifactId>profiled-plugin</artifactId><version>1.0</version></plugin>
                <plugin><groupId>org.example</groupId><artifactId>unmanaged-plugin</artifactId><version>1.0</version></plugin>
              </plugins>
            </build>
        """, parent = "parent")
        assertEquals(listOf("Plugin version 3.13.0 is already managed", "Plugin management gives a newer version 2.0 (local override: 1.5)",
            "Plugin version 1.0 is already managed"), problems(child))
    }

    fun testDuplicateDependencies() {
        val dependency = { artifact: String, extra: String -> "<dependency><groupId>org.example</groupId><artifactId>$artifact</artifactId>$extra</dependency>" }
        // Duplicates need no import, so this POM is not registered.
        val file = myFixture.configureByText("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>dup</artifactId><version>1</version>
            <dependencies>
              ${dependency("same", "<version>1.0</version>")}
              ${dependency("other", "<version>1.0</version>")}
              ${dependency("same", "\n                <version>1.0</version>")}
              ${dependency("different", "<version>1.0</version>")}
              ${dependency("different", "<version>2.0</version>")}
              ${dependency("other", "<version>1.0</version><classifier>tests</classifier>")}
            </dependencies>
            <dependencyManagement><dependencies>${dependency("same", "<version>1.0</version>")}</dependencies></dependencyManagement>
            </project>
        """.trimIndent()) as XmlFile
        assertEquals(listOf("Dependency org.example:same is declared twice", "Dependency org.example:different is declared twice, with different settings"),
            problems(file))
        myFixture.enableInspections(RedundantOverrideInspection())
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == "Remove duplicate dependency" })
        assertEquals(1, Regex("<artifactId>same</artifactId>").findAll(file.rootTag!!.findFirstSubTag("dependencies")!!.text).count())
    }

    fun testInferredParentAndRevisionFindTheParentProperty() {
        pom("parent", "<properties><lib.version>1.0</lib.version></properties>")
        // Maven 4 can infer the parent from its location, and ${revision} hides the version from the XML.
        for ((name, xml) in listOf("inferred" to "<parent/>", "revision" to "<parent><groupId>demo</groupId><artifactId>parent</artifactId><version>${'$'}{revision}</version></parent>")) {
            val child = pom(name, """<dependencies>
                <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>${'$'}{lib.version}</version></dependency>
            </dependencies>""", parent = "parent", parentXml = xml)
            val declaration = MavenProvider().collect(child).single()
            assertEquals(name, "1.0", declaration.current)
            assertTrue(name, declaration.edit!!.file.path.endsWith("/parent/pom.xml"))
            assertEquals(name, "lib.version", declaration.edit!!.label)
        }
    }

    fun testRelatedModulesShareTheParentProperty() {
        pom("parent", "<properties><jackson.version>2.15.2</jackson.version></properties>")
        val dependency = { artifact: String -> """<dependencies>
            <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>$artifact</artifactId><version>${'$'}{jackson.version}</version></dependency>
        </dependencies>""" }
        val core = pom("core", dependency("jackson-core"), parent = "parent")
        pom("databind", dependency("jackson-databind"), parent = "parent")
        pom("other", "<properties><x>1</x></properties>", parent = "parent")
        val own = MavenProvider().collect(core)
        val related = MavenProvider().related(core, own)
        assertEquals(listOf("com.fasterxml.jackson.core:jackson-databind"), related.map { it.name })
        assertEquals(own.single().edit!!.key, related.single().edit!!.key)
    }
}
