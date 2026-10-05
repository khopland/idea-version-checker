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
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in: starts IDEA's real Maven server and may download the Versions goal from Maven Central. */
class MavenSettingsIntegrationTest : BasePlatformTestCase() {
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
                        <version>1.0</version><version>1.0.1</version><version>1.1</version><version>1.1.1</version><version>2.0</version><version>3.0-RC1</version><version>4.0-SNAPSHOT</version>
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
                      <releases><updatePolicy>always</updatePolicy></releases></repository>
                  </repositories><pluginRepositories><pluginRepository><id>fixture-source</id><url>http://127.0.0.1:1/unmirrored</url>
                    <releases><updatePolicy>always</updatePolicy></releases></pluginRepository></pluginRepositories></profile></profiles>
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
            service.refresh(virtualFile)
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
            options.majorSeverity = DependencySeverity.ERROR
            val errorHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val errorVisitor = NewerMavenDependencyInspection().buildVisitor(errorHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(errorVisitor) }
            assertEquals(2, errorHolder.results.size)
            assertTrue(errorHolder.results.all { it.highlightType == ProblemHighlightType.GENERIC_ERROR })
            options.majorSeverity = DependencySeverity.DISABLED
            val disabledHolder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val disabledVisitor = NewerMavenDependencyInspection().buildVisitor(disabledHolder, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(disabledVisitor) }
            assertTrue(disabledHolder.results.isEmpty())
            options.majorSeverity = DependencySeverity.WARNING
            val standard = ProblemsHolder(InspectionManager.getInstance(project), file, true)
            val standardVisitor = NewerMavenDependencyInspection().buildVisitor(standard, true)
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { it.accept(standardVisitor) }
            assertEquals(2, standard.results.size)
            assertTrue(standard.results.all { it.highlightType == ProblemHighlightType.WARNING })

            WriteCommandAction.runWriteCommandAction(project) {
                problem.fixes!!.single().applyFix(project, problem)
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
