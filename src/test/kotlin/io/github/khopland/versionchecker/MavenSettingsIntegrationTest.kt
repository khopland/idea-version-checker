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
import kotlinx.coroutines.*
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.model.MavenParent
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
    override fun setUp() {
        super.setUp()
        val configuredHome = System.getProperty("versionchecker.mavenHome")?.takeIf { it.isNotBlank() } ?: return
        val mavenHome = Path.of(configuredHome)
        VfsRootAccess.allowRootAccess(testRootDisposable, mavenHome.toString(), mavenHome.toRealPath().toString())
        MavenProjectsManager.getInstance(project).generalSettings.setMavenHome(mavenHome.toString())
    }

    private val checksumAlgorithms = mapOf(".sha512" to "SHA-512", ".sha256" to "SHA-256", ".sha1" to "SHA-1", ".md5" to "MD5")

    private fun repositoryPath(exchange: com.sun.net.httpserver.HttpExchange): String =
        exchange.requestURI.path.removeSuffix(checksumAlgorithms.keys.firstOrNull { exchange.requestURI.path.endsWith(it) }.orEmpty())

    private fun sendRepositoryResponse(exchange: com.sun.net.httpserver.HttpExchange, body: ByteArray) {
        val algorithm = checksumAlgorithms.entries.firstOrNull { exchange.requestURI.path.endsWith(it.key) }?.value
        val bytes = if (algorithm == null) body else
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(body)).toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    fun testGoalProcessUsesImportVmOptionsAndExplicitProfilesWithoutSavingUnrelatedDocuments() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-process-runtime-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        val oldOptions = manager.importingSettings.vmOptionsForImporter
        val oldProfiles = manager.explicitProfiles
        try {
            fun remote(name: String, version: String): Path {
                val root = directory.resolve(name)
                val metadata = Files.createDirectories(root.resolve("example/process/library")).resolve("maven-metadata.xml")
                val bytes = "<metadata><versioning><versions><version>$version</version></versions></versioning></metadata>".toByteArray()
                Files.write(metadata, bytes)
                checksumAlgorithms.forEach { (suffix, algorithm) ->
                    Files.writeString(metadata.resolveSibling("maven-metadata.xml$suffix"),
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(bytes)))
                }
                return root
            }
            val selected = remote("selected repository", "2.0")
            val excluded = remote("excluded repository", "9.0")
            manager.importingSettings.vmOptionsForImporter = "-Dversionchecker.fixture.repository=${selected.toUri()}"
            manager.setExplicitProfiles(MavenExplicitProfiles(listOf("selected"), listOf("excluded")))
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>example.process</groupId><artifactId>app</artifactId><version>1</version>
                <repositories><repository><id>central</id><url>https://repo.maven.apache.org/maven2</url>
                <releases><enabled>false</enabled></releases></repository></repositories>
                <profiles><profile><id>selected</id><repositories><repository><id>selected</id>
                <url>${'$'}{versionchecker.fixture.repository}</url></repository></repositories></profile>
                <profile><id>excluded</id><activation><property><name>versionchecker.fixture.repository</name></property></activation>
                <repositories><repository><id>excluded</id><url>${excluded.toUri()}</url></repository></repositories></profile></profiles>
                </project>""".trimIndent())
            val imported = MavenProject(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!).apply {
                updateMavenId(MavenId("example.process", "app", "1"))
            }
            val unrelated = myFixture.addFileToProject("unrelated-process-document.txt", "original")
            val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            val document = documents.getDocument(unrelated.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("unsaved unrelated edit") }
            val repository = directory.resolve("local repository")
            val key = MavenMetadataKey("example.process", "library")
            val context = MavenResolutionContext("", repository,
                mapOf("maven.repo.local" to directory.resolve("overridden repository").toString()), manager.explicitProfiles, true)
            val result = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenNativeMetadata.load(manager, imported, context, listOf(key), 1).getValue(key).getOrThrow() }
            }), 120_000)
            assertEquals(listOf("2.0"), result.versions)
            assertTrue("Goal execution must not save unrelated edits", documents.isDocumentUnsaved(document))
            assertEquals("original", String(unrelated.virtualFile.contentsToByteArray()))
        } finally {
            manager.importingSettings.vmOptionsForImporter = oldOptions
            manager.setExplicitProfiles(oldProfiles)
            manager.embeddersManager.reset()
            directory.toFile().deleteRecursively()
        }
    }

    fun testGoalProcessRejectsMavenConfigDisabledInIde() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-ignored-maven-config-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        val original = manager.generalSettings.isUseMavenConfig
        try {
            Files.writeString(Files.createDirectories(directory.resolve(".mvn")).resolve("maven.config"), "--offline")
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """<project><modelVersion>4.0.0</modelVersion><groupId>example.process</groupId>
                <artifactId>ignored-config</artifactId><version>1</version></project>""")
            val imported = MavenProject(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!).apply {
                updateMavenId(MavenId("example.process", "ignored-config", "1"))
            }
            manager.generalSettings.isUseMavenConfig = false
            val result = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    try {
                        MavenGoalProcess.run(manager, imported, MavenExplicitProfiles.NONE, emptyMap(), "--version", directory.resolve("local"))
                        null
                    } catch (failure: MavenGoalConfigurationFailure) { failure }
                }
            }), 120_000)
            assertNotNull("An ignored Maven config must not become active in the goal process", result)
            assertTrue(result!!.recoveryMessage, result.recoveryMessage.contains("cannot ignore"))
        } finally {
            manager.generalSettings.isUseMavenConfig = original
            manager.embeddersManager.reset()
            directory.toFile().deleteRecursively()
        }
    }

    fun testSuccessfulCliShortcutCannotBeReportedAsNoUpdates() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-informational-goal-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        try {
            Files.writeString(Files.createDirectories(directory.resolve(".mvn")).resolve("maven.config"), "--version")
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """<project><modelVersion>4.0.0</modelVersion><groupId>example.process</groupId>
                <artifactId>informational</artifactId><version>1</version></project>""")
            val imported = MavenProject(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!).apply {
                updateMavenId(MavenId("example.process", "informational", "1"))
            }
            val result = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    try {
                        MavenVersionLookup.checkAll(manager, imported, UpdateMode.MAJOR, setOf(MavenArtifactKind.DEPENDENCY),
                            listOf(DependencyVersion("example.process", "library", "1.0")))
                        null
                    } catch (failure: MavenGoalConfigurationFailure) { failure }
                }
            }), 120_000)
            assertNotNull("An informational exit must not be reported as no updates", result)
        } finally {
            manager.embeddersManager.reset()
            directory.toFile().deleteRecursively()
        }
    }

    fun testMetadataResponsesKeepTheSelectedModulesRepositories() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-module-repositories-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        val oldRepository = manager.generalSettings.localRepository
        try {
            val repository = directory.resolve("local")
            manager.generalSettings.setLocalRepository(repository.toString())
            fun remote(name: String, version: String): String {
                val remote = directory.resolve(name)
                val metadata = Files.createDirectories(remote.resolve("example/scoped/library")).resolve("maven-metadata.xml")
                val bytes = "<metadata><versioning><versions><version>$version</version></versions></versioning></metadata>".toByteArray()
                Files.write(metadata, bytes)
                checksumAlgorithms.forEach { (suffix, algorithm) ->
                    Files.writeString(metadata.resolveSibling("maven-metadata.xml$suffix"),
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(bytes)))
                }
                return """<repositories><repository><id>central</id><url>https://repo.maven.apache.org/maven2</url>
                    <releases><enabled>false</enabled></releases></repository>
                    <repository><id>$name</id><url>${remote.toUri()}</url></repository></repositories>"""
            }
            val rootRepository = remote("root-repository", "2.0")
            val childRepository = remote("child-repository", "9.0")
            fun imported(path: Path, name: String, body: String): MavenProject {
                Files.createDirectories(path.parent)
                Files.writeString(path, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>example.scoped</groupId><artifactId>$name</artifactId><version>1</version>$body</project>""")
                return MavenProject(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!).apply {
                    updateMavenId(MavenId("example.scoped", name, "1"))
                }
            }
            val root = imported(directory.resolve("pom.xml"), "root",
                "<packaging>pom</packaging><modules><module>child</module></modules>$rootRepository")
            val child = imported(directory.resolve("child/pom.xml"), "child", childRepository)
            fun versions(imported: MavenProject): List<String> {
                val context = MavenResolutionContext("", repository, emptyMap(), MavenExplicitProfiles.NONE, true)
                val key = MavenMetadataKey("example.scoped", "library")
                return PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { MavenNativeMetadata.load(manager, imported, context, listOf(key), 1).getValue(key).getOrThrow().versions }
                }), 120_000)
            }
            assertEquals("A child executed by Maven 4 must not overwrite its parent's response", listOf("2.0"), versions(root))
            assertEquals("The child must still use its own repository when explicitly selected", listOf("9.0"), versions(child))
        } finally {
            manager.embeddersManager.reset()
            manager.generalSettings.setLocalRepository(oldRepository)
            directory.toFile().deleteRecursively()
        }
    }

    fun testLargeManagementChildAndFullAudit() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-large-management-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val requests = CopyOnWriteArrayList<String>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val body = when {
                    path.endsWith("/maven-metadata.xml") -> {
                        requests += path
                        Thread.sleep(2)
                        val artifact = path.substringBeforeLast('/').substringAfterLast('/')
                        """<metadata><artifactId>$artifact</artifactId><versioning><versions>
                            <version>1.0</version><version>1.0.1</version><version>1.1</version><version>2.0</version>
                            <version>3.0-RC1</version><version>4.0-SNAPSHOT</version></versions></versioning></metadata>"""
                    }
                    path.endsWith("/spring-boot-starter-parent/1.0/spring-boot-starter-parent-1.0.pom") ->
                        """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                            <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                            <version>1.0</version><packaging>pom</packaging></project>"""
                    else -> null
                }
                if (body == null) exchange.sendResponseHeaders(404, -1)
                else {
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                }
            } finally { exchange.close() }
        }
        server.start()
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        val oldSettings = manager.generalSettings.userSettingsFile
        val options = project.service<VersionCheckerSettings>()
        val oldOptions = options.state.copy()
        options.loadState(VersionCheckerSettings.Options())
        try {
            val fixtureRepository = directory.resolve("repository")
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """<settings><localRepository>$fixtureRepository</localRepository><profiles><profile><id>large-management</id><repositories>
                <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url><releases><enabled>false</enabled></releases><snapshots><enabled>false</enabled></snapshots></repository>
                <repository><id>large-management</id><url>http://127.0.0.1:${server.address.port}/</url><releases><updatePolicy>daily</updatePolicy></releases></repository>
                </repositories></profile></profiles><activeProfiles><activeProfile>large-management</activeProfile></activeProfiles></settings>""")
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val managed = (0 until 1000).map { MavenArtifactInfo("example.management", "artifact-$it", "1.0", "jar", null) }
            fun imported(name: String, body: String): MavenProject {
                val pom = Files.createDirectories(directory.resolve(name)).resolve("pom.xml")
                Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>example.management</groupId><artifactId>$name</artifactId><version>1</version>$body</project>""")
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
                return MavenProject(file).apply {
                    updateState(MavenModel().apply { mavenId = MavenId("example.management", name, "1") },
                        managed, "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(),
                        fixtureRepository, false)
                    manager.projectsTree.putVirtualFileToProjectMapping(this, mavenId)
                    manager.projectsTree.setIgnoredState(listOf(this), false)
                }
            }
            val platform = imported("platform", """<parent><groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId><version>1.0</version><relativePath/></parent><packaging>pom</packaging>
                <dependencyManagement><dependencies>${managed.joinToString("") {
                    "<dependency><groupId>${it.groupId}</groupId><artifactId>${it.artifactId}</artifactId><version>${it.version}</version></dependency>"
                }}</dependencies></dependencyManagement>""")
            val child = imported("child", """<parent><groupId>example.management</groupId><artifactId>platform</artifactId>
                <version>1</version><relativePath>../platform/pom.xml</relativePath></parent><dependencies>${managed.take(25).joinToString("") {
                    "<dependency><groupId>${it.groupId}</groupId><artifactId>${it.artifactId}</artifactId></dependency>"
                }}</dependencies>""")
            fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 180_000)
            val adapter = MavenBuildSystemAdapter()
            val childSnapshot = adapter.snapshot(project, child.file)!!
            assertEquals(25, childSnapshot.declarations.size)
            val childReport = background { adapter.check(project, childSnapshot, UpdateMode.MAJOR) }
            assertTrue(childReport.failure.orEmpty(), childReport.successful)
            assertEquals(25, childReport.candidates.size)
            assertEquals("A child must not query the parent's 1,000 managed entries", 25, requests.size)

            adapter.invalidateMetadata(project)
            val full = background { adapter.discover(project, BuildSelection(UpdateScope.CURRENT_FILE, platform.path)) }.single()
            assertEquals(1001, full.declarations.size)
            assertNull(full.coverageDescription)
            val fullBefore = requests.size
            val fullStarted = System.nanoTime()
            val service = project.service<VersionCheckService>()
            val trace = CheckPerformance.start(CheckPerformance.Stage.INTERACTION_STARTED)
            val fullReport = background { CheckPerformance.traced(trace) { service.checkNow(adapter, full, UpdateMode.MAJOR) } }
            assertTrue(fullReport.successful)
            assertEquals(1001, fullReport.candidates.size)
            assertTrue(fullReport.candidates.all { it.version == "2.0" })
            assertEquals(1001, requests.size - fullBefore)
            println("version-check benchmark=maven-management path=full supported=1001 checked=1001 httpRequests=1001 elapsedNs=${System.nanoTime() - fullStarted} interaction=${trace?.id} originNs=${trace?.started}")
            val auditedEditor = adapter.snapshot(project, platform.file)!!
            assertNull(auditedEditor.coverageDescription)
            assertEquals(CheckPhase.CHECKED, service.status(adapter, auditedEditor).phase)
            val warmed = requests.size
            val patch = background { adapter.check(project, full, UpdateMode.PATCH) }
            assertTrue(patch.candidates.all { it.version == "1.0.1" })
            assertEquals("Warm modes reuse the full management history", warmed, requests.size)
        } finally {
            project.service<MavenMetadataService>().invalidate()
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(oldSettings)
            options.loadState(oldOptions)
            server.stop(0); executor.shutdownNow(); directory.toFile().deleteRecursively()
        }
    }

    fun testBatchedMetadataSharesOneHundredArtifactsAcrossOneHundredModulesAndModes() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-shared-metadata-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val requests = CopyOnWriteArrayList<String>()
        val published = AtomicReference("2.0")
        val failedArtifact = AtomicReference<String?>(null)
        val metadataOverrides = AtomicReference<Map<String, String>>(emptyMap())
        val externalDtdRequests = AtomicInteger()
        val metadataDelay = java.util.concurrent.atomic.AtomicLong(10)
        val delayedResponses = AtomicInteger()
        val peakDelayedResponses = AtomicInteger()
        val auth = AtomicReference("Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray()))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                if (path.endsWith(".dtd")) externalDtdRequests.incrementAndGet()
                if (exchange.requestHeaders.getFirst("Authorization") != auth.get()) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (path.endsWith("/maven-metadata.xml")) {
                    requests += path
                    val artifact = path.substringBeforeLast('/').substringAfterLast('/')
                    if (artifact == failedArtifact.get()) exchange.sendResponseHeaders(500, -1)
                    else {
                        val active = delayedResponses.incrementAndGet()
                        peakDelayedResponses.accumulateAndGet(active) { previous, current -> maxOf(previous, current) }
                        try { Thread.sleep(metadataDelay.get()) } finally { delayedResponses.decrementAndGet() }
                        val body = (metadataOverrides.get()[artifact] ?: """<metadata><groupId>example.shared</groupId><artifactId>$artifact</artifactId><versioning><versions>
                            <version>1.0</version><version>1.0.1</version><version>1.1</version><version>1.1.1</version>
                            <version>${published.get()}</version><version>4.0-RC1</version><version>5.0-SNAPSHOT</version>
                            </versions></versioning></metadata>""").toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
                    }
                } else exchange.sendResponseHeaders(404, -1)
            } finally { exchange.close() }
        }
        server.start()
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        val oldSettings = manager.generalSettings.userSettingsFile
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        try {
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """<settings><servers><server><id>shared-mirror</id><username>fixture</username><password>password</password></server></servers>
                <mirrors><mirror><id>shared-mirror</id><mirrorOf>shared-source</mirrorOf><url>http://127.0.0.1:${server.address.port}/</url></mirror></mirrors>
                <profiles><profile><id>shared</id><repositories>
                  <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url><releases><enabled>false</enabled></releases><snapshots><enabled>false</enabled></snapshots></repository>
                  <repository><id>shared-source</id><url>http://127.0.0.1:1/unmirrored</url><releases><updatePolicy>daily</updatePolicy></releases></repository>
                </repositories></profile></profiles><activeProfiles><activeProfile>shared</activeProfile></activeProfiles></settings>""")
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val imported = (0 until 100).map { module ->
                val pom = Files.createDirectories(directory.resolve("module-$module")).resolve("pom.xml")
                val baseline = if (module % 2 == 0) "1.0" else "1.1"
                Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>example.shared</groupId><artifactId>module-$module</artifactId><version>1</version>
                    <dependencies>${(0 until 100).joinToString("") { artifact ->
                        "<dependency><groupId>example.shared</groupId><artifactId>artifact-$artifact</artifactId><version>$baseline</version></dependency>"
                    }}</dependencies></project>""")
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
                MavenProject(virtual).apply {
                    updateState(MavenModel().apply { mavenId = MavenId("example.shared", "module-$module", "1") },
                        emptyList(), "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(),
                        Path.of(System.getProperty("user.home"), ".m2/repository"), false)
                    manager.projectsTree.putVirtualFileToProjectMapping(this, mavenId)
                    manager.projectsTree.setIgnoredState(listOf(this), false)
                }
            }
            val adapter = MavenBuildSystemAdapter()
            fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 180_000)
            val snapshots = background { adapter.discover(project, BuildSelection(UpdateScope.WHOLE_PROJECT)) }
            assertEquals(100, snapshots.size)
            assertEquals(10_000, snapshots.sumOf { it.declarations.size })
            val baselineBefore = requests.size
            val baseline = background { MavenVersionLookup.check(manager, imported.first()) }
            assertEquals(100, requests.size - baselineBefore)
            adapter.invalidateMetadata(project)
            val before = requests.size
            val started = System.nanoTime()
            var firstDeadline: Long? = null
            var firstBatchNanos: Long? = null
            val queue = CheckQueue { it == snapshots[1].sourceFile }
            val events = mutableListOf<String>()
            for ((index, snapshot) in snapshots.withIndex()) {
                val report = background {
                    if (index != 0) adapter.check(project, snapshot, UpdateMode.MAJOR) else coroutineScope {
                        var selected: Deferred<UpdateReport>? = null
                        val report = queue.withSlot(snapshot.sourceFile) {
                            adapter.checkIncrementally(project, snapshot, UpdateMode.MAJOR) { delta ->
                                if (firstBatchNanos == null) {
                                    firstBatchNanos = System.nanoTime() - started
                                    assertEquals("An early batch publishes before the full history scan", 16, delta.report.candidates.size)
                                    // The next batch's goal may already be running.
                                    assertTrue(requests.size - before in 16 until 10_000)
                                    selected = async(start = CoroutineStart.UNDISPATCHED) {
                                        queue.withSlot(snapshots[1].sourceFile) {
                                            adapter.check(project, snapshots[1], UpdateMode.MAJOR).also { events += "selected" }
                                        }
                                    }
                                } else events += "background remainder"
                            }
                        }
                        val selectedReport = selected!!.await()
                        assertTrue(selectedReport.failure.orEmpty(), selectedReport.successful)
                        assertEquals(100, selectedReport.candidates.size)
                        assertEquals(listOf("selected", "background remainder"), events)
                        report
                    }
                }
                assertTrue(report.failure.orEmpty(), report.successful)
                assertEquals(100, report.candidates.size)
                assertTrue(report.candidates.all { it.version == "2.0" })
                if (firstDeadline == null) firstDeadline = report.validUntilNanos
                assertEquals("Sharing must not extend freshness", firstDeadline, report.validUntilNanos)
                if (snapshot.sourceFile == imported.first().path) assertEquals(baseline, report.mavenUpdates())
            }
            assertEquals("10,000 declarations must cause only 100 fresh metadata requests", 100, requests.size - before)
            println("version-check benchmark=maven-shared modules=100 declarations=10000 uniqueArtifacts=100 httpRequests=${requests.size - before} firstBatchNs=$firstBatchNanos elapsedNs=${System.nanoTime() - started}")
            for (mode in listOf(UpdateMode.PATCH, UpdateMode.MINOR)) {
                val report = background { adapter.check(project, snapshots.first(), mode) }
                assertTrue(report.successful)
                val expected = if (mode == UpdateMode.PATCH) snapshots.first().declarations.first().baseline + ".1" else "1.1.1"
                assertTrue(report.candidates.all { it.version == expected })
                assertEquals(firstDeadline, report.validUntilNanos)
            }
            assertEquals("Mode changes must not repeat native metadata requests", 100, requests.size - before)
            published.set("3.0"); adapter.invalidateMetadata(project)
            val refreshed = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertTrue(refreshed.candidates.all { it.version == "3.0" })
            assertEquals(200, requests.size - before)
            failedArtifact.set("artifact-0"); adapter.invalidateMetadata(project)
            val partial = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertFalse(partial.successful)
            assertEquals(99, partial.candidates.size)
            assertEquals(CoverageSummary(checked = 99, failed = 1), partial.coverageSummary(snapshots.first()))
            assertTrue(partial.failure!!.contains("artifact-0"))
            failedArtifact.set(null)
            val retryBefore = requests.size
            val retry = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertTrue(retry.successful)
            assertEquals(100, retry.candidates.size)
            assertEquals("Retry only the failed artifact", 1, requests.size - retryBefore)
            metadataOverrides.set(mapOf(
                "artifact-0" to "<metadata><versioning><versions><version>99.0</version>",
                "artifact-1" to """<!DOCTYPE metadata SYSTEM "http://127.0.0.1:${server.address.port}/forbidden.dtd">
                    <metadata><versioning><versions><version>99.0</version></versions></versioning></metadata>"""
            ))
            adapter.invalidateMetadata(project)
            val malformed = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertFalse(malformed.successful)
            assertEquals("A malformed document must not poison the reused parser", 98, malformed.candidates.size)
            assertEquals(CoverageSummary(checked = 98, failed = 2), malformed.coverageSummary(snapshots.first()))
            assertTrue(malformed.candidates.all { it.version == "3.0" })
            assertTrue(malformed.failure!!.contains("artifact-0"))
            assertTrue(malformed.failure.contains("artifact-1"))
            assertEquals("External DTDs remain blocked", 0, externalDtdRequests.get())
            metadataOverrides.set(emptyMap())
            val xmlRetryBefore = requests.size
            val xmlRetry = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertTrue(xmlRetry.failure.orEmpty(), xmlRetry.successful)
            assertEquals(100, xmlRetry.candidates.size)
            assertEquals("Retry only the malformed metadata", 2, requests.size - xmlRetryBefore)
            // Same repository ID with a changed mirror URL or credential is a new context,
            // even without an explicit cache invalidation.
            published.set("3.1")
            Files.writeString(settings, Files.readString(settings).replace("${server.address.port}/</url>", "${server.address.port}/second/</url>"))
            val urlBefore = requests.size
            val changedUrl = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertTrue(changedUrl.successful)
            assertTrue(changedUrl.candidates.all { it.version == "3.1" })
            assertEquals(100, requests.size - urlBefore)
            assertTrue(requests.takeLast(100).all { it.startsWith("/second/") })
            published.set("3.2")
            auth.set("Basic " + Base64.getEncoder().encodeToString("fixture:changed".toByteArray()))
            Files.writeString(settings, Files.readString(settings).replace("<password>password</password>", "<password>changed</password>"))
            val authBefore = requests.size
            val changedAuth = background { adapter.check(project, snapshots.first(), UpdateMode.MAJOR) }
            assertTrue(changedAuth.failure.orEmpty(), changedAuth.successful)
            assertTrue(changedAuth.candidates.all { it.version == "3.2" })
            assertEquals(100, requests.size - authBefore)
            run {
                // Two batches retain early-publication and concurrency coverage without
                // repeating the 100-coordinate latency benchmark on every CI run.
                val benchmark = java.lang.Boolean.getBoolean("versionchecker.mavenLatencyBenchmark")
                val latencySnapshot = snapshots.first().let {
                    if (benchmark) it else it.copy(declarations = it.declarations.take(32))
                }
                val coordinates = latencySnapshot.declarations.size
                val delays = if (benchmark) listOf(50L, 250L) else listOf(50L)
                // Four resolver threads per goal, with the next batch's goal already running.
                val workers = 4
                for (delay in delays) {
                    metadataDelay.set(delay)
                    peakDelayedResponses.set(0)
                    adapter.invalidateMetadata(project)
                    val latencyBefore = requests.size
                    val latencyStarted = System.nanoTime()
                    var firstUseful: Long? = null
                    val report = background {
                        adapter.checkIncrementally(project, latencySnapshot, UpdateMode.MAJOR) { delta ->
                            if (firstUseful == null) {
                                firstUseful = System.nanoTime() - latencyStarted
                                assertEquals(16, delta.report.candidates.size)
                                assertTrue(requests.size - latencyBefore >= 16)
                            }
                        }
                    }
                    assertTrue(report.failure.orEmpty(), report.successful)
                    assertEquals(coordinates, report.candidates.size)
                    assertEquals(coordinates, requests.size - latencyBefore)
                    assertTrue("Native metadata requests overlap", peakDelayedResponses.get() > 1)
                    assertTrue("Native metadata concurrency stays bounded", peakDelayedResponses.get() <= 2 * workers)
                    println("version-check benchmark=maven-latency delayMs=$delay workers=$workers coordinates=$coordinates httpRequests=${requests.size - latencyBefore} peakDelayedResponses=${peakDelayedResponses.get()} firstBatchNs=$firstUseful elapsedNs=${System.nanoTime() - latencyStarted}")
                }
            }
        } finally {
            project.service<MavenMetadataService>().invalidate()
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(oldSettings)
            server.stop(0); executor.shutdownNow(); directory.toFile().deleteRecursively()
        }
    }

    fun testActiveProfilePropertyEditsChangeTheNativeEffectiveDependencyVersion() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-maven-property-owner-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
        try {
            for ((index, defaultVersion) in listOf("1.0", "0.5").withIndex()) {
                val pom = Files.createDirectories(directory.resolve("case-$index")).resolve("pom.xml")
                Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>example.owner</groupId><artifactId>case-$index</artifactId><version>1</version>
                    <properties><library.version>$defaultVersion</library.version></properties>
                    <dependencies><dependency><groupId>example.owner</groupId><artifactId>library</artifactId><version>${'$'}{library.version}</version></dependency></dependencies>
                    <profiles><profile><id>on</id><activation><activeByDefault>true</activeByDefault></activation>
                      <properties><library.version>1.0</library.version></properties></profile></profiles></project>""")
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
                val file = PsiManager.getInstance(project).findFile(virtual) as com.intellij.psi.xml.XmlFile
                val imported = MavenProject(virtual).apply {
                    updateState(MavenModel().apply {
                        mavenId = MavenId("example.owner", "case-$index", "1")
                        setProperties(java.util.Properties().apply { setProperty("library.version", "1.0") })
                    }, emptyList(), "21", emptyList(), MavenExplicitProfiles(listOf("on")), emptySet(), emptyMap(),
                        directory.resolve("repository"), false)
                }
                manager.projectsTree.putVirtualFileToProjectMapping(imported, imported.mavenId)
                manager.projectsTree.setIgnoredState(listOf(imported), false)
                val analysis = MavenDependencyAnalysis(org.jetbrains.idea.maven.dom.MavenDomUtil.getMavenDomProjectModel(file)!!,
                    imported, mapOf(DependencyVersion("example.owner", "library", "1.0") to "2.0"))
                val dependency = file.rootTag!!.findFirstSubTag("dependencies")!!.subTags.single()
                val problem = analysis.problem(dependency)!!
                if (index == 0) {
                    val fix = analysis.quickFixes(dependency, problem).single()
                    val descriptor = InspectionManager.getInstance(project).createProblemDescriptor(
                        problem.anchor, "Newer version", fix, ProblemHighlightType.WARNING, true)
                    fix.applyFix(project, descriptor)
                } else {
                    val plan = MavenBulkUpdatePlan.create(mapOf(file to analysis))
                    assertEquals(1, plan.changes.size)
                    assertTrue(plan.apply(project))
                }
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
                val effective = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                    runBlocking { withMavenCheckSession(manager, imported) {
                        it.evaluateEffectivePom(pom.toFile(), emptySet(), emptySet())
                    } }
                }), 120_000) ?: error("No native effective POM")
                val root = com.intellij.openapi.util.JDOMUtil.load(effective)
                assertEquals("2.0", root.getChild("dependencies", root.namespace).getChild("dependency", root.namespace)
                    .getChildTextTrim("version", root.namespace))
                assertEquals(defaultVersion, file.rootTag!!.findFirstSubTag("properties")!!.subTags.single().value.trimmedText)
            }
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            directory.toFile().deleteRecursively()
        }
    }

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

    fun testUnsortableVersionListsStillReportEveryUpdate() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-unsortable-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val published = AtomicReference("2.5.17")
        val authorization = "Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray())
        // With a locally installed 0-SNAPSHOT, Maven Resolver's sort throws on these branch snapshots.
        val branches = listOf("a", "b", "c", "d", "e").map { "bugfix_$it-SNAPSHOT" } + "feature_f-SNAPSHOT"
        val releases = (0..41).map { "2.4.$it" } + (0..17).map { "2.5.$it" }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != authorization) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (exchange.requestURI.path.endsWith("/maven-metadata.xml")) {
                    val artifact = exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/')
                    val versions = if (artifact == "ordinary") listOf("1.0", "1.1")
                        else branches + releases + listOf(published.get()).filter { it !in releases }
                    val body = """<metadata><groupId>example.unsortable</groupId><artifactId>$artifact</artifactId><versioning>
                        <versions>${versions.joinToString("") { "<version>$it</version>" }}</versions>
                        <lastUpdated>20261014000000</lastUpdated></versioning></metadata>""".toByteArray()
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
        val repository = Path.of(System.getProperty("user.home"), ".m2", "repository")
        val installed = repository.resolve("example/unsortable")
        try {
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                <localRepository>$repository</localRepository>
                <servers><server><id>unsortable-mirror</id><username>fixture</username><password>password</password></server></servers>
                <mirrors><mirror><id>unsortable-mirror</id><mirrorOf>unsortable-source</mirrorOf><url>http://127.0.0.1:${server.address.port}/</url></mirror></mirrors>
                <profiles><profile><id>unsortable-repositories</id><repositories>
                  <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url><releases><enabled>false</enabled></releases></repository>
                  <repository><id>unsortable-source</id><url>http://127.0.0.1:1/unmirrored</url><releases><updatePolicy>always</updatePolicy></releases></repository>
                </repositories></profile></profiles><activeProfiles><activeProfile>unsortable-repositories</activeProfile></activeProfiles></settings>""")
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val ordinary = DependencyVersion("example.unsortable", "ordinary", "1.0")
            val unsortable = (1..3).map { DependencyVersion("example.unsortable", "lib-$it", "2.5.10") }
            for (dependency in unsortable) {
                Files.writeString(Files.createDirectories(installed.resolve(dependency.artifactId)).resolve("maven-metadata-local.xml"),
                    "<metadata><versioning><versions><version>0-SNAPSHOT</version></versions></versioning></metadata>")
            }
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>example.unsortable</groupId><artifactId>app</artifactId><version>1</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>${(listOf(ordinary) + unsortable).joinToString("") {
                    "<dependency><groupId>${it.groupId}</groupId><artifactId>${it.artifactId}</artifactId><version>${it.version}</version></dependency>"
                }}</dependencies></dependencyManagement></project>""")
            val pomFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
            val app = MavenProject(pomFile).apply {
                updateState(MavenModel().apply { mavenId = MavenId("example.unsortable", "app", "1") }, emptyList(),
                    "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(), repository, false)
            }
            manager.projectsTree.putVirtualFileToProjectMapping(app, app.mavenId)
            fun check() = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, app) }
            }), 180_000)

            // IDEA reports the aborted goal as successful; an empty result would hide every update.
            assertEquals(mapOf(ordinary to "1.1") + unsortable.associateWith { "2.5.17" }, check())

            // Skipped artifacts must come from freshly read metadata, not files cached before the failure.
            published.set("2.6.0")
            assertEquals(mapOf(ordinary to "1.1") + unsortable.associateWith { "2.6.0" }, check())
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(previousSettings)
            server.stop(0)
            installed.toFile().deleteRecursively()
            directory.toFile().deleteRecursively()
        }
    }

    fun testMinimumReleaseAgeWithholdsRecentVersionsInNativeAndFallbackChecks() {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        val directory = Files.createTempDirectory("version-checker-release-age-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val now = java.time.Instant.now()
        // POMs keep their Last-Modified time in the local repository; a unique group keeps reruns independent.
        val group = "example.releaseage.run${System.currentTimeMillis()}"
        val published = mapOf("1.0" to now.minus(java.time.Duration.ofDays(40)), "1.1" to now.minus(java.time.Duration.ofHours(30)),
            "1.2" to now.minus(java.time.Duration.ofHours(3)), "2.0" to now.minus(java.time.Duration.ofHours(1)))
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        val pomRequests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = repositoryPath(exchange)
                val artifact = if (path.endsWith("/maven-metadata.xml")) path.substringBeforeLast('/').substringAfterLast('/')
                    else path.substringBeforeLast('/').substringBeforeLast('/').substringAfterLast('/')
                if (path.endsWith("/maven-metadata.xml")) {
                    // "settled" was last updated long ago, so none of its versions needs a release date.
                    val updated = if (artifact == "settled") now.minus(java.time.Duration.ofDays(30)) else now.minus(java.time.Duration.ofMinutes(30))
                    val body = """<metadata><groupId>$group</groupId><artifactId>$artifact</artifactId><versioning><versions>
                        ${published.keys.joinToString("") { "<version>$it</version>" }}</versions>
                        <lastUpdated>${stamp.format(updated)}</lastUpdated></versioning></metadata>""".toByteArray()
                    sendRepositoryResponse(exchange, body)
                } else if (path.endsWith(".pom")) {
                    pomRequests += path
                    val version = path.substringBeforeLast('/').substringAfterLast('/')
                    val plugin = artifact == "fixture-plugin"
                    val body = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>
                        <packaging>${if (plugin) "maven-plugin" else "jar"}</packaging>${if (plugin) "<prerequisites><maven>3.6.3</maven></prerequisites>" else ""}</project>""".toByteArray()
                    exchange.responseHeaders.add("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(published.getValue(version).atZone(java.time.ZoneOffset.UTC)))
                    sendRepositoryResponse(exchange, body)
                } else exchange.sendResponseHeaders(404, -1)
            } finally { exchange.close() }
        }
        server.start()
        val manager = MavenProjectsManager.getInstance(project)
        manager.initForTests()
        manager.projectsTree.ignoredFilesPaths = manager.projects.map { it.path }
        val previousSettings = manager.generalSettings.userSettingsFile
        val options = project.service<VersionCheckerSettings>()
        val previousOptions = options.state.copy()
        val repository = Path.of(System.getProperty("user.home"), ".m2", "repository")
        try {
            val settings = directory.resolve("settings.xml")
            Files.writeString(settings, """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                <localRepository>$repository</localRepository>
                <mirrors><mirror><id>age-mirror</id><mirrorOf>age-source</mirrorOf><url>http://127.0.0.1:${server.address.port}/</url></mirror></mirrors>
                <profiles><profile><id>age-repositories</id><repositories>
                  <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url><releases><enabled>false</enabled></releases></repository>
                  <repository><id>age-source</id><url>http://127.0.0.1:1/unmirrored</url><releases><updatePolicy>always</updatePolicy></releases></repository>
                </repositories><pluginRepositories><pluginRepository><id>age-source</id><url>http://127.0.0.1:1/unmirrored</url>
                  <releases><updatePolicy>always</updatePolicy></releases></pluginRepository></pluginRepositories></profile></profiles>
                <activeProfiles><activeProfile>age-repositories</activeProfile></activeProfiles></settings>""")
            manager.generalSettings.setUserSettingsFile(settings.toString())
            val pom = directory.resolve("pom.xml")
            Files.writeString(pom, """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId><artifactId>app</artifactId><version>1</version><packaging>pom</packaging>
                <dependencies><dependency><groupId>$group</groupId><artifactId>fixture</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>$group</groupId><artifactId>settled</artifactId><version>1.0</version></dependency></dependencies>
                <build><pluginManagement><plugins><plugin><groupId>$group</groupId><artifactId>fixture-plugin</artifactId><version>1.0</version></plugin>
                </plugins></pluginManagement></build></project>""")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
            val mavenProject = MavenProject(virtualFile).apply { updateMavenId(MavenId(group, "app", "1")) }
            manager.projectsTree.putVirtualFileToProjectMapping(mavenProject, mavenProject.mavenId)
            val native = BuildSystemAdapter.find("maven")!!
            fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 180_000)
            fun nativeVersions(): Map<String, String> {
                native.invalidateMetadata(project)
                val snapshot = native.snapshot(project, virtualFile)!!
                val report = background { native.check(project, snapshot, UpdateMode.MAJOR) }
                assertTrue(report.failure.orEmpty(), report.successful)
                return report.candidates.associate { it.declaration.coordinate().artifactId to it.version }
            }

            options.loadState(VersionCheckerSettings.Options())
            assertEquals(mapOf("fixture" to "2.0", "settled" to "2.0", "fixture-plugin" to "2.0"), nativeVersions())
            assertTrue("Without a minimum age, dependency release dates are not read", pomRequests.none { "/fixture/" in it })

            options.loadState(VersionCheckerSettings.Options(minimumReleaseAgeHours = 24))
            assertEquals("Versions younger than 24 hours are withheld", mapOf("fixture" to "1.1", "settled" to "2.0", "fixture-plugin" to "1.1"),
                nativeVersions())
            assertTrue("Old metadata settles every version without reading POMs", pomRequests.none { "/settled/" in it })
            assertTrue(pomRequests.none { it.endsWith("/1.0/fixture-1.0.pom") })
            val fetched = pomRequests.size
            assertEquals("Downloaded POM dates are reused", mapOf("fixture" to "1.1", "settled" to "2.0", "fixture-plugin" to "1.1"),
                nativeVersions())
            assertEquals(fetched, pomRequests.size)

            options.loadState(VersionCheckerSettings.Options(minimumReleaseAgeHours = 2))
            assertEquals(mapOf("fixture" to "1.2", "settled" to "2.0", "fixture-plugin" to "1.2"), nativeVersions())

            // Projects with custom extensions use the Versions goals, which cannot see release dates.
            options.loadState(VersionCheckerSettings.Options(minimumReleaseAgeHours = 24))
            val fallback = background { MavenVersionLookup.check(manager, mavenProject) }
            assertEquals("The Versions goal fallback applies the same minimum age",
                mapOf("fixture" to "1.1", "settled" to "2.0"), fallback.mapKeys { it.key.artifactId })
        } finally {
            options.loadState(previousOptions)
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(previousSettings)
            server.stop(0)
            repository.resolve(group.replace('.', '/')).toFile().deleteRecursively()
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
                    val id = MavenId("io.github.khopland.demo", artifact, "1.0-SNAPSHOT")
                    val properties = java.util.Properties()
                    // A mapped project needs a model state before DOM can resolve inherited properties.
                    val xml = com.intellij.openapi.util.JDOMUtil.load(Files.readString(pom))
                    for (source in listOf(com.intellij.openapi.util.JDOMUtil.load(Files.readString(directory.resolve("pom.xml"))), xml))
                        source.getChild("properties", source.namespace)?.children.orEmpty().forEach { properties[it.name] = it.textTrim }
                    updateState(MavenModel().apply { mavenId = id; setProperties(properties) },
                        emptyList(), "21", emptyList(), MavenExplicitProfiles.NONE, emptySet(), emptyMap(),
                        directory.resolve("repository"), false)
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

    fun testSettingsProfileMirrorAndServerAuthentication() = checkSettingsProfileMirrorAndServerAuthentication("4.0.0")

    fun testMaven4Model41SettingsProfileMirrorAndServerAuthentication() {
        if (System.getProperty("versionchecker.mavenHome").isNullOrBlank()) return
        checkSettingsProfileMirrorAndServerAuthentication("4.1.0")
    }

    private fun checkSettingsProfileMirrorAndServerAuthentication(modelVersion: String) {
        if (!java.lang.Boolean.getBoolean("versionchecker.mavenIntegration")) return
        // Keep published fixture POMs independent across Maven versions and model variants.
        val group = "example.versionchecker.run${System.nanoTime()}"
        val directory = Files.createTempDirectory("version-checker-integration-")
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString(), directory.toRealPath().toString())
        val metadataRequests = AtomicInteger()
        val publishedVersion = AtomicReference("2.0")
        val incompatiblePlugin = java.util.concurrent.atomic.AtomicBoolean(false)
        val pluginPomRequests = CopyOnWriteArrayList<String>()
        val expectedAuth = AtomicReference("Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray()))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = repositoryPath(exchange)
                if (exchange.requestHeaders.getFirst("Authorization") != expectedAuth.get()) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (path.endsWith("/maven-metadata.xml")) {
                    metadataRequests.incrementAndGet()
                    val artifact = if ("/fixture-plugin/" in path) "fixture-plugin" else "fixture"
                    val body = """
                        <metadata><groupId>${group}</groupId><artifactId>$artifact</artifactId>
                        <versioning><latest>4.0-SNAPSHOT</latest><release>3.0-RC1</release><versions>
                        <version>1.0</version><version>1.0.1</version><version>1.1</version><version>1.1.1</version><version>${publishedVersion.get()}</version>${if (artifact == "fixture-plugin" && incompatiblePlugin.get()) "<version>8.0</version>" else ""}<version>3.0-RC1</version><version>4.0-SNAPSHOT</version>
                        </versions><lastUpdated>20261005000000</lastUpdated></versioning></metadata>
                    """.trimIndent().toByteArray()
                    sendRepositoryResponse(exchange, body)
                } else if ("/fixture-plugin/" in path && path.endsWith(".pom")) {
                    pluginPomRequests += path
                    val version = path.substringBeforeLast('/').substringAfterLast('/')
                    val body = """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                        <groupId>${group}</groupId><artifactId>fixture-plugin</artifactId><version>$version</version>
                        <packaging>maven-plugin</packaging><prerequisites><maven>${if (version == "8.0") "99.0" else "3.6.3"}</maven></prerequisites></project>""".toByteArray()
                    sendRepositoryResponse(exchange, body)
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
            val projects = if (modelVersion == "4.1.0") "subprojects" else "modules"
            val childElement = if (modelVersion == "4.1.0") "subproject" else "module"
            Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/$modelVersion">
                  <modelVersion>$modelVersion</modelVersion><groupId>${group}</groupId><artifactId>app</artifactId><version>1</version>
                  <packaging>pom</packaging><$projects><$childElement>child</$childElement></$projects>
                  <properties><fixture.version>1.0</fixture.version><fixture-plugin.version>1.0</fixture-plugin.version></properties>
                  <dependencies><dependency><groupId>${group}</groupId><artifactId>fixture</artifactId><version>${'$'}{fixture.version}</version></dependency></dependencies>
                  <build><pluginManagement><plugins><plugin><groupId>${group}</groupId><artifactId>fixture-plugin</artifactId>
                    <version>${'$'}{fixture-plugin.version}</version></plugin></plugins></pluginManagement></build>
                </project>
            """.trimIndent())
            val childPom = Files.createDirectories(directory.resolve("child")).resolve("pom.xml")
            Files.writeString(childPom, """
                <project xmlns="http://maven.apache.org/POM/$modelVersion"><modelVersion>$modelVersion</modelVersion>
                <parent><groupId>${group}</groupId><artifactId>app</artifactId><version>1</version></parent>
                <artifactId>child</artifactId><dependencies><dependency><groupId>${group}</groupId>
                <artifactId>fixture</artifactId><version>1.1</version></dependency></dependencies>
                <build><plugins><plugin><groupId>${group}</groupId><artifactId>fixture-plugin</artifactId><version>1.1</version></plugin></plugins></build></project>
            """.trimIndent())
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom)!!
            val mavenProject = MavenProject(virtualFile)
            mavenProject.updateMavenId(MavenId(group, "app", "1"))
            val future = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking {
                    withBackgroundProgress(project, "Checking Maven dependencies", cancellable = true) {
                        MavenVersionLookup.check(manager, mavenProject)
                    }
                }
            })
            val updates = PlatformTestUtil.waitForFuture(future, 120_000)
            assertEquals("2.0", updates[DependencyVersion(group, "fixture", "1.0")])
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
                    [DependencyVersion(group, "fixture", "1.0")])
            }
            val childFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(childPom)!!
            val childProject = MavenProject(childFile).apply { updateMavenId(MavenId(group, "child", "1")) }
            val childCheck = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, childProject, UpdateMode.PATCH) }
            })
            assertEquals("1.1.1", PlatformTestUtil.waitForFuture(childCheck, 120_000)
                [DependencyVersion(group, "fixture", "1.1")])

            val beforeSettingsChange = metadataRequests.get()
            expectedAuth.set("Basic " + Base64.getEncoder().encodeToString("fixture:changed".toByteArray()))
            Files.writeString(settings, Files.readString(settings).replace("<password>password</password>", "<password>changed</password>"))
            val refreshedSettings = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, mavenProject) }
            })
            assertEquals("2.0", PlatformTestUtil.waitForFuture(refreshedSettings, 120_000)
                [DependencyVersion(group, "fixture", "1.0")])
            assertTrue("Each check must pick up externally edited settings credentials", metadataRequests.get() > beforeSettingsChange)

            val beforePublication = metadataRequests.get()
            publishedVersion.set("2.1")
            val afterPublication = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { MavenVersionLookup.check(manager, mavenProject) }
            })
            assertEquals("A newly published release must bypass Maven's daily metadata cache", "2.1",
                PlatformTestUtil.waitForFuture(afterPublication, 120_000)
                    [DependencyVersion(group, "fixture", "1.0")])
            assertTrue(metadataRequests.get() > beforePublication)
            publishedVersion.set("2.0")

            // Native goal checks above accept a standalone POM. Registering imported projects below
            // also requires their effective model: property resolution reads the child's inherited state.
            for (imported in listOf(mavenProject, childProject)) {
                imported.updateState(MavenModel().apply {
                    mavenId = imported.mavenId
                    setProperties(java.util.Properties().apply {
                        setProperty("fixture.version", "1.0")
                        setProperty("fixture-plugin.version", "1.0")
                    })
                    if (imported == childProject) setParent(MavenParent(mavenProject.mavenId, "../pom.xml"))
                }, emptyList(), "21", emptyList(), MavenExplicitProfiles(listOf("fixture-profile")),
                    emptySet(), emptyMap(), directory.resolve("repository"), false)
                manager.projectsTree.putVirtualFileToProjectMapping(imported, imported.mavenId)
            }
            val native = BuildSystemAdapter.find("maven")!!
            val snapshot = native.snapshot(project, virtualFile)!!
            val firstCategory = CompletableDeferred<InspectionUpdate>()
            val releaseCategories = CompletableDeferred<Unit>()
            val staged = object : BuildSystemAdapter by native {
                override suspend fun checkIncrementally(project: com.intellij.openapi.project.Project, snapshot: BuildSnapshot,
                                                        mode: UpdateMode, publish: suspend (InspectionUpdate) -> Unit): UpdateReport =
                    native.checkIncrementally(project, snapshot, mode) { update ->
                        publish(update)
                        if (firstCategory.complete(update)) releaseCategories.await()
                    }
            }
            val coordinator = project.service<VersionCheckService>()
            val stagedCheck = coordinator.refresh(listOf(staged), virtualFile)
            try {
                PlatformTestUtil.waitWithEventsDispatching("Native Maven dependency hints arrive before plugin checks", { firstCategory.isCompleted }, 120_000)
                val early = runBlocking { firstCategory.await() }
                assertTrue(early.completedDeclarations.all { it.location.startsWith("DEPENDENCY:") })
                assertEquals(listOf("2.0"), early.report.candidates.map { it.version })
                assertFalse(stagedCheck.isCompleted)
                assertNull("A partial category cannot authorize a bulk preview", coordinator.cached(snapshot))
                val earlyFile = PsiManager.getInstance(project).findFile(virtualFile)!!
                val earlyProblems = ProblemsHolder(InspectionManager.getInstance(project), earlyFile, true)
                val earlyVisitor = NewerMavenDependencyInspection().buildCapturedVisitor(earlyProblems, true)
                PsiTreeUtil.findChildrenOfType(earlyFile, XmlTag::class.java).forEach { it.accept(earlyVisitor) }
                assertEquals(2, earlyProblems.results.size)
                assertTrue(earlyProblems.results.all { it.fixes!!.any { fix -> fix is IgnorePublishedVersionFix } })
                assertFalse(earlyProblems.results.first().descriptionTemplate.contains("Maven plugin"))
                releaseCategories.complete(Unit)
                PlatformTestUtil.waitWithEventsDispatching("Native Maven categories complete", { stagedCheck.isCompleted }, 120_000)
                assertTrue(coordinator.cached(snapshot)!!.failure.orEmpty(), coordinator.cached(snapshot)!!.successful)
                assertEquals(2, coordinator.cached(snapshot)!!.candidates.size)
            } finally { releaseCategories.complete(Unit); stagedCheck.cancel() }
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
                service.updates(mavenProject)[DependencyVersion(group, "fixture", "1.0")] == "2.0"
            }, 120_000)
            val childCoordinate = DependencyVersion(group, "fixture", "1.1")
            PlatformTestUtil.waitWithEventsDispatching("Child background check", {
                service.updates(childProject)[childCoordinate] == "2.0"
            }, 120_000)
            project.service<VersionCheckService>().refresh("maven", virtualFile)
            assertEquals("Current POM refresh must preserve the other module's cache", "2.0", service.updates(childProject)[childCoordinate])
            PlatformTestUtil.waitWithEventsDispatching("Current POM refresh", {
                service.updates(mavenProject)[DependencyVersion(group, "fixture", "1.0")] == "2.0"
            }, 120_000)
            PlatformTestUtil.waitWithEventsDispatching("Complete Maven dependency and plugin results", {
                native.snapshot(project, virtualFile)?.let { coordinator.cached(it)?.successful } == true
            }, 120_000)
            val file = PsiManager.getInstance(project).findFile(virtualFile)!!
            val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val visitor = NewerMavenDependencyInspection().buildCapturedVisitor(holder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(visitor) }
            val problem = holder.results.single { it.psiElement is XmlTag && (it.psiElement as XmlTag).localName == "fixture.version" }
            val pluginProblem = holder.results.single { it.psiElement is XmlTag && (it.psiElement as XmlTag).localName == "fixture-plugin.version" }
            assertEquals(ProblemHighlightType.WARNING, pluginProblem.highlightType)
            assertTrue(pluginProblem.descriptionTemplate.contains("1.0 → 2.0"))
            assertEquals(ProblemHighlightType.WARNING, problem.highlightType)
            assertTrue(problem.descriptionTemplate.contains("1.0 → 2.0"))
            assertTrue(problem.fixes!!.filterIsInstance<UpdateDependencyVersionFix>().single().name.startsWith("Update fixture.version to 2.0 in pom.xml"))

            myFixture.configureFromExistingVirtualFile(virtualFile)
            PlatformTestUtil.waitWithEventsDispatching("Editor-open version check", {
                service.updates(mavenProject)[DependencyVersion(group, "fixture", "1.0")] == "2.0"
            }, 120_000)
            val options = project.service<VersionCheckerSettings>().state
            options.majorSeverity = VersionSeverity.ERROR
            val errorHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val errorVisitor = NewerMavenDependencyInspection().buildCapturedVisitor(errorHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(errorVisitor) }
            assertEquals(4, errorHolder.results.size)
            assertTrue(errorHolder.results.all { it.highlightType == ProblemHighlightType.GENERIC_ERROR })
            options.majorSeverity = VersionSeverity.DISABLED
            val disabledHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val disabledVisitor = NewerMavenDependencyInspection().buildCapturedVisitor(disabledHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(disabledVisitor) }
            assertTrue(disabledHolder.results.isEmpty())
            options.majorSeverity = VersionSeverity.WARNING
            val standard = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val standardVisitor = NewerMavenDependencyInspection().buildCapturedVisitor(standard, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(standardVisitor) }
            assertEquals(4, standard.results.size)
            assertTrue(standard.results.all { it.highlightType == ProblemHighlightType.WARNING })

            // Use the refreshed descriptor after editor setup has changed the POM's modification stamp.
            val currentProblem = standard.results.single { it.psiElement is XmlTag && (it.psiElement as XmlTag).localName == "fixture.version" }
            currentProblem.fixes!!.filterIsInstance<UpdateDependencyVersionFix>().single().applyFix(project, currentProblem)
            val tags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            assertEquals("2.0", tags.single { it.localName == "fixture.version" }.value.trimmedText)
            assertTrue(tags.any { it.localName == "version" && it.value.trimmedText == "\${fixture.version}" })
            val after = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val afterVisitor = NewerMavenDependencyInspection().buildCapturedVisitor(after, true)
            tags.forEach { it.accept(afterVisitor) }
            assertTrue("Edited versions must not get stale errors", after.results.none { !it.descriptionTemplate.contains("Maven plugin") })
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            incompatiblePlugin.set(true)
            native.invalidateMetadata(project)
            val pluginSnapshot = native.snapshot(project, virtualFile)!!
            fun <T> background(action: suspend () -> T): T = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { action() } }), 120_000)
            val compatible = background { native.check(project, pluginSnapshot, UpdateMode.MAJOR) }
            assertTrue(compatible.failure.orEmpty(), compatible.successful)
            assertEquals("Skip a newer plugin requiring Maven 99, then select the compatible candidate", "2.0",
                compatible.candidates.single { it.declaration.coordinate().artifactKind == MavenArtifactKind.PLUGIN }.version)
            val metadata = project.service<MavenMetadataService>()
            val context = background { metadata.contexts.get(manager, mavenProject, pluginSnapshot, metadata.cache.generation()) }
            val incompatible = MavenMetadataKey(group, "fixture-plugin", true, "8.0")
            val requirement = background { metadata.cache.getMany(context.id, listOf(incompatible)) {
                error("The native lookup must already have cached the incompatible POM's prerequisites")
            }.getValue(incompatible).getOrThrow() }
            assertEquals("99.0", requirement.value.requiredMaven)
            val pomCount = pluginPomRequests.size
            val cachedCompatible = background { native.check(project, pluginSnapshot, UpdateMode.MAJOR) }
            assertEquals(compatible.candidates, cachedCompatible.candidates)
            assertEquals("Reuse plugin prerequisite decisions", pomCount, pluginPomRequests.size)

            val extension = Files.createDirectories(directory.resolve(".mvn")).resolve("extensions.xml")
            Files.writeString(extension, "<extensions/>")
            val fallbackSnapshot = native.snapshot(project, virtualFile)!!
            val fallbackContext = background { project.service<MavenMetadataService>().contexts.get(manager, mavenProject,
                fallbackSnapshot, project.service<MavenMetadataService>().cache.generation()) }
            assertFalse("Custom core extension configuration retains the Versions goal fallback", fallbackContext.nativeSupported)
        } finally {
            manager.projectsTree.setIgnoredState(manager.projects, true)
            manager.embeddersManager.reset()
            manager.generalSettings.setUserSettingsFile(oldSettings)
            server.stop(0)
            Path.of(System.getProperty("user.home"), ".m2", "repository").resolve(group.replace('.', '/')).toFile().deleteRecursively()
            directory.toFile().deleteRecursively()
        }
    }
}
