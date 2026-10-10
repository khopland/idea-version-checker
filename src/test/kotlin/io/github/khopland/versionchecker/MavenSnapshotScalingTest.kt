package io.github.khopland.versionchecker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.maven.MavenBuildSystemAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class MavenSnapshotScalingTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-maven-inputs").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
    }

    override fun tearDown() {
        try {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            directory.toFile().deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun imported(name: String, body: String): MavenProject {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """<project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion><groupId>scaling</groupId><artifactId>${name.substringAfterLast('/')}</artifactId>
            <version>1</version>$body</project>""")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        return MavenProject(file).apply {
            updateState(MavenModel().apply { mavenId = MavenId("scaling", name.substringAfterLast('/'), "1") },
                emptyList(), "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(),
                this@MavenSnapshotScalingTest.directory.resolve("repository"), false)
            manager.projectsTree.putVirtualFileToProjectMapping(this, mavenId)
        }
    }

    private fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
        ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 60_000)

    private val dependency = "<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.2.3</version></dependency></dependencies>"

    fun testFastEditorScopeIsExplicitPartialAndCannotSatisfyAFullAudit() {
        val platform = imported("platform", """<dependencyManagement><dependencies>
            <dependency><groupId>g</groupId><artifactId>used</artifactId><version>1.0</version></dependency>
            <dependency><groupId>g</groupId><artifactId>unused</artifactId><version>1.0</version></dependency>
            <dependency><groupId>g</groupId><artifactId>platform-bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>
            </dependencies></dependencyManagement>""")
        imported("consumer", """<dependencies><dependency><groupId>g</groupId><artifactId>used</artifactId><version>1.0</version></dependency></dependencies>""")
        val settings = project.service<VersionCheckerSettings>().state
        val previous = settings.mavenFastEditorChecks
        val adapter = MavenBuildSystemAdapter()
        try {
            settings.mavenFastEditorChecks = false
            val original = adapter.snapshot(project, platform.file)!!
            assertEquals(3, original.declarations.size)
            settings.mavenFastEditorChecks = true
            assertFalse(adapter.isCurrent(project, original))
            val fast = adapter.snapshot(project, platform.file)!!
            assertEquals(setOf("g:used", "g:platform-bom"), fast.declarations.map { it.artifact.name }.toSet())
            assertNotNull(fast.coverageDescription)
            assertTrue(VersionCheckStatus(CheckPhase.PARTIAL, coverageDescription = fast.coverageDescription).text.contains("partial"))
            val full = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, platform.file.path)) }.single()
            assertEquals(3, full.declarations.size)
            assertNull(full.coverageDescription)
            val cache = VersionResultCache()
            assertTrue(cache.put(fast, cache.begin(fast), UpdateReport()))
            assertNotNull(cache.get(fast))
            assertNull("A fast editor result must not authorize the full preview", cache.get(full))
        } finally { settings.mavenFastEditorChecks = previous }
    }

    fun testFastEditorScopeRetainsParentOverridesAndFallsBackForUnresolvedConsumers() {
        imported("parent", """<packaging>pom</packaging><dependencyManagement><dependencies>
            <dependency><groupId>g</groupId><artifactId>inherited</artifactId><version>1.0</version></dependency>
            </dependencies></dependencyManagement>""")
        val child = imported("child", """<parent><groupId>scaling</groupId><artifactId>parent</artifactId><version>1</version>
            <relativePath>../parent/pom.xml</relativePath></parent><dependencyManagement><dependencies>
            <dependency><groupId>g</groupId><artifactId>inherited</artifactId><version>2.0</version></dependency>
            <dependency><groupId>g</groupId><artifactId>unused</artifactId><version>1.0</version></dependency>
            </dependencies></dependencyManagement>""")
        val settings = project.service<VersionCheckerSettings>().state
        val previous = settings.mavenFastEditorChecks
        try {
            settings.mavenFastEditorChecks = true
            val adapter = MavenBuildSystemAdapter()
            val fast = adapter.snapshot(project, child.file)!!
            assertEquals(listOf("g:inherited"), fast.declarations.map { it.artifact.name })
            imported("unresolved", """<dependencies><dependency><groupId>${'$'}{unknown.group}</groupId><artifactId>consumer</artifactId><version>1.0</version></dependency></dependencies>""")
            val conservative = adapter.snapshot(project, child.file)!!
            assertEquals(2, conservative.declarations.size)
            assertNull(conservative.coverageDescription)
        } finally { settings.mavenFastEditorChecks = previous }
    }

    fun testCompletedFullAuditRemainsVisibleWithFastEditorChecksEnabled() {
        val platform = imported("audited-platform", """<dependencyManagement><dependencies>
            <dependency><groupId>g</groupId><artifactId>unused</artifactId><version>1.0</version></dependency>
            </dependencies></dependencyManagement>""")
        val settings = project.service<VersionCheckerSettings>().state
        val previous = settings.mavenFastEditorChecks
        val adapter = MavenBuildSystemAdapter()
        try {
            settings.mavenFastEditorChecks = true
            assertNotNull(adapter.snapshot(project, platform.file)!!.coverageDescription)
            val full = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, platform.file.path)) }.single()
            val checker = object : BuildSystemAdapter by adapter {
                override val capabilities = AdapterCapabilities(incrementalInspections = false)
                override suspend fun check(project: com.intellij.openapi.project.Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                    UpdateReport(snapshot.declarations.map { UpdateCandidate(it, "2.0") })
            }
            val service = project.service<VersionCheckService>()
            background { service.checkNow(checker, full, UpdateMode.MAJOR) }
            val editor = adapter.snapshot(project, platform.file)!!
            assertNull("A completed full audit must not immediately become partial in the editor", editor.coverageDescription)
            assertEquals(1, editor.declarations.size)
            assertEquals(CheckPhase.CHECKED, service.status(adapter, editor).phase)
            assertEquals(1, service.cached(editor)!!.candidates.size)
            service.invalidateForPreview(adapter, platform.file)
            assertNotNull("After refresh invalidation, the editor must return to its configured fast scope",
                adapter.snapshot(project, platform.file)!!.coverageDescription)
            val failedChecker = object : BuildSystemAdapter by checker {
                override suspend fun check(project: com.intellij.openapi.project.Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                    UpdateReport(failure = "Repository unavailable")
            }
            assertTrue(background { runCatching { service.checkNow(failedChecker, full, UpdateMode.MAJOR) }.isFailure })
            val failedAudit = adapter.snapshot(project, platform.file)!!
            assertNull("A failed full audit must remain visible until retry or expiry", failedAudit.coverageDescription)
            assertEquals(CheckPhase.FAILED, service.status(adapter, failedAudit).phase)
        } finally { settings.mavenFastEditorChecks = previous }
    }
    private fun reports(snapshots: List<BuildSnapshot>) = snapshots.associateWith { snapshot ->
        UpdateReport(snapshot.declarations.take(1).map { UpdateCandidate(it, "1.2.9") })
    }

    fun testUnselectedUnsavedSiblingRejectsPreviewBeforeAnyEdit() {
        val selected = imported("selected", dependency)
        val sibling = imported("sibling", dependency)
        val adapter = MavenBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, selected.file)!!
        val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
        assertEquals(1, plan.changes.size)
        val document = FileDocumentManager.getInstance().getDocument(sibling.file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("<artifactId>a</artifactId>", "<artifactId>changed</artifactId>")) }
        assertFalse(adapter.areCurrent(project, listOf(snapshot)))
        assertFalse(plan.apply(project))
        assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
        assertTrue(background { runCatching { adapter.prepareUpdates(project, reports(listOf(snapshot))) }.isFailure })
    }

    fun testModuleConfigurationIsSeparateAndSharedAncestorIsRevalidatedBeforeApply() {
        val first = imported("root/modules/first", dependency)
        val second = imported("root/modules/second", dependency)
        val sharedConfig = Files.createDirectories(directory.resolve("root/.mvn")).resolve("maven.config")
        val localConfig = Files.createDirectories(directory.resolve("root/modules/first/.mvn")).resolve("maven.config")
        Files.writeString(sharedConfig, "-Dshared=one")
        Files.writeString(localConfig, "-Dlocal=one")
        val adapter = MavenBuildSystemAdapter()
        val firstSnapshot = adapter.snapshot(project, first.file)!!
        val secondSnapshot = adapter.snapshot(project, second.file)!!
        assertTrue(firstSnapshot.fingerprint.files.containsKey(localConfig.toString()))
        assertFalse(secondSnapshot.fingerprint.files.containsKey(localConfig.toString()))
        assertTrue(secondSnapshot.fingerprint.files.containsKey(sharedConfig.toString()))
        Files.writeString(localConfig, "-Dlocal=changed")
        assertFalse(adapter.isCurrent(project, firstSnapshot))
        assertTrue(adapter.isCurrent(project, secondSnapshot))
        val current = listOf(adapter.snapshot(project, first.file)!!, secondSnapshot)
        val plan = background { adapter.prepareUpdates(project, reports(current)) }
        assertEquals(2, plan.changes.size)
        Files.writeString(sharedConfig, "-Dshared=changed")
        assertFalse(adapter.areCurrent(project, current))
        assertFalse(plan.apply(project))
        assertTrue(listOf(first, second).all { FileDocumentManager.getInstance().getDocument(it.file)!!.text.contains("<version>1.2.3</version>") })
    }

    fun testImportIgnoreAndPolicyChangesCannotReuseAPreviousInputPass() {
        val selected = imported("selected", dependency)
        val sibling = imported("sibling", dependency)
        val adapter = MavenBuildSystemAdapter()
        val snapshot = adapter.snapshot(project, selected.file)!!
        val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
        manager.projectsTree.setIgnoredState(listOf(sibling), true)
        assertFalse(adapter.areCurrent(project, listOf(snapshot)))
        assertFalse(plan.apply(project))
        val beforeImport = adapter.snapshot(project, selected.file)!!
        imported("new-module", dependency)
        assertFalse(adapter.isCurrent(project, beforeImport))
        val beforePolicy = adapter.snapshot(project, selected.file)!!
        val options = project.service<VersionCheckerSettings>().state
        val policy = options.deprecatedDependencies
        try {
            options.deprecatedDependencies = "g:a = Replaced"
            assertFalse(adapter.isCurrent(project, beforePolicy))
        } finally { options.deprecatedDependencies = policy }
    }

    fun testExternalSettingsEditRejectsPreparedPreview() {
        val selected = imported("selected", dependency)
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, "<settings><servers/></settings>")
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val adapter = MavenBuildSystemAdapter()
            val snapshot = adapter.snapshot(project, selected.file)!!
            val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
            assertEquals(1, plan.changes.size)
            Files.writeString(settings, "<settings><servers><server><id>changed</id></server></servers></settings>")
            assertFalse(adapter.isCurrent(project, snapshot))
            assertFalse(plan.apply(project))
            assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testUnsavedSettingsAndAncestorConfigurationRejectPreparedPreviews() {
        val selected = imported("root/module", dependency)
        val settings = directory.resolve("settings.xml")
        Files.writeString(settings, "<settings/>")
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val configDirectory = Files.createDirectories(directory.resolve("root/.mvn"))
            val paths = listOf(settings, configDirectory.resolve("maven.config"), configDirectory.resolve("jvm.config"),
                Files.createDirectories(configDirectory.resolve("wrapper")).resolve("maven-wrapper.properties"))
            for (path in paths) {
                val original = if (path == settings) "<settings/>" else "original"
                Files.writeString(path, original)
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
                val document = FileDocumentManager.getInstance().getDocument(file)!!
                val adapter = MavenBuildSystemAdapter()
                val snapshot = adapter.snapshot(project, selected.file)!!
                val plan = background { adapter.prepareUpdates(project, reports(listOf(snapshot))) }
                assertEquals(1, plan.changes.size)
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original + "\nchanged") }
                assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
                assertFalse("Unsaved ${path.fileName} must invalidate its preview", adapter.isCurrent(project, snapshot))
                assertFalse(plan.apply(project))
                assertTrue(FileDocumentManager.getInstance().getDocument(selected.file)!!.text.contains("<version>1.2.3</version>"))
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
                FileDocumentManager.getInstance().saveDocument(document)
            }
        } finally { manager.generalSettings.setUserSettingsFile(previousSettings) }
    }

    fun testSharedProjectInputsDiscoveryValidationAndApplication() {
        imported("root", "<packaging>pom</packaging>")
        val dependencies = (1..100).joinToString("") {
            "<dependency><groupId>g</groupId><artifactId>dependency-$it</artifactId><version>1.2.3</version></dependency>"
        }
        val modules = (1..100).map { imported("root/modules/module-$it", "<dependencies>$dependencies</dependencies>") }
        val adapter = MavenBuildSystemAdapter()
        val selection = BuildSelection(UpdateScope.WHOLE_PROJECT)
        val snapshots = background { adapter.discover(project, selection) }
        assertEquals(101, snapshots.size)
        assertEquals(10_000, snapshots.sumOf { it.declarations.size })
        val samples = mutableMapOf<String, MutableList<Long>>()
        repeat(5) {
            for (path in listOf("discovery", "validation")) {
                val started = System.nanoTime()
                if (path == "discovery") assertEquals(snapshots, background { adapter.discover(project, selection) })
                else assertTrue(adapter.areCurrent(project, snapshots))
                val elapsed = System.nanoTime() - started
                samples.getOrPut(path) { mutableListOf() } += elapsed
                println("version-check benchmark=maven-inputs path=$path elapsedNs=$elapsed poms=101 declarations=10000")
            }
        }
        for ((path, durations) in samples) {
            val sorted = durations.sorted()
            println("version-check benchmark=maven-inputs path=$path samples=5 medianNs=${sorted[2]} p95Ns=${sorted.last()}")
        }
        val plan = background { adapter.prepareUpdates(project, reports(snapshots)) }
        assertEquals(100, plan.changes.size)
        assertTrue(plan.apply(project))
        assertTrue(modules.all { FileDocumentManager.getInstance().getDocument(it.file)!!.text.contains("<version>1.2.9</version>") })

        // A fresh validation pass must observe an uncommitted edit in an unselected sibling.
        val selected = adapter.snapshot(project, modules.first().file)!!
        val document = FileDocumentManager.getInstance().getDocument(modules.last().file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(document.text.replace("dependency-100", "changed")) }
        assertFalse(adapter.areCurrent(project, listOf(selected)))
    }
}
