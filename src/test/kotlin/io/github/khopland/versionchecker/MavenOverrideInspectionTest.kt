package io.github.khopland.versionchecker

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.khopland.versionchecker.maven.RemoveMavenOverrideFix
import io.github.khopland.versionchecker.maven.MavenOverrideAnalysis
import io.github.khopland.versionchecker.maven.UnnecessaryMavenOverrideInspection
import io.github.khopland.versionchecker.maven.withMavenCheckSession
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenParent
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

class MavenOverrideInspectionTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path
    private fun artifact(name: String) = "${getTestName(true)}-$name"

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-overrides-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
    }

    override fun tearDown() {
        try {
            project.service<VersionCheckerSettings>().state.enabled = true
            manager.projectsTree.setIgnoredState(manager.projects, true)
            WriteCommandAction.runWriteCommandAction(project) {
                LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$PROJECT_POLICY_FILE")?.delete(this)
            }
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun pom(name: String, body: String, parent: String? = null, imported: Boolean = true): XmlFile {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            ${parent?.let { "<parent><groupId>demo</groupId><artifactId>${artifact(it)}</artifactId><version>1</version><relativePath>../$it/pom.xml</relativePath></parent>" }.orEmpty()}
            <groupId>demo</groupId><artifactId>${artifact(name)}</artifactId><version>1</version><packaging>pom</packaging>
            $body</project>
        """.trimIndent())
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val file = PsiManager.getInstance(project).findFile(virtualFile) as XmlFile
        if (imported) {
            val parentProject = parent?.let { manager.findProject(MavenId("demo", artifact(it), "1")) }
            val properties = java.util.Properties().apply {
                parentProject?.properties?.let(::putAll)
                file.rootTag!!.findFirstSubTag("properties")?.subTags?.forEach { setProperty(it.localName, it.value.trimmedText) }
            }
            val model = MavenModel().apply {
                mavenId = MavenId("demo", artifact(name), "1")
                setProperties(properties)
                parent?.let { setParent(MavenParent(MavenId("demo", artifact(it), "1"), "../$it/pom.xml")) }
            }
            val project = MavenProject(virtualFile).apply {
                updateState(model, emptyList(), "21", emptyList(), MavenExplicitProfiles(emptyList()),
                    emptySet(), emptyMap(), this@MavenOverrideInspectionTest.directory.resolve("repository"), false)
            }
            manager.projectsTree.putVirtualFileToProjectMapping(project, project.mavenId)
            manager.projectsTree.setIgnoredState(listOf(project), false)
        }
        return file
    }

    private fun property(value: String) = "<properties><library.version>$value</library.version></properties>"
    private fun dependency(version: String?, extra: String = "") = """<dependency>
        <groupId>demo</groupId><artifactId>library</artifactId>${version?.let { "<version>$it</version>" }.orEmpty()}$extra</dependency>"""
    private fun direct(version: String?, extra: String = "") = "<dependencies>${dependency(version, extra)}</dependencies>"
    private fun managed(version: String?, extra: String = "") =
        "<dependencyManagement><dependencies>${dependency(version, extra)}</dependencies></dependencyManagement>"

    private fun inspect(file: XmlFile): List<ProblemDescriptor> {
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
        val visitor = UnnecessaryMavenOverrideInspection().buildVisitor(holder, true)
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(visitor) }
        return holder.results
    }

    private fun apply(problem: ProblemDescriptor) {
        problem.fixes!!.filterIsInstance<RemoveMavenOverrideFix>().single().applyFix(project, problem)
    }

    private fun writePolicy(text: String) {
        val path = Path.of(project.basePath!!)
        Files.createDirectories(path)
        VfsRootAccess.allowRootAccess(testRootDisposable, path.toString())
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        WriteCommandAction.runWriteCommandAction(project) {
            val file = root.findChild(PROJECT_POLICY_FILE) ?: root.createChildData(this, PROJECT_POLICY_FILE)
            file.setBinaryContent(text.toByteArray())
        }
    }

    fun testEqualPropertyOverrideIsRemovedWithoutChangingParentOrReferences() {
        val parent = pom("parent", property("2.0") + managed("\${library.version}"))
        val child = pom("child", property("2.0") + direct(null), "parent")
        val before = parent.text
        val problem = inspect(child).single()
        assertEquals("library.version", (problem.psiElement as XmlTag).localName)
        assertTrue(problem.descriptionTemplate.contains("matches the parent value '2.0'"))
        apply(problem)
        assertNull(child.rootTag!!.findFirstSubTag("properties")!!.findFirstSubTag("library.version"))
        assertNotNull(child.rootTag!!.findFirstSubTag("dependencies"))
        assertEquals(before, parent.text)
    }

    fun testNewerParentPropertyOffersUpgradeForInheritedVersionConsumers() {
        pom("parent", property("2.0") + managed("\${library.version}"))
        val child = pom("child", property("1.0"), "parent")
        val problem = inspect(child).single()
        assertTrue(problem.descriptionTemplate.contains("newer"))
        assertTrue(problem.fixes!!.single().name.contains("parent version 2.0 (was 1.0)"))
        apply(problem)
        assertNull(child.rootTag!!.findFirstSubTag("properties")!!.findFirstSubTag("library.version"))
    }

    fun testNewerOrdinaryPropertyDoesNotOfferVersionUpgrade() {
        pom("parent", "<properties><timeout>20</timeout></properties>")
        assertEmpty(inspect(pom("child", "<properties><timeout>10</timeout></properties>", "parent")))
    }

    fun testNewerPropertyWithNonVersionUsesRequiresReview() {
        pom("parent", property("2.0") + managed("\${library.version}"))
        val child = pom("child", property("1.0") + "<name>\${library.version}</name>", "parent")
        assertEmpty(inspect(child))
    }

    fun testEqualManagedVersionIsRemovedButDependencyScopeAndExclusionsRemain() {
        val parent = pom("parent", managed("2.0"))
        val child = pom("child", direct("2.0", "<scope>test</scope><exclusions><exclusion><groupId>other</groupId><artifactId>excluded</artifactId></exclusion></exclusions>"), "parent")
        val before = parent.text
        apply(inspect(child).single())
        val declaration = child.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single()
        assertNull(declaration.findFirstSubTag("version"))
        assertEquals("test", declaration.findFirstSubTag("scope")!!.value.trimmedText)
        assertNotNull(declaration.findFirstSubTag("exclusions"))
        assertEquals(before, parent.text)
    }

    fun testNewerParentManagedVersionOffersUpgrade() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        assertTrue(problem.descriptionTemplate.contains("newer dependency version 2.0"))
        apply(problem)
        assertNull(child.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version"))
    }

    fun testMavenOrderingTreatsTwoTenAsNewerThanTwoNine() {
        pom("parent", managed("2.10"))
        assertEquals(1, inspect(pom("child", direct("2.9"), "parent")).size)
    }

    fun testOlderParentVersionsKeepLocalOverrides() {
        pom("parent", property("1.0") + managed("1.0"))
        assertEmpty(inspect(pom("child", property("2.0") + direct("2.0"), "parent")))
    }

    fun testNearestParentVersionWinsOverGrandparent() {
        pom("grandparent", property("3.0") + managed("3.0"))
        pom("parent", property("1.0") + managed("1.0"), "grandparent")
        assertEmpty(inspect(pom("child", property("2.0") + direct("2.0"), "parent")))
    }

    fun testGrandparentFallbackIsFoundThroughIntermediateParent() {
        pom("grandparent", property("2.0") + managed("2.0"))
        pom("parent", "", "grandparent")
        assertEquals(2, inspect(pom("child", property("2.0") + direct("2.0"), "parent")).size)
    }

    fun testParentDependencyVersionCanBeInheritedWithoutManagement() {
        pom("parent", direct("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        apply(inspect(child).single())
        assertEmpty(child.rootTag!!.findFirstSubTag("dependencies")!!.subTags.toList())
    }

    fun testManagementProvidesVersionWhenChildDeclarationReplacesParentDependency() {
        pom("parent", direct("1.0") + managed("3.0"))
        val problem = inspect(pom("child", direct("2.0"), "parent")).single()
        assertTrue(problem.descriptionTemplate.contains("newer dependency version 3.0"))
    }

    fun testCustomizedInheritedDependencyRequiresReview() {
        pom("parent", direct("2.0"))
        assertEmpty(inspect(pom("child", direct("1.0", "<scope>test</scope>"), "parent")))
    }

    fun testLocalManagementVersionIsNotMistakenForParentVersion() {
        pom("parent", managed("1.0"))
        assertEmpty(inspect(pom("child", managed("2.0") + direct("2.0"), "parent")))
    }

    fun testCustomizedManagementOverrideRequiresReview() {
        pom("parent", managed("2.0"))
        val child = pom("child", managed("1.0", "<scope>test</scope>"), "parent")
        assertEmpty(inspect(child))
    }

    fun testSimpleManagementOverrideRemovesWholeEntry() {
        pom("parent", managed("2.0"))
        val child = pom("child", managed("1.0"), "parent")
        apply(inspect(child).single())
        assertEmpty(child.rootTag!!.findFirstSubTag("dependencyManagement")!!.findFirstSubTag("dependencies")!!.subTags.toList())
    }

    fun testParentManagementConfigurationDoesNotChangeDuringCleanup() {
        pom("parent", managed("2.0", "<scope>test</scope>"))
        assertEmpty(inspect(pom("child", managed("1.0"), "parent")))
    }

    fun testVersionlessManagementEntryBlocksGrandparentVersion() {
        pom("grandparent", managed("3.0"))
        pom("parent", managed(null), "grandparent")
        assertEmpty(inspect(pom("child", direct("1.0"), "parent")))
    }

    fun testParentVersionPropertyUsesChildInterpolationContext() {
        pom("parent", property("3.0") + managed("\${library.version}"))
        val child = pom("child", property("1.0") + direct("2.0"), "parent")
        val problems = inspect(child)
        assertEquals("Only the property upgrade is valid; dependency fallback is 1.0", 1, problems.size)
        assertEquals("library.version", (problems.single().psiElement as XmlTag).localName)
    }

    fun testSimpleParentVersionReferenceCanMatchLiteralOverride() {
        pom("parent", property("2.0") + managed("\${library.version}"))
        assertEquals(1, inspect(pom("child", direct("1.0"), "parent")).size)
    }

    fun testDifferentClassifierOrTypeKeepsOverride() {
        pom("parent", managed("2.0", "<classifier>tests</classifier><type>test-jar</type>"))
        assertEmpty(inspect(pom("child", direct("2.0"), "parent")))
    }

    fun testMatchingClassifierAndTypeAreSupported() {
        val extra = "<classifier>tests</classifier><type>test-jar</type>"
        pom("parent", managed("2.0", extra))
        assertEquals(1, inspect(pom("child", direct("1.0", extra), "parent")).size)
    }

    fun testProfilePropertyAndDependencyAmbiguityRequiresReview() {
        pom("parent", property("2.0") + managed("2.0") + "<profiles><profile><id>other</id>" +
            property("3.0") + managed("3.0") + "</profile></profiles>")
        assertEmpty(inspect(pom("child", property("2.0") + direct("2.0"), "parent")))
    }

    fun testProfileDeclarationsAreNotRemoved() {
        pom("parent", property("2.0") + managed("2.0"))
        assertEmpty(inspect(pom("child", "<profiles><profile><id>other</id>" + property("2.0") +
            direct("2.0") + "</profile></profiles>", "parent")))
    }

    fun testCompositeUnresolvedAndRangeVersionsRequireReview() {
        pom("parent", property("2.0") + managed("2.0"))
        for ((i, version) in listOf("\${missing}", "\${library.version}.0", "[1.0,3.0)", "1.0-SNAPSHOT").withIndex()) {
            assertEmpty(inspect(pom("child-$i", direct(version), "parent")))
        }
    }

    fun testPrereleaseParentDoesNotOfferUpgrade() {
        pom("parent", managed("2.0-RC1"))
        assertEmpty(inspect(pom("child", direct("1.0"), "parent")))
    }

    fun testDuplicateDeclarationsRequireReview() {
        pom("parent", "<dependencies>${dependency("1.0")}${dependency("3.0")}</dependencies>" + managed("3.0"))
        assertEmpty(inspect(pom("child", direct("2.0"), "parent")))
    }

    fun testParentPropertyExpressionDoesNotProvePropertyRedundancy() {
        pom("parent", "<properties><library.version>\${other}</library.version><other>2.0</other></properties>")
        assertEmpty(inspect(pom("child", property("2.0"), "parent")))
    }

    fun testImportedBomDoesNotFallThroughToGrandparentManagement() {
        pom("grandparent", managed("3.0"))
        pom("parent", "<dependencyManagement><dependencies><dependency><groupId>demo</groupId>" +
            "<artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope>" +
            "</dependency></dependencies></dependencyManagement>", "grandparent")
        assertEmpty(inspect(pom("child", direct("2.0"), "parent")))
    }

    fun testLocallyAvailableExternalParentDoesNotNeedToBeImported() {
        pom("parent", property("2.0") + managed("2.0"), imported = false)
        val child = pom("child", property("2.0") + direct("1.0"), "parent")
        val problems = inspect(child)
        assertEquals(MavenOverrideAnalysis.forFile(child)!!.sources.joinToString { it.text }, 2, problems.size)
    }

    fun testNewerExternalParentPropertyIsDetected() {
        pom("parent", property("2.0") + managed("\${library.version}"), imported = false)
        assertEquals(1, inspect(pom("child", property("1.0"), "parent")).size)
    }

    fun testRelativeParentWithDifferentCoordinatesDoesNotProvideFallback() {
        val parent = pom("parent", property("2.0") + managed("2.0"), imported = false)
        WriteCommandAction.runWriteCommandAction(project) {
            parent.rootTag!!.findFirstSubTag("artifactId")!!.value.setText("unrelated-parent")
        }
        assertEmpty(inspect(pom("child", property("1.0") + direct("1.0"), "parent")))
    }

    fun testConfiguredVersionPropertyRequiresReview() {
        pom("parent", property("2.0") + managed("\${library.version}"))
        val child = pom("child", property("1.0") + direct("1.0"), "parent")
        val config = Files.createDirectories(directory.resolve("child/.mvn")).resolve("maven.config")
        Files.writeString(config, "-Dlibrary.version=3.0")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(config)
        assertEmpty(inspect(child))
    }

    fun testIgnoredParentVersionDoesNotOfferUpgrade() {
        pom("parent", property("2.0") + managed("2.0"))
        project.service<VersionCheckerSettings>().state.ignoredVersions = "maven demo:library = 2.0"
        try {
            assertEmpty(inspect(pom("child", direct("1.0"), "parent")))
            assertEquals(1, inspect(pom("equal-child", direct("2.0"), "parent")).size)
        } finally { project.service<VersionCheckerSettings>().state.ignoredVersions = "" }
    }

    fun testSharedRulesRestrictDependencyAndPropertyUpgrades() {
        pom("parent", property("2.0") + managed("\${library.version}"))
        writePolicy("""{"schemaVersion":1,"rules":[
            {"ecosystem":"maven","package":"demo:library","allowedUpdates":["patch"]}
        ]}""")
        assertEmpty(inspect(pom("child", property("1.0"), "parent")))
        assertEmpty(inspect(pom("other-child", direct("1.0"), "parent")))
    }

    fun testChangedRulesRejectHeldFix() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        writePolicy("""{"schemaVersion":1,"rules":[
            {"ecosystem":"maven","package":"demo:library","allowedUpdates":[]}
        ]}""")
        val before = child.text
        apply(problem)
        assertEquals(before, child.text)
    }

    fun testCleanupCanBeUndone() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        myFixture.configureFromExistingVirtualFile(child.virtualFile)
        val before = child.text
        apply(inspect(child).single())
        val editor = FileEditorManager.getInstance(project).selectedEditor
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before, child.text)
    }

    fun testNativeEffectivePomInheritsEqualAndNewerVersionsAfterCleanup() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        for (local in listOf("1.0", "2.0")) {
            for (kind in listOf("property", "dependency", "management", "inherited-dependency", "managed-inherited-dependency")) {
                val name = "$kind-$local"
                val parentBody = when (kind) {
                    "inherited-dependency" -> direct("2.0")
                    "managed-inherited-dependency" -> direct("1.0") + managed("2.0")
                    else -> property("2.0") + managed("\${library.version}")
                }
                pom("parent-$name", parentBody)
                val body = when (kind) {
                    "property" -> property(local) + direct(null)
                    "dependency", "inherited-dependency", "managed-inherited-dependency" -> direct(local)
                    else -> managed(local) + direct(null)
                }
                val child = pom("child-$name", body, "parent-$name")
                fun effectiveVersion(): String {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    val pom = manager.findProject(child.virtualFile)!!
                    val effective = PlatformTestUtil.waitForFuture(com.intellij.openapi.application.ApplicationManager.getApplication()
                        .executeOnPooledThread(java.util.concurrent.Callable {
                            kotlinx.coroutines.runBlocking { withMavenCheckSession(manager, pom) {
                                it.evaluateEffectivePom(java.io.File(child.virtualFile.path), emptySet(), emptySet())
                            } }
                        }), 120_000) ?: error("No native effective POM for $name")
                    val root = com.intellij.openapi.util.JDOMUtil.load(effective)
                    return root.getChild("dependencies", root.namespace).getChild("dependency", root.namespace)
                        .getChildTextTrim("version", root.namespace)
                }
                assertEquals(name, local, effectiveVersion())
                apply(inspect(child).single())
                assertEquals(name, "2.0", effectiveVersion())
            }
        }
    }

    fun testChangedParentRejectsHeldFix() {
        val parent = pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        WriteCommandAction.runWriteCommandAction(project) {
            parent.rootTag!!.findFirstSubTag("dependencyManagement")!!.findFirstSubTag("dependencies")!!
                .subTags.single().findFirstSubTag("version")!!.value.setText("0.5")
        }
        val before = child.text
        apply(problem)
        assertEquals(before, child.text)
    }

    fun testChangedChildRejectsHeldFix() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        WriteCommandAction.runWriteCommandAction(project) { (problem.psiElement as XmlTag).value.setText("3.0") }
        val before = child.text
        apply(problem)
        assertEquals(before, child.text)
    }

    fun testPhysicalParentChangeWithoutVfsRefreshRejectsFix() {
        val parent = pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        FileDocumentManager.getInstance().saveAllDocuments()
        Files.writeString(Path.of(parent.virtualFile.path), parent.text.replace("<version>2.0</version>", "<version>0.5</version>"))
        val before = child.text
        apply(problem)
        assertEquals(before, child.text)
    }

    fun testPreviewRemovesOnlyCopiedOverride() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        val problem = inspect(child).single()
        val before = child.text
        val fix = problem.fixes!!.filterIsInstance<RemoveMavenOverrideFix>().single()
        val preview = fix.generatePreview(project, problem) as IntentionPreviewInfo.CustomDiff
        assertTrue(preview.originalText().contains("<version>1.0</version>"))
        assertFalse(preview.modifiedText().contains("<version>1.0</version>"))
        assertTrue(preview.modifiedText().contains("<artifactId>library</artifactId>"))
        assertEquals(before, child.text)
    }

    fun testDisabledPluginAndIgnoredOrUnimportedPomsHaveNoProblems() {
        pom("parent", managed("2.0"))
        val child = pom("child", direct("1.0"), "parent")
        project.service<VersionCheckerSettings>().state.enabled = false
        assertEmpty(inspect(child))
        project.service<VersionCheckerSettings>().state.enabled = true
        manager.projectsTree.setIgnoredState(listOf(manager.findProject(child.virtualFile)!!), true)
        assertEmpty(inspect(child))
        assertEmpty(inspect(pom("unimported", direct("1.0"), "parent", imported = false)))
    }
}
