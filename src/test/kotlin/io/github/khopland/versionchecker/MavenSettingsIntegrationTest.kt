package io.github.khopland.versionchecker

import io.github.khopland.versionchecker.maven.*
import io.github.khopland.versionchecker.core.*

import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenArtifactInfo
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in: starts IDEA's real Maven server and may download the Versions goal from Maven Central. */
class MavenSettingsIntegrationTest : BasePlatformTestCase() {
    fun testDependencyFiltersKeepManagedDeclarationsAndReduceNativeRequests() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-filter-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val requests = CopyOnWriteArrayList<String>()
        val publishedVersion = AtomicReference("1.1")
        val authorization = "Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != authorization) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestURI.path.endsWith("/maven-metadata.xml")) {
                    requests += exchange.requestURI.path
                    val artifact = exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/')
                    val body = """<metadata><groupId>example.filters</groupId><artifactId>$artifact</artifactId><versioning>
                        <latest>${publishedVersion.get()}</latest><release>${publishedVersion.get()}</release>
                        <versions><version>1.0</version><version>${publishedVersion.get()}</version></versions>
                        <lastUpdated>20261008000000</lastUpdated></versioning></metadata>""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                } else if ("/fixture-bom/" in exchange.requestURI.path && exchange.requestURI.path.endsWith(".pom")) {
                    val version = exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/')
                    val body = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <groupId>example.filters</groupId><artifactId>fixture-bom</artifactId><version>$version</version><packaging>pom</packaging>
                        <dependencyManagement><dependencies><dependency><groupId>example.filters</groupId><artifactId>artifact-5</artifactId>
                        <version>1.0</version></dependency></dependencies></dependencyManagement></project>""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                } else exchange.sendResponseHeaders(404, -1)
            } finally { exchange.close() }
        }
        server.start()
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        val previousSettings = manager.generalSettings.userSettingsFile
        try {
            val repository = Path.of(System.getProperty("user.home"), ".m2", "repository")
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                <localRepository>$repository</localRepository>
                <servers><server><id>filter-mirror</id><username>fixture</username><password>password</password></server></servers>
                <mirrors><mirror><id>filter-mirror</id><mirrorOf>filter-source</mirrorOf><url>http://127.0.0.1:${server.address.port}/</url></mirror></mirrors>
                <profiles><profile><id>filter-repositories</id><repositories>
                  <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url><releases><enabled>false</enabled></releases></repository>
                  <repository><id>filter-source</id><url>http://127.0.0.1:1/unmirrored</url><releases><updatePolicy>daily</updatePolicy></releases></repository>
                </repositories></profile></profiles><activeProfiles><activeProfile>filter-repositories</activeProfile></activeProfiles></settings>""")
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val allCoordinates = (1..5).map { DependencyVersion("example.filters", "artifact-$it", "1.0") }
            val parentPom = directory.resolve("pom.xml")
            Files.writeString(parentPom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>example.filters</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>${allCoordinates.joinToString("") {
                    "<dependency><groupId>${it.groupId}</groupId><artifactId>${it.artifactId}</artifactId><version>${it.version}</version></dependency>"
                }}</dependencies></dependencyManagement></project>""")
            val childPom = Files.createDirectories(directory.resolve("child")).resolve("pom.xml")
            Files.writeString(childPom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>example.filters</groupId><artifactId>parent</artifactId><version>1</version></parent>
                <artifactId>child</artifactId><properties><fixture.group>example.filters</fixture.group><fixture.artifact>artifact-1</fixture.artifact></properties>
                <dependencies><dependency><groupId>${'$'}{fixture.group}</groupId><artifactId>${'$'}{fixture.artifact}</artifactId></dependency></dependencies></project>""")
            val parentFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parentPom)!!
            val parent = MavenProject(parentFile).apply { updateMavenId(MavenId("example.filters", "parent", "1")) }
            manager.projectsTree.putVirtualFileToProjectMapping(parent, parent.mavenId)
            val childFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(childPom)!!
            val child = MavenProject(childFile).apply {
                updateState(MavenModel().apply { mavenId = MavenId("example.filters", "child", "1") },
                    allCoordinates.map { MavenArtifactInfo(it.groupId, it.artifactId, it.version, "jar", null) },
                    "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(), repository, false)
            }
            manager.projectsTree.putVirtualFileToProjectMapping(child, child.mavenId)
            val adapter = MavenBuildSystemAdapter()
            // With transitive management disabled, parent-managed entries alone are already
            // narrowed by the pinned goal. Verify that case before adding inherited dependencies.
            val managedOnlyBefore = requests.size
            val managedOnly = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, child) }
            })
            assertEquals("1.1", PlatformTestUtil.waitForFuture(managedOnly, 120_000)[allCoordinates.first()])
            assertEquals(1, requests.size - managedOnlyBefore)
            val parentDocument = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(parentFile)!!
            WriteCommandAction.runWriteCommandAction(project) {
                parentDocument.setText(parentDocument.text.replace("</project>", "<dependencies>${allCoordinates.drop(1).joinToString("") {
                    "<dependency><groupId>${it.groupId}</groupId><artifactId>${it.artifactId}</artifactId><version>${it.version}</version></dependency>"
                }}</dependencies></project>"))
            }
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertTrue(Files.readString(parentPom).contains("<dependencyManagement>"))
            val snapshot = adapter.snapshot(project, childFile)!!
            assertEquals(listOf(allCoordinates.first()), snapshot.declarations.map { it.coordinate() })
            val samples = mutableMapOf<String, MutableList<Long>>()
            repeat(5) {
                for (filtered in listOf(false, true)) {
                    // Force the same freshness boundary for both paths, including inherited entries
                    // that the child snapshot does not own. Retain native artifacts and metadata.
                    MavenRepositoryMetadata.expireUpdates(child.localRepositoryPath, allCoordinates, setOf("filter-mirror"))
                    val before = requests.size
                    val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        runBlocking {
                            val started = System.nanoTime()
                            val updates = MavenVersionLookup.checkAll(manager, child, UpdateMode.MAJOR,
                                setOf(MavenArtifactKind.DEPENDENCY), if (filtered) snapshot.declarations.map { it.coordinate() } else null)
                            updates to (System.nanoTime() - started)
                        }
                    })
                    val (updates, nanos) = PlatformTestUtil.waitForFuture(future, 120_000)
                    assertEquals("1.1", updates[allCoordinates.first()])
                    assertEquals(if (filtered) 1 else 5, requests.size - before)
                    assertEquals(if (filtered) 1 else 5, updates.size)
                    val label = if (filtered) "filtered" else "broad"
                    samples.getOrPut(label) { mutableListOf() } += nanos
                    println("version-check benchmark=maven path=$label elapsedNs=$nanos httpRequests=${requests.size - before}")
                }
            }
            for ((label, nanos) in samples) println("version-check benchmark=maven path=$label samples=${nanos.size} medianNs=${nanos.sorted()[nanos.size / 2]} p95Ns=${nanos.max()}")
            publishedVersion.set("1.2")
            val before = requests.size
            val fresh = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { adapter.check(project, snapshot, UpdateMode.MAJOR) }
            })
            assertEquals("1.2", PlatformTestUtil.waitForFuture(fresh, 120_000).candidates.single().version)
            assertEquals("The filtered path must refresh selected metadata under a daily policy", 1, requests.size - before)
            val empty = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.checkAll(manager, child, UpdateMode.MAJOR, setOf(MavenArtifactKind.DEPENDENCY), emptyList()) }
            })
            assertTrue(PlatformTestUtil.waitForFuture(empty, 120_000).isEmpty())
            assertEquals("An empty category must not execute a broad dependency goal", 1, requests.size - before)

            val childDocument = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(childFile)!!
            WriteCommandAction.runWriteCommandAction(project) {
                childDocument.setText(childDocument.text.replace("</project>", """<dependencyManagement><dependencies><dependency>
                    <groupId>example.filters</groupId><artifactId>fixture-bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope>
                    </dependency></dependencies></dependencyManagement><profiles>
                    <profile><id>on</id><activation><activeByDefault>true</activeByDefault></activation><dependencies><dependency>
                      <groupId>example.filters</groupId><artifactId>artifact-2</artifactId><version>1.0</version></dependency></dependencies></profile>
                    <profile><id>off</id><dependencyManagement><dependencies><dependency><groupId>example.filters</groupId>
                      <artifactId>artifact-6</artifactId><version>1.0</version></dependency></dependencies></dependencyManagement></profile>
                    </profiles></project>"""))
            }
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            child.updateState(MavenModel().apply { mavenId = child.mavenId },
                allCoordinates.map { MavenArtifactInfo(it.groupId, it.artifactId, it.version, "jar", null) },
                "21", emptyList(), MavenExplicitProfiles(listOf("on")), emptySet(), emptyMap(), repository, false)
            val profileSnapshot = adapter.snapshot(project, childFile)!!
            assertEquals(setOf("example.filters:artifact-1", "example.filters:artifact-2", "example.filters:fixture-bom"),
                profileSnapshot.declarations.map { it.artifact.name }.toSet())
            val profileBefore = requests.size
            val profileCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { adapter.check(project, profileSnapshot, UpdateMode.MAJOR) }
            })
            val profileReport = PlatformTestUtil.waitForFuture(profileCheck, 120_000)
            assertEquals(3, profileReport.candidates.size)
            assertTrue(profileReport.candidates.all { it.version == "1.2" })
            assertEquals("Check only declared dependencies, the active profile and the BOM itself", 3, requests.size - profileBefore)
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(previousSettings)
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }

    fun testDemoGlobalActionsAcrossNestedModules() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-demo-integration-")
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), directory.toRealPath().toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        // BasePlatformTestCase can reuse its project and Maven tree between test methods.
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        val oldSettings = manager.generalSettings.userSettingsFile
        try {
            val demo = Path.of("src/test/resources/maven-demo").toAbsolutePath()
            val modules = listOf("" to "version-checker-demo", "literal-dependencies" to "literal-dependencies",
                "property-dependencies" to "property-dependencies", "nested" to "nested",
                "nested/managed-dependencies" to "managed-dependencies")
            for ((path, artifact) in modules) {
                val pom = Files.createDirectories(directory.resolve(path)).resolve("pom.xml")
                Files.copy(demo.resolve(path).resolve("pom.xml"), pom)
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
                val mavenProject = MavenProject(virtual).apply {
                    updateMavenId(MavenId("io.github.khopland.demo", artifact, "1.0-SNAPSHOT"))
                }
                manager.projectsTree.putVirtualFileToProjectMapping(mavenProject, mavenProject.mavenId)
            }
            val settings = directory.resolve("settings.xml")
            Files.copy(demo.resolve("settings-demo.xml"), settings)
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val rootFile = manager.projects.single { it.mavenId.artifactId == "version-checker-demo" }.file
            for (mode in UpdateMode.entries) {
                val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking {
                        withBackgroundProgress(project, "Checking demo: ${mode.label}", cancellable = true) {
                            project.service<BulkUpdateService>().createPlan(mode)
                        }
                    }
                })
                val plan = PlatformTestUtil.waitForFuture(future, 120_000)
                assertTrue("Expected literal and property updates for ${mode.label}", plan.changes.size >= 3)
                assertTrue(plan.changes.any { "literal-dependencies" in it.location })
                assertTrue(plan.changes.any { "property-dependencies" in it.location })
                val plugins = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking {
                        project.service<BulkUpdateService>().createPlan(mode, UpdateScope.CURRENT_FILE, rootFile)
                    }
                })
                val pluginPlan = PlatformTestUtil.waitForFuture(plugins, 120_000)
                assertTrue("Expected compiler and Surefire updates for ${mode.label}", pluginPlan.changes.size >= 2)
                assertTrue(pluginPlan.changes.all { it.location.startsWith(rootFile.path) })
                if (mode == UpdateMode.PATCH) {
                    assertEquals(setOf("3.12.1", "3.2.5"), pluginPlan.changes.filter { "maven-compiler-plugin" in it.location || "maven-surefire-plugin" in it.location }.map { it.latest }.toSet())
                }
            }
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(oldSettings)
            directory.toFile().deleteRecursively()
        }
    }

    fun testSettingsProfileMirrorAndServerAuthentication() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-integration-")
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), directory.toRealPath().toString())
        val metadataRequests = AtomicInteger()
        val publishedVersion = AtomicReference("2.0")
        val expectedAuth = AtomicReference("Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray()))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != expectedAuth.get()) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestURI.path.endsWith("/maven-metadata.xml")) {
                    metadataRequests.incrementAndGet()
                    val artifact = if ("/fixture-plugin/" in exchange.requestURI.path) "fixture-plugin" else "fixture"
                    val body = """
                        <metadata><groupId>example.versionchecker</groupId><artifactId>$artifact</artifactId>
                        <versioning><latest>4.0-SNAPSHOT</latest><release>3.0-RC1</release><versions>
                        <version>1.0</version><version>1.0.1</version><version>1.1</version><version>1.1.1</version><version>${publishedVersion.get()}</version><version>3.0-RC1</version><version>4.0-SNAPSHOT</version>
                        </versions><lastUpdated>20261005000000</lastUpdated></versioning></metadata>
                    """.trimIndent().toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                } else if ("/fixture-plugin/" in exchange.requestURI.path && exchange.requestURI.path.endsWith(".pom")) {
                    val version = exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/')
                    val body = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <groupId>example.versionchecker</groupId><artifactId>fixture-plugin</artifactId><version>$version</version>
                        <packaging>maven-plugin</packaging><prerequisites><maven>3.6.3</maven></prerequisites></project>""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        val oldSettings = manager.generalSettings.userSettingsFile
        try {
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <servers><server><id>private-fixture</id><username>fixture</username><password>password</password></server></servers>
                  <mirrors><mirror><id>private-fixture</id><mirrorOf>fixture-source</mirrorOf>
                    <url>http://127.0.0.1:${server.address.port}/</url></mirror></mirrors>
                  <profiles><profile><id>fixture-profile</id><repositories>
                    <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url>
                      <releases><enabled>false</enabled></releases><snapshots><enabled>false</enabled></snapshots></repository>
                    <repository><id>fixture-source</id><url>http://127.0.0.1:1/unmirrored</url>
                      <releases><updatePolicy>daily</updatePolicy></releases></repository>
                  </repositories><pluginRepositories><pluginRepository><id>fixture-source</id><url>http://127.0.0.1:1/unmirrored</url>
                    <releases><updatePolicy>daily</updatePolicy></releases></pluginRepository></pluginRepositories></profile></profiles>
                  <activeProfiles><activeProfile>fixture-profile</activeProfile></activeProfiles>
                </settings>
            """.trimIndent())
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>example.versionchecker</groupId><artifactId>app</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>child</module></modules>
                  <properties><fixture.version>1.0</fixture.version><fixture-plugin.version>1.0</fixture-plugin.version></properties>
                  <dependencies><dependency><groupId>example.versionchecker</groupId><artifactId>fixture</artifactId><version>${'$'}{fixture.version}</version></dependency></dependencies>
                  <build><pluginManagement><plugins><plugin><groupId>example.versionchecker</groupId><artifactId>fixture-plugin</artifactId>
                    <version>${'$'}{fixture-plugin.version}</version></plugin></plugins></pluginManagement></build>
                </project>
            """.trimIndent())
            val childPom = Files.createDirectories(directory.resolve("child")).resolve("pom.xml")
            Files.writeString(childPom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>example.versionchecker</groupId><artifactId>app</artifactId><version>1</version></parent>
                <artifactId>child</artifactId><dependencies><dependency><groupId>example.versionchecker</groupId>
                <artifactId>fixture</artifactId><version>1.1</version></dependency></dependencies>
                <build><plugins><plugin><groupId>example.versionchecker</groupId><artifactId>fixture-plugin</artifactId><version>1.1</version></plugin></plugins></build></project>
            """.trimIndent())
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
            val mavenProject = MavenProject(virtualFile)
            mavenProject.updateMavenId(MavenId("example.versionchecker", "app", "1"))
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    withBackgroundProgress(project, "Checking Maven dependencies", cancellable = true) {
                        MavenVersionLookup.check(manager, mavenProject)
                    }
                }
            })
            val updates = PlatformTestUtil.waitForFuture(future, 120_000)
            assertEquals("2.0", updates[DependencyVersion("example.versionchecker", "fixture", "1.0")])
            assertTrue("Expected authenticated metadata requests through the settings mirror", metadataRequests.get() > 0)
            val repositories = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.effectiveRepositoryIds(manager, mavenProject) }
            })
            assertEquals("Fallback must use the active settings profile, its mirror, and release policy",
                setOf("private-fixture"), PlatformTestUtil.waitForFuture(repositories, 120_000))
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.0.1", UpdateMode.MINOR to "1.1.1")) {
                val restricted = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { MavenVersionLookup.check(manager, mavenProject, mode) }
                })
                assertEquals(expected, PlatformTestUtil.waitForFuture(restricted, 120_000)
                    [DependencyVersion("example.versionchecker", "fixture", "1.0")])
            }
            val childFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(childPom)!!
            val childProject = MavenProject(childFile).apply { updateMavenId(MavenId("example.versionchecker", "child", "1")) }
            val childCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, childProject, UpdateMode.PATCH) }
            })
            assertEquals("1.1.1", PlatformTestUtil.waitForFuture(childCheck, 120_000)
                [DependencyVersion("example.versionchecker", "fixture", "1.1")])

            val beforeSettingsChange = metadataRequests.get()
            expectedAuth.set("Basic " + Base64.getEncoder().encodeToString("fixture:changed".toByteArray()))
            Files.writeString(settings, Files.readString(settings).replace("<password>password</password>", "<password>changed</password>"))
            val refreshedSettings = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, mavenProject) }
            })
            assertEquals("2.0", PlatformTestUtil.waitForFuture(refreshedSettings, 120_000)
                [DependencyVersion("example.versionchecker", "fixture", "1.0")])
            assertTrue("Each check must pick up externally edited settings credentials", metadataRequests.get() > beforeSettingsChange)

            val beforePublication = metadataRequests.get()
            publishedVersion.set("2.1")
            val afterPublication = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, mavenProject) }
            })
            assertEquals("A newly published release must bypass Maven's daily metadata cache", "2.1",
                PlatformTestUtil.waitForFuture(afterPublication, 120_000)
                    [DependencyVersion("example.versionchecker", "fixture", "1.0")])
            assertTrue(metadataRequests.get() > beforePublication)
            publishedVersion.set("2.0")

            manager.projectsTree.putVirtualFileToProjectMapping(mavenProject, mavenProject.mavenId)
            manager.projectsTree.putVirtualFileToProjectMapping(childProject, childProject.mavenId)
            val bulkCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    withBackgroundProgress(project, "Checking all modules", cancellable = true) {
                        project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH)
                    }
                }
            })
            val bulkPlan = PlatformTestUtil.waitForFuture(bulkCheck, 120_000)
            assertEquals("Global action must collect dependency and plugin updates from parent and child", 4, bulkPlan.changes.size)
            assertEquals(setOf("1.0.1", "1.1.1"), bulkPlan.changes.map { it.latest }.toSet())
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.0.1", UpdateMode.MINOR to "1.1.1", UpdateMode.MAJOR to "2.0")) {
                val pluginCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking {
                        withBackgroundProgress(project, "Checking plugin versions", cancellable = true) {
                            project.service<BulkUpdateService>().createPlan(mode, UpdateScope.WHOLE_PROJECT)
                        }
                    }
                })
                val pluginPlan = PlatformTestUtil.waitForFuture(pluginCheck, 120_000)
                assertEquals("Combined updates must include root and child", 4, pluginPlan.changes.size)
                assertEquals(expected, pluginPlan.changes.single { "fixture-plugin.version" in it.location }.latest)
                val currentCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking {
                        project.service<BulkUpdateService>().createPlan(mode, UpdateScope.CURRENT_FILE, childFile)
                    }
                })
                val currentPlan = PlatformTestUtil.waitForFuture(currentCheck, 120_000)
                assertEquals(2, currentPlan.changes.size)
                assertTrue(currentPlan.changes.all { it.location.startsWith(childFile.path) })
                assertTrue(currentPlan.changes.all { it.latest == if (mode == UpdateMode.MAJOR) "2.0" else "1.1.1" })
            }
            val currentDependencyCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, childFile)
                }
            })
            val currentDependencyPlan = PlatformTestUtil.waitForFuture(currentDependencyCheck, 120_000)
            assertEquals(2, currentDependencyPlan.changes.size)
            assertTrue(currentDependencyPlan.changes.all { it.location.startsWith(childFile.path) })
            val rootCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH, UpdateScope.CURRENT_FILE, virtualFile) }
            })
            val rootPlan = PlatformTestUtil.waitForFuture(rootCheck, 120_000)
            assertEquals(2, rootPlan.changes.size)
            val childDocument = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(childFile)!!
            val childText = childDocument.text
            WriteCommandAction.runWriteCommandAction(project) { childDocument.setText(childText + "\n") }
            assertFalse("Changes in an unselected module must invalidate the current POM preview", rootPlan.apply(project))
            assertTrue(rootPlan.changes.all { it.isValid() })
            WriteCommandAction.runWriteCommandAction(project) { childDocument.setText(childText) }
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            val service = project.service<MavenVersionCheckService>()
            PlatformTestUtil.waitWithEventsDispatching("Background Maven version check", {
                service.updates(mavenProject)[DependencyVersion("example.versionchecker", "fixture", "1.0")] == "2.0"
            }, 120_000)
            val childCoordinate = DependencyVersion("example.versionchecker", "fixture", "1.1")
            PlatformTestUtil.waitWithEventsDispatching("Child background check", {
                service.updates(childProject)[childCoordinate] == "2.0"
            }, 120_000)
            project.service<VersionCheckService>().refresh("maven", virtualFile)
            assertEquals("Current POM refresh must preserve the other module's cache", "2.0", service.updates(childProject)[childCoordinate])
            PlatformTestUtil.waitWithEventsDispatching("Current POM refresh", {
                service.updates(mavenProject)[DependencyVersion("example.versionchecker", "fixture", "1.0")] == "2.0"
            }, 120_000)
            val file = PsiManager.getInstance(project).findFile(virtualFile)!!
            val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val visitor = NewerMavenDependencyInspection().buildVisitor(holder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(visitor) }
            val problem = holder.results.single { !it.descriptionTemplate.contains("Maven plugin") }
            val pluginProblem = holder.results.single { it.descriptionTemplate.contains("Maven plugin") }
            assertEquals(ProblemHighlightType.WARNING, pluginProblem.highlightType)
            assertTrue(pluginProblem.descriptionTemplate.contains("1.0 → 2.0"))
            assertEquals(ProblemHighlightType.WARNING, problem.highlightType)
            assertTrue(problem.descriptionTemplate.contains("1.0 → 2.0"))
            assertEquals("Update fixture.version to 2.0", problem.fixes!!.single().name)

            myFixture.configureFromExistingVirtualFile(virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("Editor-open version check", {
                service.updates(mavenProject)[DependencyVersion("example.versionchecker", "fixture", "1.0")] == "2.0"
            }, 120_000)
            val options = project.service<VersionCheckerSettings>().state
            options.majorSeverity = VersionSeverity.ERROR
            val errorHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val errorVisitor = NewerMavenDependencyInspection().buildVisitor(errorHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(errorVisitor) }
            assertEquals(2, errorHolder.results.size)
            assertTrue(errorHolder.results.all { it.highlightType == ProblemHighlightType.GENERIC_ERROR })
            options.majorSeverity = VersionSeverity.DISABLED
            val disabledHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val disabledVisitor = NewerMavenDependencyInspection().buildVisitor(disabledHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(disabledVisitor) }
            assertTrue(disabledHolder.results.isEmpty())
            options.majorSeverity = VersionSeverity.WARNING
            val standard = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val standardVisitor = NewerMavenDependencyInspection().buildVisitor(standard, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(standardVisitor) }
            assertEquals(2, standard.results.size)
            assertTrue(standard.results.all { it.highlightType == ProblemHighlightType.WARNING })

            // Use the refreshed descriptor after editor setup has changed the POM's modification stamp.
            val currentProblem = standard.results.single { !it.descriptionTemplate.contains("Maven plugin") }
            WriteCommandAction.runWriteCommandAction(project) {
                currentProblem.fixes!!.single().applyFix(project, currentProblem)
            }
            val tags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            assertEquals("2.0", tags.single { it.localName == "fixture.version" }.value.trimmedText)
            assertTrue(tags.any { it.localName == "version" && it.value.trimmedText == "\${fixture.version}" })
            val after = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val afterVisitor = NewerMavenDependencyInspection().buildVisitor(after, true)
            tags.forEach { it.accept(afterVisitor) }
            assertTrue("Edited versions must not get stale errors", after.results.none { !it.descriptionTemplate.contains("Maven plugin") })
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(oldSettings)
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
