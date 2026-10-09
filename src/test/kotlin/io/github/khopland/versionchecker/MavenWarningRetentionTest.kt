package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.maven.*
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MavenWarningRetentionTest : BasePlatformTestCase() {
    private lateinit var manager: MavenProjectsManager
    private lateinit var directory: Path
    private val adapter = MavenBuildSystemAdapter()

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("version-checker-retained-warnings").toRealPath()
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

    private fun dependency(artifact: String, version: String = "1.0") =
        "<dependency><groupId>g</groupId><artifactId>$artifact</artifactId><version>$version</version></dependency>"

    private fun imported(name: String, body: String): XmlFile {
        val path = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
        Files.writeString(path, """<project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId>
            <version>1</version>$body</project>""")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val mavenProject = MavenProject(file).apply { updateMavenId(MavenId("example", name, "1")) }
        manager.projectsTree.putVirtualFileToProjectMapping(mavenProject, mavenProject.mavenId)
        return PsiManager.getInstance(project).findFile(file) as XmlFile
    }

    private fun snapshot(file: XmlFile) = adapter.snapshot(project, file.virtualFile)!!
    private fun report(snapshot: BuildSnapshot) = UpdateReport(snapshot.declarations
        .filter { it.baseline != "2.0.10" }.map { UpdateCandidate(it, "2.0.10") })

    private fun applyFirstFix(file: XmlFile, report: UpdateReport) {
        val analysis = MavenDependencyAnalysis(MavenDomUtil.getMavenDomProjectModel(file)!!,
            manager.findProject(file.virtualFile)!!, report.mavenUpdates())
        val tag = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags
            .first { analysis.problem(it) != null }
        val problem = analysis.problem(tag)!!
        val fix = analysis.quickFixes(tag, problem).single()
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            tag, problem.message, fix, ProblemHighlightType.WARNING, true)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
    }

    fun testInspectionSharesOneSnapshotForUpdatesRelocationsAndFixesButRevalidatesBeforeEditing() {
        val file = imported("inspection", "<dependencies>${dependency("a")}${dependency("b")}</dependencies>")
        val sibling = imported("sibling", "<dependencies>${dependency("c")}</dependencies>")
        val original = snapshot(file)
        val snapshots = AtomicInteger()
        val validations = AtomicInteger()
        val checkingAdapter = object : BuildSystemAdapter by adapter {
            override fun snapshot(project: Project, file: VirtualFile): BuildSnapshot? {
                snapshots.incrementAndGet()
                return adapter.snapshot(project, file)
            }
            override fun isCurrent(project: Project, snapshot: BuildSnapshot): Boolean {
                validations.incrementAndGet()
                return adapter.isCurrent(project, snapshot)
            }
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode) =
                report(snapshot).copy(notices = listOf(UpdateNotice(snapshot.declarations.last(), NoticeKind.RELOCATED, "replacement:b")))
        }
        ExtensionTestUtil.maskExtensions(BuildSystemAdapter.EP, listOf(checkingAdapter), testRootDisposable)
        val service = project.service<VersionCheckService>()
        service.updates(checkingAdapter, original)
        PlatformTestUtil.waitWithEventsDispatching("Inspection report cached", { service.cached(original) != null }, 10_000)

        val analysis = MavenDependencyAnalysis.forFile(file)!!
        val tags = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags
        assertEquals("2.0.10", analysis.problem(tags.first())!!.latest)
        assertTrue(analysis.problem(tags.last())!!.message.contains("replacement:b"))
        val fix = analysis.quickFixes(tags.first(), analysis.problem(tags.first())!!).single()
        assertTrue(analysis.quickFixes(tags.last(), analysis.problem(tags.last())!!).isEmpty())
        assertEquals("Diagnostics and quick-fix construction should share the inspection snapshot", 1, snapshots.get())

        WriteCommandAction.runWriteCommandAction(project) {
            sibling.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single().findFirstSubTag("version")!!.value.setText("changed")
        }
        val checksBeforeApply = validations.get()
        val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
            tags.first(), "update", fix, ProblemHighlightType.WARNING, true)
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        assertEquals(checksBeforeApply + 1, validations.get())
        assertEquals("1.0", tags.first().findFirstSubTag("version")!!.value.trimmedText)
    }

    fun testOtherDependenciesAndPomsStayActionableWhileRecheckIsBlocked() {
        val first = imported("first", "<dependencies>${dependency("a")}${dependency("b")}</dependencies>")
        val other = imported("other", "<dependencies>${dependency("c")}</dependencies>")
        val checked = CopyOnWriteArrayList<BuildSnapshot>()
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val checkingAdapter = object : BuildSystemAdapter by adapter {
            override suspend fun check(project: Project, snapshot: BuildSnapshot, mode: UpdateMode): UpdateReport {
                checked += snapshot
                gate.await()
                return report(snapshot)
            }
        }
        val service = project.service<VersionCheckService>()
        val original = listOf(snapshot(first), snapshot(other))
        original.forEach { service.updates(checkingAdapter, it) }
        PlatformTestUtil.waitWithEventsDispatching("Initial reports cached", {
            original.all { service.cached(it) != null }
        }, 10_000)
        val initialReport = service.cached(original.first())!!
        gate = CompletableDeferred()
        try {
            applyFirstFix(first, initialReport)
            val changed = snapshot(first)
            assertNull("An old report must not count as a fresh check", service.cached(changed))
            val remaining = service.updates(checkingAdapter, changed)!!
            assertEquals(listOf("g:b"), remaining.candidates.map { it.declaration.artifact.name })
            assertTrue(remaining.candidates.single().declaration.id != original.first().declarations.last().id)
            val sibling = snapshot(other)
            assertNull(service.cached(sibling))
            assertEquals("g:c", service.updates(checkingAdapter, sibling)!!.candidates.single().declaration.artifact.name)
            PlatformTestUtil.waitWithEventsDispatching("Recheck starts", { checked.size == 3 }, 10_000)
            applyFirstFix(first, remaining)
            assertTrue(first.text.contains("<artifactId>b</artifactId><version>2.0.10</version>"))
            assertTrue(service.updates(checkingAdapter, snapshot(first))!!.candidates.isEmpty())
            applyFirstFix(other, service.updates(checkingAdapter, snapshot(other))!!)
            assertTrue(other.text.contains("<artifactId>c</artifactId><version>2.0.10</version>"))
            val latest = listOf(snapshot(first), snapshot(other))
            latest.forEach { service.updates(checkingAdapter, it) }
            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Fresh reports replace retained warnings", {
                latest.all { service.cached(it)?.candidates?.isEmpty() == true }
            }, 10_000)
        } finally { gate.complete(Unit) }
    }

    fun testSharedPropertyEditRemovesItsWarningsAndRetainsUnchangedPluginAndParent() {
        val file = imported("properties", """<parent><groupId>external</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
            <properties><shared.version>1.0</shared.version></properties>
            <dependencies>${dependency("a", "\${shared.version}")}${dependency("b", "\${shared.version}")}</dependencies>
            <build><plugins><plugin><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></plugin></plugins></build>""")
        val previous = snapshot(file)
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), report(previous))
        applyFirstFix(file, report(previous))
        val retained = cache.retainedInspectionReport(adapter, snapshot(file))!!
        assertEquals(setOf(MavenArtifactKind.PARENT, MavenArtifactKind.PLUGIN),
            retained.candidates.map { it.declaration.coordinate().artifactKind }.toSet())
        assertEquals("2.0.10", file.rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
    }

    fun testCoordinateEditsAndRemovedDeclarationsDoNotRetainWarningsOrRelocations() {
        val file = imported("coordinates", "<dependencies>${dependency("a")}${dependency("b")}${dependency("c")}</dependencies>")
        val previous = snapshot(file)
        val result = report(previous).copy(notices = previous.declarations.map {
            UpdateNotice(it, NoticeKind.RELOCATED, "replacement:artifact")
        })
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), result)
        val tags = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags
        WriteCommandAction.runWriteCommandAction(project) {
            tags[0].findFirstSubTag("artifactId")!!.value.setText("different")
            tags[1].delete()
        }
        val retained = cache.retainedInspectionReport(adapter, snapshot(file))!!
        assertEquals(listOf("g:c"), retained.candidates.map { it.declaration.artifact.name })
        assertEquals(listOf("g:c"), retained.notices.map { it.declaration.artifact.name })
        assertEquals(snapshot(file).declarations.last(), retained.notices.single().declaration)
    }

    fun testChangedSettingsConfigurationAndExplicitRefreshDiscardRetainedWarnings() {
        val file = imported("configuration", "<dependencies>${dependency("a")}</dependencies>")
        val config = Files.createDirectories(directory.resolve("configuration/.mvn")).resolve("maven.config")
        Files.writeString(config, "-Dprofile=original")
        val previous = snapshot(file)
        val cache = VersionResultCache()
        cache.put(previous, cache.begin(previous), report(previous))
        val offline = manager.generalSettings.isWorkOffline
        try {
            manager.generalSettings.isWorkOffline = !offline
            assertNull(cache.retainedInspectionReport(adapter, snapshot(file)))
        } finally { manager.generalSettings.isWorkOffline = offline }
        Files.writeString(config, "-Dprofile=changed")
        assertNull(cache.retainedInspectionReport(adapter, snapshot(file)))
        assertNotNull(cache.retainedInspectionReport(adapter, previous))
        cache.invalidateSource(adapter.id, previous.sourceFile)
        assertNull(cache.retainedInspectionReport(adapter, previous))
    }

    fun testRetentionDoesNotExtendMetadataExpiryOrReuseAFailedCheck() {
        val file = imported("expiry", "<dependencies>${dependency("a")}</dependencies>")
        val previous = snapshot(file)
        var time = 0L
        val cache = VersionResultCache { time }
        cache.put(previous, cache.begin(previous), report(previous).copy(validUntilNanos = 100))
        WriteCommandAction.runWriteCommandAction(project) {
            file.rootTag!!.findFirstSubTag("artifactId")!!.value.setText("renamed-project")
        }
        val current = snapshot(file)
        assertNull(cache.get(current))
        assertNotNull(cache.retainedInspectionReport(adapter, current))
        time = 100
        assertNull(cache.retainedInspectionReport(adapter, current))
        cache.put(current, cache.begin(current), UpdateReport(failure = "Repository unavailable"))
        assertNull(cache.retainedInspectionReport(adapter, current))
    }
}
