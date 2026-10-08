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
    fun testHelpersSubstitutionsAndCatalogFeaturesCannotReceiveAutomaticEdits() {
        if (!java.lang.Boolean.getBoolean("versionchecker.gradleIntegration")) return
        val directory = Files.createTempDirectory("version-checker-gradle-safety-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val settings = GradleSettings.getInstance(project)
        val previousOffline = settings.isOfflineWork
        try {
            settings.isOfflineWork = false
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            val repository = Files.createDirectories(directory.resolve("repo/example/versionchecker"))
            for (artifact in listOf("plain", "substituted", "version-specific", "fixtures")) {
                val artifactDirectory = Files.createDirectories(repository.resolve(artifact))
                Files.writeString(artifactDirectory.resolve("maven-metadata.xml"), """<metadata>
                    <groupId>example.versionchecker</groupId><artifactId>$artifact</artifactId>
                    <versioning><versions><version>1.2.3</version><version>2.0.0</version></versions></versioning>
                </metadata>""")
                for (version in listOf("1.2.3", "2.0.0")) {
                    val publication = Files.createDirectories(artifactDirectory.resolve(version))
                    Files.writeString(publication.resolve("$artifact-$version.pom"), """<project>
                        <modelVersion>4.0.0</modelVersion><groupId>example.versionchecker</groupId>
                        <artifactId>$artifact</artifactId><version>$version</version>
                    </project>""")
                }
            }
            // The original fixture publication has the feature; the newer publication does not.
            val fixturePublication = repository.resolve("fixtures/1.2.3")
            Files.writeString(fixturePublication.resolve("fixtures-1.2.3.pom"), """<project>
                <!-- do_not_remove: published-with-gradle-metadata -->
                <modelVersion>4.0.0</modelVersion><groupId>example.versionchecker</groupId>
                <artifactId>fixtures</artifactId><version>1.2.3</version>
            </project>""")
            Files.writeString(fixturePublication.resolve("fixtures-1.2.3.module"), """{
                "formatVersion": "1.1",
                "component": { "group": "example.versionchecker", "module": "fixtures", "version": "1.2.3" },
                "variants": [{
                    "name": "testFixturesApiElements",
                    "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                    "capabilities": [{ "group": "example.versionchecker", "name": "fixtures-test-fixtures", "version": "1.2.3" }]
                }, {
                    "name": "testFixturesRuntimeElements",
                    "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                    "capabilities": [{ "group": "example.versionchecker", "name": "fixtures-test-fixtures", "version": "1.2.3" }]
                }]
            }""")
            Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'safety-fixture'\ninclude 'local'\n")
            val local = Files.createDirectories(directory.resolve("local"))
            Files.writeString(local.resolve("build.gradle"), "plugins { id 'java-library' }")
            Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { maven { url = uri('repo') } }
                void verifyNotation(String notation) {
                    if (notation != 'example.versionchecker:plain:1.2.3') throw new GradleException('Helper was edited')
                }
                configurations.configureEach {
                    resolutionStrategy.dependencySubstitution {
                        substitute module('example.versionchecker:substituted') using project(':local')
                        substitute module('example.versionchecker:version-specific:1.2.3') using project(':local')
                        substitute module('example.versionchecker:unpublished') using project(':local')
                    }
                }
                dependencies {
                    implementation 'example.versionchecker:plain:1.2.3'
                    verifyNotation('example.versionchecker:plain:1.2.3')
                    implementation 'example.versionchecker:substituted:1.2.3'
                    implementation 'example.versionchecker:version-specific:1.2.3'
                    implementation 'example.versionchecker:unpublished:1.2.3'
                    implementation libs.plain
                    testImplementation testFixtures(libs.fixtures)
                    testImplementation testFixtures('example.versionchecker:fixtures:1.2.3')
                }
            """.trimIndent())
            val catalog = Files.createDirectories(directory.resolve("gradle")).resolve("libs.versions.toml")
            Files.writeString(catalog, """
                [versions]
                shared = "1.2.3"
                [libraries]
                plain = { module = "example.versionchecker:plain", version.ref = "shared" }
                fixtures = { module = "example.versionchecker:fixtures", version.ref = "shared" }
            """.trimIndent())
            val wrapper = Files.createDirectories(directory.resolve("gradle/wrapper"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            settings.linkProject(GradleProjectSettings().apply {
                externalProjectPath = directory.toString()
                gradleJvm = "#JAVA_HOME"
                distributionType = DistributionType.DEFAULT_WRAPPED
                setModules(setOf(directory.toString(), local.toString()))
            })
            fun <T> background(work: suspend () -> T): T = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { work() } }), 120_000)
            val adapter = GradleBuildSystemAdapter()
            val source = root.findChild("build.gradle")!!
            val snapshot = adapter.snapshot(project, source)!!
            val report = background { adapter.check(project, snapshot, UpdateMode.MAJOR) }
            assertEquals(listOf("plain"), report.candidates.map { it.declaration.artifact.name })
            assertEquals(setOf("substituted", "version-specific", "unpublished"), report.notices.map { it.declaration.artifact.name }.toSet())
            assertTrue(report.notices.all { it.kind == NoticeKind.MANUAL_REVIEW })
            val catalogSnapshot = adapter.snapshot(project, root.findFileByRelativePath("gradle/libs.versions.toml")!!)!!
            val catalogReport = background { adapter.check(project, catalogSnapshot, UpdateMode.MAJOR) }
            assertEquals(listOf("plain"), catalogReport.candidates.map { it.declaration.artifact.name })
            assertEquals("fixtures", catalogReport.notices.single().declaration.artifact.name)
            assertTrue(catalogReport.notices.single().message.contains("features"))
            val plan = background { adapter.prepareUpdates(project, mapOf(snapshot to report, catalogSnapshot to catalogReport)) }
            assertEquals(1, plan.changes.size)
            assertEquals("2.0.0", plan.changes.single().latest)
            assertTrue(plan.skipped.any { it.contains("substitution") })
            assertTrue(plan.skipped.any { it.contains("features") })
            assertTrue(plan.apply(project))
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            val changed = PsiManager.getInstance(project).findFile(source)!!.text
            assertTrue(changed.contains("implementation 'example.versionchecker:plain:2.0.0'"))
            assertTrue(changed.contains("verifyNotation('example.versionchecker:plain:1.2.3')"))
            assertTrue(changed.contains("substituted:1.2.3"))
            assertTrue(changed.contains("version-specific:1.2.3"))
            assertTrue(changed.contains("unpublished:1.2.3"))
            assertTrue(changed.contains("testFixtures('example.versionchecker:fixtures:1.2.3')"))
            assertTrue(Files.readString(catalog).contains("shared = \"1.2.3\""))
            // Evaluating the edited build also proves the helper argument was preserved.
            val after = adapter.snapshot(project, source)!!
            assertTrue(background { adapter.check(project, after, UpdateMode.MAJOR) }.candidates.isEmpty())
            assertFalse(Files.exists(directory.resolve("build/classes")))
        } finally {
            settings.unlinkExternalProject(directory.toString())
            settings.isOfflineWork = previousOffline
            directory.toFile().deleteRecursively()
        }
    }

    fun testNativeStableQualifierCaseVariantsAndServicePackOrdering() {
        if (!java.lang.Boolean.getBoolean("versionchecker.gradleIntegration")) return
        val directory = Files.createTempDirectory("version-checker-gradle-qualifiers-").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
        val settings = GradleSettings.getInstance(project)
        val previousOffline = settings.isOfflineWork
        val cases = listOf(
            Triple("final", "1.0.Final", listOf("1.0.Final", "1.1.final")),
            Triple("ga", "1.0.GA", listOf("1.0.GA", "1.1.ga")),
            Triple("jre", "1.0.JRE", listOf("1.0.JRE", "1.1.jre", "9.0.android")),
            Triple("android", "1.0.android", listOf("1.0.android", "1.1.ANDROID", "9.0-jre")),
            Triple("pack", "1.0-sp1", listOf("1.0-sp1", "1.0-sp2")),
            Triple("pack-ten", "1.0-sp2", listOf("1.0-sp1", "1.0-sp2", "1.0-sp10")),
            Triple("pack-case", "1.0-SP1", listOf("1.0-SP1", "1.0-sp2")),
            Triple("pack-mixed", "1.0-sp1", listOf("1.0-sp1", "1.0-SP2", "1.0-sp10")),
            Triple("pack-single", "1-SP1", listOf("1-SP1", "1-sp2")),
            Triple("reverse", "1.0-sp10", listOf("1.0-sp1", "1.0-sp2", "1.0-sp10"))
        )
        try {
            settings.isOfflineWork = false
            project.service<VersionCheckerSettings>().loadState(VersionCheckerSettings.Options())
            for ((artifact, _, versions) in cases) {
                val publication = Files.createDirectories(directory.resolve("repo/example/versionchecker/$artifact"))
                Files.writeString(publication.resolve("maven-metadata.xml"), """<metadata><groupId>example.versionchecker</groupId>
                  <artifactId>$artifact</artifactId><versioning><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>""")
                for (version in versions) {
                    val path = Files.createDirectories(publication.resolve(version))
                    Files.writeString(path.resolve("$artifact-$version.pom"), """<project><modelVersion>4.0.0</modelVersion>
                      <groupId>example.versionchecker</groupId><artifactId>$artifact</artifactId><version>$version</version></project>""")
                }
            }
            Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'qualifier-fixture'\n")
            Files.writeString(directory.resolve("build.gradle"), """plugins { id 'java' }
                repositories { maven { url = uri('repo') } }
                dependencies {
                    ${cases.joinToString("\n") { (artifact, current, _) -> "implementation 'example.versionchecker:$artifact:$current'" }}
                }
            """.trimIndent())
            val wrapper = Files.createDirectories(directory.resolve("gradle/wrapper"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"))
            Files.copy(Path.of("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"))
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            settings.linkProject(GradleProjectSettings().apply {
                externalProjectPath = directory.toString()
                gradleJvm = "#JAVA_HOME"
                distributionType = DistributionType.DEFAULT_WRAPPED
                setModules(setOf(directory.toString()))
            })
            val adapter = GradleBuildSystemAdapter()
            val snapshot = adapter.snapshot(project, root.findChild("build.gradle")!!)!!
            val report = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { adapter.check(project, snapshot, UpdateMode.MAJOR) }
            }), 120_000)
            assertEquals(mapOf("final" to "1.1.final", "ga" to "1.1.ga", "jre" to "1.1.jre", "android" to "1.1.ANDROID",
                "pack" to "1.0-sp2", "pack-ten" to "1.0-sp10", "pack-case" to "1.0-sp2", "pack-mixed" to "1.0-sp10", "pack-single" to "1-sp2"),
                report.candidates.associate { it.declaration.artifact.name to it.version })
            assertTrue(report.candidates.filter { it.declaration.artifact.name.startsWith("pack") }.all { it.kind == VersionChangeKind.PATCH })
            val patch = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { adapter.check(project, snapshot, UpdateMode.PATCH) }
            }), 120_000)
            assertEquals(report.candidates.filter { it.declaration.artifact.name.startsWith("pack") }.associate { it.declaration.artifact.name to it.version },
                patch.candidates.associate { it.declaration.artifact.name to it.version })
            assertFalse(Files.exists(directory.resolve("build/classes")))
        } finally {
            settings.unlinkExternalProject(directory.toString())
            settings.isOfflineWork = previousOffline
            directory.toFile().deleteRecursively()
        }
    }

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
