package io.github.khopland.versionchecker

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.khopland.versionchecker.core.*
import io.github.khopland.versionchecker.gradle.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Real IDEA Gradle tooling + project wrapper + authenticated settings repositories. No public artifact queries. */
class GradleRepositoryIntegrationTest : BasePlatformTestCase() {
    fun testNativeGradleRepositoryAuthenticationCatalogSubprojectsAndModes() {
        if (!java.lang.Boolean.getBoolean("versionchecker.gradleIntegration")) return
        val directory = Files.createTempDirectory("version-checker-gradle-integration-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val requests = CopyOnWriteArrayList<String>()
        val deny = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val auth = "Basic " + Base64.getEncoder().encodeToString("fixture:password".toByteArray())
        server.createContext("/") { exchange ->
            try {
                requests += exchange.requestURI.path
                if (deny.get() || exchange.requestHeaders.getFirst("Authorization") != auth) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else {
                    val artifact = exchange.requestURI.path.split('/').getOrNull(3).orEmpty()
                    val body = when {
                        exchange.requestURI.path.endsWith("maven-metadata.xml") -> """<metadata><groupId>example.versionchecker</groupId><artifactId>$artifact</artifactId><versioning><versions>
                            <version>1.2.3</version><version>1.2.9</version><version>1.9.0</version><version>2.0.0</version><version>3.0-RC1</version><version>4.0-SNAPSHOT</version>
                            </versions></versioning></metadata>"""
                        exchange.requestURI.path.endsWith(".pom") -> {
                            val version = exchange.requestURI.path.substringBeforeLast('/').substringAfterLast('/')
                            """<project><modelVersion>4.0.0</modelVersion><groupId>example.versionchecker</groupId><artifactId>$artifact</artifactId><version>$version</version>${if (artifact == "bom") "<packaging>pom</packaging>" else ""}</project>"""
                        }
                        else -> null
                    }?.toByteArray()
                    if (body == null) exchange.sendResponseHeaders(404, -1) else {
                        exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
                    }
                }
            } finally { exchange.close() }
        }
        server.start()
        val settings = GradleSettings.getInstance(project)
        val previousOffline = settings.isOfflineWork
        try {
            settings.isOfflineWork = false
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            Files.writeString(directory.resolve("settings.gradle"), """
                rootProject.name = 'gradle-fixture'
                include 'nested:child'
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        maven {
                            url = uri('http://127.0.0.1:${server.address.port}/')
                            allowInsecureProtocol = true
                            credentials { username = 'fixture'; password = 'password' }
                            content { includeGroup 'example.versionchecker' }
                        }
                    }
                }
            """.trimIndent())
            Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation 'example.versionchecker:literal:1.2.3'
                    implementation platform('example.versionchecker:bom:1.2.3')
                }
                tasks.register('mustNotRun') { doLast { throw new GradleException('Build task executed') } }
                allprojects {
                    tasks.matching { it.name in ['compileJava', 'compileTestJava', 'jar', 'test', 'assemble', 'build'] }.configureEach {
                        doFirst { throw new GradleException('Application build task executed: ' + path) }
                    }
                }
            """.trimIndent())
            val child = Files.createDirectories(directory.resolve("nested/child"))
            Files.writeString(child.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies { implementation(libs.alpha); testImplementation(libs.beta) }
            """.trimIndent())
            val catalog = Files.createDirectories(directory.resolve("gradle")).resolve("libs.versions.toml")
            Files.writeString(catalog, """
                [versions]
                shared = "1.2.3"
                [libraries]
                alpha = { module = "example.versionchecker:alpha", version.ref = "shared" }
                beta = { module = "example.versionchecker:beta", version.ref = "shared" }
            """.trimIndent())
            val wrapper = Files.createDirectories(directory.resolve("gradle/wrapper"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"))
            val lock = directory.resolve("gradle.lockfile")
            Files.writeString(lock, "# fixture lockfile remains unchanged\n")
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            settings.linkProject(GradleProjectSettings().apply {
                externalProjectPath = directory.toString()
                gradleJvm = "#JAVA_HOME"
                distributionType = DistributionType.DEFAULT_WRAPPED
                setModules(setOf(directory.toString(), child.toString()))
            })
            val adapter = GradleBuildSystemAdapter()
            val source = root.findChild("build.gradle")!!
            fun <T> background(work: suspend () -> T): T = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { work() } }), 120_000)
            for ((mode, expected) in listOf(UpdateMode.PATCH to "1.2.9", UpdateMode.MINOR to "1.9.0", UpdateMode.MAJOR to "2.0.0")) {
                val plan = background { project.service<BulkUpdateService>().createPlan(mode, UpdateScope.WHOLE_PROJECT) }
                assertEquals("literal, BOM and shared catalog updates for $mode", 3, plan.changes.size)
                assertEquals(setOf(expected), plan.changes.map { it.latest }.toSet())
                val local = background { project.service<BulkUpdateService>().createPlan(mode, UpdateScope.CURRENT_FILE, source) }
                assertEquals(2, local.changes.size)
                assertTrue(local.changes.all { it.location.startsWith(source.path) })
            }
            val snapshot = adapter.snapshot(project, source)!!
            val service = project.service<VersionCheckService>()
            service.updates(adapter, snapshot)
            PlatformTestUtil.waitWithEventsDispatching("Gradle background check", { service.cached(snapshot) != null }, 120_000)
            val psi = PsiManager.getInstance(project).findFile(source)!!
            val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
            NewerGradleDependencyInspection().buildVisitor(holder, true).visitFile(psi)
            assertEquals(2, holder.results.size)
            val literalProblem = holder.results.single { it.descriptionTemplate.contains(":literal") }
            assertTrue(literalProblem.descriptionTemplate.contains("1.2.3 → 2.0.0"))
            assertEquals(1, literalProblem.fixes!!.size)
            project.service<VersionCheckerSettings>().state.majorSeverity = VersionSeverity.DISABLED
            val hidden = ProblemsHolder(InspectionManager.getInstance(project), psi, true)
            NewerGradleDependencyInspection().buildVisitor(hidden, true).visitFile(psi)
            assertTrue(hidden.results.isEmpty())
            project.service<VersionCheckerSettings>().state.majorSeverity = VersionSeverity.WARNING
            literalProblem.fixes!!.single().applyFix(project, literalProblem)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            assertTrue(psi.text.contains("literal:2.0.0"))
            val plan = background { project.service<BulkUpdateService>().createPlan(UpdateMode.PATCH) }
            assertEquals(2, plan.changes.size) // The literal is already newer; the BOM and catalog change.
            assertTrue(plan.apply(project))
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            assertEquals("# fixture lockfile remains unchanged\n", Files.readString(lock))
            assertTrue(requests.any { it.endsWith("maven-metadata.xml") })
            assertFalse(requests.any { it.endsWith(".jar") })
            assertFalse(Files.exists(directory.resolve("build/classes")))
            assertFalse(Files.exists(directory.resolve("build/libs")))
            // Credentials failures must not be converted into an empty successful result.
            deny.set(true)
            val changed = adapter.snapshot(project, source)!!
            val failure = runCatching { background { adapter.check(project, changed, UpdateMode.MAJOR) } }.exceptionOrNull()
            assertNotNull("Repository authentication failure must be surfaced", failure)
        } finally {
            settings.unlinkExternalProject(directory.toString())
            settings.isOfflineWork = previousOffline
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
